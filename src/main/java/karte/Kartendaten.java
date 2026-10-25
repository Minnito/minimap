package karte;

import client.api.AddonConfig;
import client.api.Draw;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.material.MapColor;

// Kartendaten wie bei JourneyMap: geladene Chunks abtasten und je Block einen Pixel malen – in Kacheln zu 256 × 256 Blöcken,
// getrennt nach Ebene (Tag, Nacht, Topo, Höhle je 16er-Schicht – alle Schichten in einem Durchlauf). Gespeichert als PNG unter
// client-modules/karte/<welt>/<dimension>/<ebene>/<x>_<z>.png. Reihenfolge: Ringe um den Spieler, nächste zuerst, im Uhrzeigersinn.
// ponytail: alle gesehenen Kacheln bleiben im Speicher (256 KB je Kachel und Ebene, ~27 Ebenen); bei riesigen Karten Kacheln außer Sicht entladen.
final class Kartendaten {
    static final int TILE = 256;
    // Zeitbudget je Tick statt fester Chunk-Zahl; Chunks erst nach 10 s erneut abtasten.
    // Beim Beenden des Spiels kommt kein unload – darum oft speichern (nur geänderte Kacheln).
    private static final long BUDGET_NS = 4_000_000;
    private static final int FRISCH_TICKS = 20 * 10, SAVE_TICKS = 20 * 10;

    enum Typ {
        TAG, NACHT, TOPO, HOEHLE;

        String text() {
            return T.t("typ." + name().toLowerCase());
        }
    }

    /** Eine Kartenebene; y zählt nur bei HOEHLE (jede Blockhöhe, siehe Hoehlen). */
    record Ebene(Typ typ, int y) {
        static Ebene of(Typ typ) {
            return new Ebene(typ, 0);
        }

        String ordner() {
            return typ.name().toLowerCase();
        }
    }

    final Hoehlen hoehlen = new Hoehlen();

    private static final class Tile {
        final NativeImage image;
        final Identifier id;
        final Path file;
        final String uebersicht;   // Schlüssel der Übersicht, zu der die Kachel gehört
        boolean dirty;

        Tile(NativeImage image, Identifier id, Path file, String uebersicht) {
            this.image = image;
            this.id = id;
            this.file = file;
            this.uebersicht = uebersicht;
        }
    }

    // ───── Übersichten fürs weite Herauszoomen: 4 × 4 Kacheln (1024 Blöcke) in einem 256er-Bild ─────
    static final int UEBERSICHT = 4;
    private static final int MAX_UEBERSICHTEN = 200;

    private static final class Bild {
        NativeImage img;
        Identifier id;      // null = hier ist nichts erkundet
        int version = -1;
    }

