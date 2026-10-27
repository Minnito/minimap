package karte;

import client.api.Draw;
import client.api.Tasks;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.MapColor;

// Höhlenkarte für JEDE Y-Höhe: je Blocksäule die „Böden“ (fester Block mit Luft darüber) mit Farbe und Decke darüber.
// Daraus ergibt sich jede Höhe sofort: Y im Hohlraum → dessen Boden; Y im Stein → Höhlen bis 15 Blöcke darunter (aufgeschnitten).
// Gespeichert je Region (32 × 32 Chunks) unter <dimension>/hoehlen/<rx>_<rz>.bin; Bilder je (Kachel, Y) nur im Speicher.
final class Hoehlen {
    static final int FEST = 0xFF0A0A0C;              // fester Stein ohne Hohlraum in Reichweite
    private static final int MAX_BOEDEN = 48, FENSTER = 15, MAX_BILDER = 160;

    /** Böden eines Chunks: Säule c = z * 16 + x hat die Einträge start[c] … start[c + 1] − 1, von oben nach unten. */
    private record Chunk(short[] start, short[] y, short[] decke, int[] farbe) {
        boolean gleich(Chunk o) {
            return o != null && Arrays.equals(start, o.start) && Arrays.equals(y, o.y) && Arrays.equals(decke, o.decke) && Arrays.equals(farbe, o.farbe);
        }
    }

    private static final class Bild {
        final NativeImage img = new NativeImage(Kartendaten.TILE, Kartendaten.TILE, true);
        Identifier id;
        int version = -1;
    }