    private final Map<String, Integer> uebersichtVersion = new HashMap<>();
    private final Map<String, Bild> uebersichten = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bild> e) {
            if (size() <= MAX_UEBERSICHTEN) return false;
            if (e.getValue().id != null) Minecraft.getInstance().getTextureManager().release(e.getValue().id);
            else if (e.getValue().img != null) e.getValue().img.close();
            return true;
        }
    };
    private int uebersichtBudget;

    private static String uebersichtKey(Ebene e, int ox, int oz) {
        return e.ordner() + "|" + ox + "|" + oz;
    }

    /** Wie viele Übersichten in diesem Bild neu gebaut werden dürfen. */
    void uebersichtBudget(int n) {
        uebersichtBudget = n;
    }

    /** Übersichtsbild (ox, oz in Einheiten von 4 Kacheln), null = nichts erkundet oder noch nicht gebaut. Nur Oberflächen-Ebenen. */
    Identifier uebersicht(Ebene e, int ox, int oz) {
        if (e.typ() == Typ.HOEHLE || dir == null) return null;
        String key = uebersichtKey(e, ox, oz);
        int v = uebersichtVersion.getOrDefault(key, 0);
        Bild b = uebersichten.get(key);
        if (b != null && b.version == v) return b.id;
        if (uebersichtBudget <= 0) return b == null ? null : b.id;
        uebersichtBudget--;
        if (b == null) uebersichten.put(key, b = new Bild());
        if (b.img == null) b.img = new NativeImage(TILE, TILE, true);
        boolean etwas = false;
        int teil = TILE / UEBERSICHT;
        for (int sz = 0; sz < UEBERSICHT; sz++)
            for (int sx = 0; sx < UEBERSICHT; sx++) {
                Tile t = tile(e, ox * UEBERSICHT + sx, oz * UEBERSICHT + sz, false);
                etwas |= t != null;
                for (int py = 0; py < teil; py++)
                    for (int px = 0; px < teil; px++)
                        b.img.setPixel(sx * teil + px, sz * teil + py, t == null ? 0 : t.image.getPixel(px * UEBERSICHT + 1, py * UEBERSICHT + 1));
            }
        b.version = v;
        if (!etwas) return null;
        if (b.id == null) b.id = Draw.texture(Karte.ID, "uebersicht" + serial++, b.img);
        else Draw.upload(b.id);
        return b.id;
    }

    private final Map<Ebene, Map<Long, Tile>> ebenen = new HashMap<>();
    private final Set<String> missing = new HashSet<>(); // "<ebene>/<key>" ohne Datei – nicht jedes Bild erneut nachsehen
    private final Set<Tile> unsaved = new HashSet<>();
    private final Map<Long, Integer> gescannt = new HashMap<>();  // Chunk → Tick der letzten Abtastung
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private Path dir;               // Ordner der aktuellen Welt + Dimension, null = nicht in einer Welt
    private int[] spiral = new int[0];
    private int cursor, centerX, centerZ, radius = -1, ticks, serial;
    private long version;           // steigt bei jeder Änderung – Minimap baut ihr Bild dann neu
    private Tile last;              // Zwischenspeicher für pixel()
    private Ebene lastEbene;
    private long lastKey;

    long version() {
        return version;
    }

    int geladen() {
        int n = 0;
        for (var m : ebenen.values()) n += m.size();
        return n;
    }

    /** Jeden Tick: Welt/Dimension verfolgen, Chunks im Zeitbudget abtasten, ab und zu speichern. */
    void tick() {
        var mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Path now = level == null ? null : weltOrdner().resolve(clean(level.dimension().identifier().toString()));
        if (!Objects.equals(now, dir)) {
            close();
            dir = now;
            hoehlen.ordner(now);
        }
        if (dir == null || mc.player == null) return;

        int r = mc.options.getEffectiveRenderDistance();
        if (r != radius) {
            radius = r;
            spiral = spiral(r);
            cursor = 0;
        }
        int px = mc.player.chunkPosition().x(), pz = mc.player.chunkPosition().z();
        if (px != centerX || pz != centerZ) {        // neuer Chunk: Spirale beginnt wieder bei dir
            centerX = px;
            centerZ = pz;
            cursor = 0;
        }
        ticks++;
        long ende = System.nanoTime() + BUDGET_NS;
        for (int schritt = 0; schritt < spiral.length / 2 && System.nanoTime() < ende; schritt++) {
            int cx = centerX + spiral[cursor], cz = centerZ + spiral[cursor + 1];
            cursor = (cursor + 2) % spiral.length;
            long k = key(cx, cz);
            Integer t = gescannt.get(k);
            if ((t != null && ticks - t < FRISCH_TICKS) || !level.hasChunk(cx, cz)) continue;
            scan(level, cx, cz);
            gescannt.put(k, ticks);
        }
        if (gescannt.size() > 100_000) gescannt.clear();
        if (ticks % SAVE_TICKS == 0) save();
    }

    /** Alles speichern und Texturen freigeben (Welt verlassen, Dimension gewechselt, Addon entladen). */
    void close() {
        save();
        hoehlen.close();
        for (var m : ebenen.values())
            for (var t : m.values()) Minecraft.getInstance().getTextureManager().release(t.id);
        ebenen.clear();
        for (var b : uebersichten.values())
            if (b.id != null) Minecraft.getInstance().getTextureManager().release(b.id);
            else if (b.img != null) b.img.close();
        uebersichten.clear();
        uebersichtVersion.clear();
        missing.clear();
        gescannt.clear();
        last = null;
        lastEbene = null;
        dir = null;
    }

    /** Geänderte Kacheln schreiben; liefert, wie viele jetzt als Datei vorliegen. */
    int save() {
        hoehlen.save();
        int ok = 0;
        for (var t : unsaved) {
            try {
                Files.createDirectories(t.file.getParent());
                t.image.writeToFile(t.file);
                ok++;
            } catch (IOException e) {
                System.err.println("[minimap] Speichern fehlgeschlagen: " + t.file + " – " + e);
            }
        }
        unsaved.clear();
        return ok;
    }

    /** Wie viele geladene Kacheln als Datei auf der Platte liegen. */
    int dateien() {
        int n = 0;
        for (var m : ebenen.values())
            for (var t : m.values()) if (Files.exists(t.file)) n++;
        return n;
    }

    /** Textur einer Kachel zum Zeichnen, null = noch nichts erkundet. */
    Identifier textur(Ebene e, int tx, int tz) {
        if (e.typ() == Typ.HOEHLE) return hoehlen.textur(tx, tz, e.y());
        Tile t = tile(e, tx, tz, false);
        if (t == null) return null;
        if (t.dirty) {                 // erst hochladen, wenn jemand die Kachel zeichnet – bei ~27 Ebenen spart das viel
            Draw.upload(t.id);
            t.dirty = false;
        }
        return t.id;
    }

    /** Farbe eines Blocks (ARGB), 0 = unbekannt. Merkt sich die letzte Kachel – die Minimap fragt Pixel für Pixel. */
    int pixel(Ebene e, int x, int z) {
        if (e.typ() == Typ.HOEHLE) return hoehlen.farbe(x, z, e.y());
        int tx = Math.floorDiv(x, TILE), tz = Math.floorDiv(z, TILE);
        long k = key(tx, tz);
        if (last == null || k != lastKey || !e.equals(lastEbene)) {
            last = tile(e, tx, tz, false);
            lastKey = k;
            lastEbene = e;
            if (last == null) return 0;
        }
        return last.image.getPixel(Math.floorMod(x, TILE), Math.floorMod(z, TILE));
    }

    private void scan(ClientLevel level, int cx, int cz) {
        int tx = Math.floorDiv(cx * 16, TILE), tz = Math.floorDiv(cz * 16, TILE);  // ein Chunk liegt immer in einer Kachel
        Tile tag = tile(Ebene.of(Typ.TAG), tx, tz, true), nacht = tile(Ebene.of(Typ.NACHT), tx, tz, true);
        Tile topo = tile(Ebene.of(Typ.TOPO), tx, tz, true);
        if (hoehlen.scan(level, cx, cz)) version++;
        int minY = level.getMinY();
        var blockLight = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        for (int dz = 0; dz < 16; dz++) {
            int z = cz * 16 + dz, pz = Math.floorMod(z, TILE);
            for (int dx = 0; dx < 16; dx++) {
                int x = cx * 16 + dx, px = Math.floorMod(x, TILE);
                int y = surface(level, x, z, minY);
                if (y > minY) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    MapColor map = state.getMapColor(level, pos);
                    boolean wet = !state.getFluidState().isEmpty();
                    int depth = wet ? depth(level, x, y, z) : 0;
                    int north = surface(level, x, z - 1, minY);
                    int day = map.calculateARGBColor(shade(wet, depth, y, north));
                    put(tag, px, pz, day);
                    put(nacht, px, pz, night(day, blockLight.getLightValue(pos.set(x, y + 1, z))));
                    put(topo, px, pz, wet ? water(depth) : topo(y, north));
                }
            }
        }
    }

    private void put(Tile t, int px, int pz, int argb) {
        if (t.image.getPixel(px, pz) == argb) return;
        t.image.setPixel(px, pz, argb);
        uebersichtVersion.merge(t.uebersicht, 1, Integer::sum);   // Übersicht neu bauen, sobald jemand sie zeichnet
        t.dirty = true;
        unsaved.add(t);
        version++;
    }

    // Oberster Block mit Kartenfarbe (Glas, Barrieren … durchsehen); minY = nichts
    private int surface(ClientLevel level, int x, int z, int minY) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        while (y > minY && level.getBlockState(pos.set(x, y, z)).getMapColor(level, pos) == MapColor.NONE) y--;
        return y;
    }

    private int depth(ClientLevel level, int x, int y, int z) {
        int d = 0;
        while (d < 10 && !level.getBlockState(pos.set(x, y - d - 1, z)).getFluidState().isEmpty()) d++;
        return d;
    }

    // Wie bei Vanilla-Karten: nach Höhe des nördlichen Nachbarn bzw. Wassertiefe schattieren
    private static MapColor.Brightness shade(boolean wet, int depth, int y, int north) {
        if (wet) return depth > 6 ? MapColor.Brightness.LOW : depth > 2 ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH;
        return y > north ? MapColor.Brightness.HIGH : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }

    // Nacht: dunkel und bläulich, nur Lichtquellen (Blocklicht) hellen auf
    static int night(int day, int light) {
        float f = 0.18f + 0.82f * light / 15f;
        int r = (int) (((day >> 16) & 0xFF) * f), g = (int) (((day >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, ((day & 0xFF) * f) + 18 * (1 - f));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    // Topo: Farbverlauf nach Höhe, alle 8 Blöcke eine dunklere Höhenlinie
    private static final int[] TOPO_Y = {-64, 40, 64, 90, 120, 160, 200, 320};
    private static final int[] TOPO_C = {0x1C2B3A, 0x3E6E4A, 0x6FA05A, 0xB8C46A, 0xA88A58, 0x8C7A6E, 0xD8D8D8, 0xFFFFFF};

    static int topo(int y, int north) {
        int c = gradient(TOPO_Y, TOPO_C, y);
        if (Math.floorDiv(y, 8) != Math.floorDiv(north, 8)) c = darker(c, 0.65f);
        return 0xFF000000 | c;
    }

    private static int water(int depth) {
        return 0xFF000000 | darker(0x4A80C8, 1 - depth * 0.05f);
    }

    static int gradient(int[] ys, int[] cs, int y) {
        if (y <= ys[0]) return cs[0];
        for (int i = 1; i < ys.length; i++) {
            if (y <= ys[i]) {
                float t = (y - ys[i - 1]) / (float) (ys[i] - ys[i - 1]);
                int a = cs[i - 1], b = cs[i];
                int r = (int) (((a >> 16) & 0xFF) + t * (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)));
                int g = (int) (((a >> 8) & 0xFF) + t * (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)));
                int bl = (int) ((a & 0xFF) + t * ((b & 0xFF) - (a & 0xFF)));
                return r << 16 | g << 8 | bl;
            }
        }
        return cs[cs.length - 1];
    }

    static int darker(int rgb, float f) {
        f = Math.clamp(f, 0, 1);
        return (int) (((rgb >> 16) & 0xFF) * f) << 16 | (int) (((rgb >> 8) & 0xFF) * f) << 8 | (int) ((rgb & 0xFF) * f);
    }

    private Tile tile(Ebene e, int tx, int tz, boolean create) {
        long key = key(tx, tz);
        var m = ebenen.computeIfAbsent(e, k -> new HashMap<>());
        Tile t = m.get(key);
        String miss = e.ordner() + "/" + key;
        if (t != null || dir == null || (!create && missing.contains(miss))) return t;
        Path file = dir.resolve(e.ordner()).resolve(tx + "_" + tz + ".png");
        NativeImage image = null;
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                image = NativeImage.read(in);
            } catch (IOException ex) {
                System.err.println("[minimap] Kachel unlesbar, wird neu gemalt: " + file + " – " + ex);
            }
        }
        if (image == null) {
            if (!create) {
                missing.add(miss);
                return null;
            }
            image = new NativeImage(TILE, TILE, true);
        }
        missing.remove(miss);
        String ueb = uebersichtKey(e, Math.floorDiv(tx, UEBERSICHT), Math.floorDiv(tz, UEBERSICHT));
        uebersichtVersion.merge(ueb, 1, Integer::sum);           // neu geladene Kachel gehört in die Übersicht
        t = new Tile(image, Draw.texture(Karte.ID, "tile" + serial++, image), file, ueb);
        m.put(key, t);
        return t;
    }

    /**
     * Wo die Karte der aktuellen Welt liegt. Einzelspieler: im Weltordner (saves/<Welt>/karte) – wandert beim Umbenennen,
     * Kopieren und Sichern mit und verschwindet mit der Welt. Server: in der Instanz je Server-Adresse (dort gibt es keinen Weltordner).
     * Braucht die Berechtigung "files" (Schreiben außerhalb von client-modules/karte).
     */
    private static Object ordnerFuer;
    private static Path ordner;

    static Path weltOrdner() {
        var sp = Minecraft.getInstance().getSingleplayerServer();
        String name = worldName();
        Object fuer = sp == null ? name : sp;          // je Welt einmal bestimmen (und ggf. umziehen), nicht jeden Tick
        if (fuer.equals(ordnerFuer)) return ordner;
        ordnerFuer = fuer;
        return ordner = bestimmeOrdner(sp, name);
    }

    private static Path bestimmeOrdner(net.minecraft.client.server.IntegratedServer sp, String name) {
        Path instanz = AddonConfig.dir(Karte.ID).resolve(name);
        if (sp == null) return instanz;
        Path weltRoot = sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), welt = weltRoot.resolve(Karte.ID);
        Umzug.ordner(weltRoot.resolve(Umzug.ALT), welt);                  // saves/<Welt>/karte → saves/<Welt>/minimap (alte Addon-ID)
        if (!Files.exists(welt) && Files.isDirectory(instanz)) {          // alte Karte aus der Instanz einmalig umziehen
            try {
                Files.move(instanz, welt);
                System.out.println("[minimap] Karte in den Weltordner umgezogen: " + welt);
            } catch (IOException e) {
                System.err.println("[minimap] Umzug in den Weltordner fehlgeschlagen, Karte bleibt in der Instanz: " + e);
                return instanz;
            }
        }
        return welt;
    }

    /** Eindeutiger Name der aktuellen Welt bzw. des Servers – für Ordner und je-Welt-Einstellungen. */
    static String worldName() {
        var mc = Minecraft.getInstance();
        var sp = mc.getSingleplayerServer();
        // Ordner des Spielstands statt Anzeigename: zwei Welten namens „New World“ liegen in „New World“ und „New World (1)“
        if (sp != null) return "sp_" + clean(sp.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
        var server = mc.getCurrentServer();   // auch Direktverbindung, nicht nur Serverliste
        return server == null ? "unbekannt" : clean(server.ip);
    }

    static String clean(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }

    static long key(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
    }

    // Chunk-Versätze (dx, dz) im Umkreis r: Ring für Ring (nächste zuerst), im Ring im Uhrzeigersinn ab Norden
    static int[] spiral(int r) {
        var list = new ArrayList<int[]>();
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                if (dx * dx + dz * dz <= r * r) list.add(new int[] {dx, dz});
        list.sort(Comparator.<int[]>comparingLong(a -> Math.round(Math.hypot(a[0], a[1])))
            .thenComparingDouble(a -> (Math.atan2(a[0], -a[1]) + 2 * Math.PI) % (2 * Math.PI)));   // Norden = 0, Osten = 90°
        int[] out = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            out[i * 2] = list.get(i)[0];
            out[i * 2 + 1] = list.get(i)[1];
        }
        return out;
    }
}