    // Je Region (32 × 32 Chunks) eine eigene Map: Speichern geht nur über geänderte Regionen, Entladen ist ein remove
    private final Map<Long, Map<Long, Chunk>> regionen = new HashMap<>();
    private final Map<Long, Integer> regionenGeladen = new HashMap<>();   // Region → Speicherrunde, in der sie zuletzt gebraucht wurde
    private final Set<Long> regionenGeaendert = new HashSet<>();
    private static final int UNGENUTZT_RUNDEN = 6;                        // 6 × 10 s nicht gebraucht → aus dem Speicher
    private int runde;
    private final Map<Long, Integer> kachelVersion = new HashMap<>();
    private final Map<String, Bild> bilder = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bild> e) {
            if (size() <= MAX_BILDER) return false;
            if (e.getValue().id != null) Minecraft.getInstance().getTextureManager().release(e.getValue().id);
            return true;
        }
    };
    private final Map<Long, String> zuletzt = new HashMap<>();   // Kachel → zuletzt gerechnetes Bild (Platzhalter beim schnellen Ziehen)
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private Path dir;
    private int budget, serial, generation;
    private long letzterKey = Long.MIN_VALUE;
    private Chunk letzter;

    void ordner(Path dimension) {
        dir = dimension == null ? null : dimension.resolve("hoehlen");
    }

    int chunks() {
        int n = 0;
        for (var r : regionen.values()) n += r.size();
        return n;
    }

    int regionen() {
        return regionen.size();
    }

    /** Wie viele Kachelbilder in diesem Bild noch neu gerechnet werden dürfen (je ~1 ms). */
    void budget(int n) {
        budget = n;
    }

    void close() {
        save();
        for (var b : bilder.values()) if (b.id != null) Minecraft.getInstance().getTextureManager().release(b.id);
        bilder.clear();
        zuletzt.clear();
        regionen.clear();
        regionenGeladen.clear();
        kachelVersion.clear();
        letzter = null;
        letzterKey = Long.MIN_VALUE;
        generation++;                                     // noch laufende Ladevorgänge verwerfen
        dir = null;
    }

    // ───── Abtasten: alle Böden eines Chunks in einem Durchlauf von oben nach unten ─────
    /** true = Daten haben sich geändert. */
    boolean scan(ClientLevel level, int cx, int cz) {
        region(cx >> 5, cz >> 5);
        LevelChunkSection[] sections = level.getChunk(cx, cz).getSections();
        short[] start = new short[257];
        short[] ys = new short[256 * 4], de = new short[256 * 4];
        int[] fa = new int[256 * 4];
        int n = 0, top = level.getMaxY() + 1;
        for (int c = 0; c < 256; c++) {
            start[c] = (short) n;
            int dx = c & 15, dz = c >> 4, anzahl = 0, fest = top;
            boolean luft = true;
            for (int i = sections.length - 1; i >= 0; i--) {
                var sec = sections[i];
                if (sec.hasOnlyAir()) {
                    luft = true;
                    continue;
                }
                int sy = level.getSectionYFromSectionIndex(i) * 16;
                for (int ly = 15; ly >= 0; ly--) {
                    BlockState s = sec.getBlockState(dx, ly, dz);
                    int y = sy + ly;
                    MapColor map = s.isAir() ? MapColor.NONE : s.getMapColor(level, pos.set(cx * 16 + dx, y, cz * 16 + dz));
                    if (map == MapColor.NONE) {      // Luft, Glas … – man sieht hindurch
                        luft = true;
                        continue;
                    }
                    if (luft && anzahl < MAX_BOEDEN) {
                        if (n == ys.length) {
                            ys = Arrays.copyOf(ys, n * 2);
                            de = Arrays.copyOf(de, n * 2);
                            fa = Arrays.copyOf(fa, n * 2);
                        }
                        ys[n] = (short) y;
                        de[n] = (short) fest;
                        fa[n] = map.calculateARGBColor(MapColor.Brightness.NORMAL);
                        n++;
                        anzahl++;
                    }
                    fest = y;
                    luft = false;
                }
            }
        }
        start[256] = (short) n;
        var neu = new Chunk(start, Arrays.copyOf(ys, n), Arrays.copyOf(de, n), Arrays.copyOf(fa, n));
        long k = Kartendaten.key(cx, cz), r = Kartendaten.key(cx >> 5, cz >> 5);
        var region = regionen.computeIfAbsent(r, x -> new HashMap<>());
        if (neu.gleich(region.get(k))) return false;
        region.put(k, neu);
        if (k == letzterKey) letzter = neu;
        regionenGeaendert.add(r);
        kachelVersion.merge(Kartendaten.key(cx >> 4, cz >> 4), 1, Integer::sum);
        return true;
    }

    // ───── Farbe auf Höhe Y ─────
    /** ARGB des Blocks (x, z) von Höhe y aus gesehen, 0 = unerkundet. */
    int farbe(int x, int z, int y) {
        int cx = x >> 4, cz = z >> 4;
        long k = Kartendaten.key(cx, cz);
        if (k != letzterKey) {
            var region = regionen.get(region(cx >> 5, cz >> 5));
            letzter = region == null ? null : region.get(k);
            letzterKey = k;
        }
        if (letzter == null) return 0;
        int c = (z & 15) * 16 + (x & 15);
        return scheibe(letzter.y, letzter.decke, letzter.farbe, letzter.start[c], letzter.start[c + 1], y);
    }

    /** Kern der Höhenansicht – rein, darum auch im Selbsttest geprüft. */
    static int scheibe(short[] ys, short[] decken, int[] farben, int von, int bis, int y) {
        for (int i = von; i < bis; i++) {
            if (ys[i] > y) continue;                       // Boden liegt über der Höhe
            int tiefe = y - ys[i];
            if (y < decken[i] || tiefe <= FENSTER)         // im Hohlraum darüber – oder nah genug im Stein darunter
                return 0xFF000000 | Kartendaten.darker(farben[i] & 0xFFFFFF, 1 - Math.min(tiefe, 24) * 0.025f);
            return FEST;
        }
        return FEST;
    }

    // ───── Kachelbilder je Höhe (für die Weltkarte) ─────
    /** Bild der Kachel auf Höhe y; null = (noch) nichts. Rechnet nur im Budget, sonst ggf. das alte Bild. */
    Identifier textur(int tx, int tz, int y) {
        region(tx >> 1, tz >> 1);                          // eine Kachel (16 Chunks) liegt in einer Region (32 Chunks)
        Integer v = kachelVersion.get(Kartendaten.key(tx, tz));
        if (v == null) return null;                        // hier gibt es keine Höhlendaten
        String key = tx + "," + tz + "," + y;
        long kachel = Kartendaten.key(tx, tz);
        Bild b = bilder.get(key);
        if (b != null && b.version == v) return b.id;
        if (budget <= 0) {
            if (b != null) return b.id;
            String z = zuletzt.get(kachel);
            Bild ersatz = z == null || !bilder.containsKey(z) ? null : bilder.get(z);
            return ersatz == null ? null : ersatz.id;
        }
        budget--;
        zuletzt.put(kachel, key);
        if (b == null) bilder.put(key, b = new Bild());
        var bild = b.img;
        client.api.Profiler.section("minimap.hoehlenbild", () -> {
            for (int pz = 0; pz < Kartendaten.TILE; pz++)
                for (int px = 0; px < Kartendaten.TILE; px++)
                    bild.setPixel(px, pz, farbe(tx * Kartendaten.TILE + px, tz * Kartendaten.TILE + pz, y));
        });
        if (b.id == null) b.id = Draw.texture(Karte.ID, "hoehle" + serial++, b.img);
        else Draw.upload(b.id);
        b.version = v;
        return b.id;
    }

    // ───── Speichern / Laden je Region ─────
    private Path datei(long region) {
        return dir.resolve((int) (region >> 32) + "_" + (int) region + ".bin");
    }

    /** Region bei Bedarf im Hintergrund laden und als benutzt merken; liefert ihren Schlüssel. */
    private long region(int rx, int rz) {
        long r = Kartendaten.key(rx, rz);
        if (dir == null || regionenGeladen.put(r, runde) != null) return r;
        Path f = datei(r);
        int gen = generation;
        Kartendaten.hintergrund(() -> {                   // Entpacken dauert Millisekunden – nicht auf dem Spiel-Thread
            if (!Files.exists(f)) return;
            var gelesen = new ArrayList<Map.Entry<Long, Chunk>>();
            try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
                int anzahl = in.readInt();
                for (int i = 0; i < anzahl; i++) {
                    int cx = in.readInt(), cz = in.readInt(), n = in.readInt();
                    short[] start = new short[257], ys = new short[n], de = new short[n];
                    int[] fa = new int[n];
                    for (int j = 0; j < 257; j++) start[j] = in.readShort();
                    for (int j = 0; j < n; j++) {
                        ys[j] = in.readShort();
                        de[j] = in.readShort();
                        fa[j] = in.readInt();
                    }
                    gelesen.add(Map.entry(Kartendaten.key(cx, cz), new Chunk(start, ys, de, fa)));
                }
            } catch (IOException e) {
                System.err.println("[minimap] Höhlendaten unlesbar, werden neu erfasst: " + f + " – " + e);
            }
            Tasks.main(() -> {
                if (gen != generation) return;
                var region = regionen.computeIfAbsent(r, x -> new HashMap<>());
                for (var e : gelesen) {
                    // frisch abgetastete Daten gewinnen; dann wurde die Region ohne die alten gespeichert → erneut speichern
                    if (region.putIfAbsent(e.getKey(), e.getValue()) != null) regionenGeaendert.add(r);
                    kachelVersion.merge(Kartendaten.key((int) (e.getKey() >> 32) >> 4, (int) (long) e.getKey() >> 4), 1, Integer::sum);
                }
                letzterKey = Long.MIN_VALUE;              // farbe() hat sich evtl. „kein Chunk“ gemerkt
            });
        });
        return r;
    }

    /** Geänderte Regionen im Hintergrund schreiben (Chunk-Daten sind unveränderlich – kopierte Einträge genügen als Schnappschuss). */
    int save() {
        runde++;
        int n = 0;
        if (dir != null)
            for (long r : regionenGeaendert) {
                var region = regionen.get(r);
                if (region == null) continue;
                n++;
                schreiben(datei(r), region.entrySet().stream().map(e -> Map.entry(e.getKey(), e.getValue())).toList());
            }
        regionenGeaendert.clear();
        entladen();
        return n;
    }

    // Regionen, die seit einer Minute weder Abtasten noch Minimap noch Weltkarte gebraucht haben, aus dem Speicher werfen –
    // sie liegen gespeichert auf der Platte und kommen beim nächsten Gebrauch wieder. Bilder (bilder) bleiben im LRU-Cache.
    private void entladen() {
        var weg = new ArrayList<Long>();
        for (var e : regionenGeladen.entrySet())
            if (runde - e.getValue() >= UNGENUTZT_RUNDEN && !regionenGeaendert.contains(e.getKey())) weg.add(e.getKey());
        for (long r : weg) {
            regionenGeladen.remove(r);
            regionen.remove(r);
        }
        if (!weg.isEmpty()) letzterKey = Long.MIN_VALUE;   // letzter kann in einer entladenen Region liegen
    }

    private static void schreiben(Path f, List<Map.Entry<Long, Chunk>> inRegion) {
        Kartendaten.hintergrund(() -> {
            try {
                Path tmp = Kartendaten.tmp(f);
                try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                    out.writeInt(inRegion.size());
                    for (var e : inRegion) {
                        Chunk c = e.getValue();
                        out.writeInt((int) (e.getKey() >> 32));
                        out.writeInt((int) (long) e.getKey());
                        out.writeInt(c.y.length);
                        for (short s : c.start) out.writeShort(s);
                        for (int j = 0; j < c.y.length; j++) {
                            out.writeShort(c.y[j]);
                            out.writeShort(c.decke[j]);
                            out.writeInt(c.farbe[j]);
                        }
                    }
                }
                Kartendaten.ersetzen(tmp, f);
            } catch (IOException e) {
                Karte.fehler("Höhlendaten nicht gespeichert", f + " – " + e);
            }
        });
    }

    /** Für den Selbsttest: alle Regionen sofort entladen, als wären sie eine Minute ungenutzt. */
    void allesEntladen() {
        runde += UNGENUTZT_RUNDEN;
        entladen();
    }
}
