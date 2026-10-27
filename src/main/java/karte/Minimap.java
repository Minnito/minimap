package karte;

import client.api.AddonConfig;
import client.api.Draw;
import client.api.HudElement;
import client.api.Profiler;
import client.api.Setting;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

// Minimap im Stil von JourneyMap: Bild aus den Kartenkacheln (Quadrat oder Kreis, optional mit Blickrichtung gedreht),
// Rahmen mit Kompass, Wegpunkte, Entity-Radar, Spielerpfeil und vier Info-Zeilen (1–2 oben, 3–4 unten).
final class Minimap {
    static final double[] ZOOM = {0.125, 0.25, 0.5, 1, 2, 4, 8};   // GUI-Pixel je Block
    private static final int ZEILE = 10;

    enum Modus {
        AUTO, TAG, NACHT, TOPO, HOEHLE;

        String text() {
            return this == AUTO ? T.t("modus.auto") : T.t("typ." + name().toLowerCase());
        }
    }

    enum Info {
        LEER, ZEIT, ORT, BIOM, DIMENSION, FPS, TAG, LICHT;

        String text() {
            return T.t("info." + name().toLowerCase());
        }
    }

    private final AddonConfig cfg;
    private final Kartendaten daten;
    final Ansicht.Filter filter;
    private boolean an, kreis, drehen, kompass, gitter;
    private int size, zoom;
    private Modus modus;
    private final Info[] slots = new Info[4];
    private final String[] texte = new String[4];   // je Tick neu – ändern sich nicht schneller, Biom/Format sind teuer
    private final int[] breiten = new int[4];

    private NativeImage bild;
    private Identifier bildId;
    private int bildRes;
    private Object[] zuletzt = {};   // Stand des letzten Bildes – nur neu rechnen, wenn sich etwas geändert hat

    Minimap(AddonConfig cfg, Kartendaten daten, Ansicht.Filter filter) {
        this.cfg = cfg;
        this.daten = daten;
        this.filter = filter;
        an = cfg.bool("mm_an", true);
        kreis = cfg.bool("mm_kreis", false);
        drehen = cfg.bool("mm_drehen", false);
        kompass = cfg.bool("mm_kompass", true);
        gitter = cfg.bool("mm_gitter", false);
        size = cfg.number("size", 128);
        zoom = Math.clamp(cfg.number("mm_zoomstufe", 2), 0, ZOOM.length - 1);
        modus = Karte.wahl(cfg, "mm_modus", Modus.AUTO);
        Info[] std = {Info.LEER, Info.ZEIT, Info.ORT, Info.BIOM};
        for (int i = 0; i < 4; i++) slots[i] = Karte.wahl(cfg, "mm_info" + i, std[i]);
    }

    void tick() {
        if (mc().player == null || mc().level == null) return;
        for (int i = 0; i < 4; i++) {
            texte[i] = slots[i] == Info.LEER ? null : info(slots[i]).getString();
            breiten[i] = texte[i] == null ? 0 : Stil.breite(texte[i]);
        }
    }

    void umschalten() {
        cfg.set("mm_an", an = !an);
    }

    void zoom(int d) {
        cfg.set("mm_zoomstufe", zoom = Math.clamp(zoom + d, 0, ZOOM.length - 1));
    }

    void naechsterModus() {
        modus = Modus.values()[(modus.ordinal() + 1) % Modus.values().length];
        cfg.set("mm_modus", modus.name());
    }

    /** Welche Ebene die Minimap gerade zeigt. */
    Kartendaten.Ebene ebene() {
        var mc = Minimap.mc();
        var p = mc.player;
        int schicht = p == null ? 64 : p.getBlockY();   // Höhlenansicht genau auf deiner Höhe
        return switch (modus) {
            case TAG -> Kartendaten.Ebene.of(Kartendaten.Typ.TAG);
            case NACHT -> Kartendaten.Ebene.of(Kartendaten.Typ.NACHT);
            case TOPO -> Kartendaten.Ebene.of(Kartendaten.Typ.TOPO);
            case HOEHLE -> new Kartendaten.Ebene(Kartendaten.Typ.HOEHLE, schicht);
            case AUTO -> {
                if (p != null && (mc.level.dimensionType().hasCeiling() || unterTage())) yield new Kartendaten.Ebene(Kartendaten.Typ.HOEHLE, schicht);
                yield Kartendaten.Ebene.of(nacht() ? Kartendaten.Typ.NACHT : Kartendaten.Typ.TAG);
            }
        };
    }

    // Unter Tage oder drinnen: mehr als 3 Blöcke unter der Oberfläche
    private static boolean unterTage() {
        var mc = mc();
        var b = mc.player.blockPosition();
        return b.getY() < mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, b.getX(), b.getZ()) - 3;
    }

    static boolean nacht() {
        long t = mc().level.getOverworldClockTime() % 24000;
        return t >= 13000 && t < 23000;
    }

    HudElement hud() {
        int oben = zeilen(0, 2), unten = zeilen(2, 4);
        return new HudElement(Karte.ID, T.t("hud.minimap"), 1, 0, -size - 8, 6, size, oben + size + 6 + unten, this::zeichne).visibleIf(() -> an);
    }

    /** Höhe einer Info-Platte samt Abstand zur Karte; 0 = keine Zeile in diesem Bereich. */
    private int zeilen(int von, int bis) {
        int n = 0;
        for (int i = von; i < bis; i++) if (slots[i] != Info.LEER) n++;
        return n == 0 ? 0 : n * ZEILE + 6 + 4;
    }

    private void zeichne(GuiGraphicsExtractor g, int x, int y) {
        var mc = mc();
        var p = mc.player;
        if (p == null || mc.level == null) return;
        int my = y + zeilen(0, 2) + 3;
        float half = size / 2f;

        var a = new Ansicht();
        a.cx = p.getX();
        a.cz = p.getZ();
        a.scale = ZOOM[zoom];
        a.ox = x + half;
        a.oy = my + half;
        a.drehung(kreis && drehen ? p.getYRot() : 180);

        // Rahmen: deckend mit feiner Kontur, leichter Schatten hebt ihn von hellem Gelände ab
        if (kreis) {
            Draw.circle(g, (int) a.ox, (int) a.oy + 1, (int) half + 5, 0x40000000);
            Draw.circle(g, (int) a.ox, (int) a.oy, (int) half + 3, Stil.RAHMEN);
            Draw.circle(g, (int) a.ox, (int) a.oy, (int) half + 1, Stil.LINIE);
        } else {
            Draw.dropShadow(g, x - 3, my - 3, size + 6, size + 6, 5, 0, 1, 4, 0x50000000);
            Draw.roundRect(g, x - 3, my - 3, size + 6, size + 6, 5, Stil.RAHMEN);
            Ansicht.rechteck(g, x - 1, my - 1, size + 2, size + 2, Stil.LINIE);
        }
        // Wie Xaero: Karte und Markierungen in echten Bildschirmpixeln, unabhängig von der GUI-Skalierung
        float k = mc.getWindow().getGuiScale();
        var e = ebene();
        if (kreis) {
            Profiler.section("minimap.bild", () -> bild(a, e, Math.min(512, Math.round(size * k))));
            Draw.image(g, bildId, x, my, size, size, bildRes, bildRes);
        } else Profiler.section("minimap.kacheln", () -> Draw.clip(g, x, my, size, size, () -> kacheln(g, a, e, x, my, k)));

        g.pose().pushMatrix();
        g.pose().scale(1 / k, 1 / k);
        a.ox *= k;
        a.oy *= k;
        a.scale *= k;
        a.schrift = k;
        var rand = new Ansicht.Rand(kreis, (half - 2) * k, (half - 2) * k);
        a.wegpunkte(g, filter, rand);
        a.radar(g, filter, rand, 12);
        a.spieler(g, 15);
        g.pose().popMatrix();
        a.ox /= k;
        a.oy /= k;
        a.scale /= k;
        if (kompass) kompass(g, a, new Ansicht.Rand(kreis, half - 2, half - 2));

        platte(g, 0, 2, x + size / 2, y);
        platte(g, 2, 4, x + size / 2, my + size + 3 + 4);
    }

    // Kompass auf dem Rahmen: Norden in der Akzentfarbe, die anderen leise – zeigt auf einen Blick, wo oben ist
    private void kompass(GuiGraphicsExtractor g, Ansicht a, Ansicht.Rand rand) {
        String[] namen = {T.t("kompass.n"), T.t("kompass.o"), T.t("kompass.s"), T.t("kompass.w")};
        int[][] dir = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            float dx = a.sx(a.cx + dir[i][0] * 1e4, a.cz + dir[i][1] * 1e4) - a.ox;
            float dy = a.sy(a.cx + dir[i][0] * 1e4, a.cz + dir[i][1] * 1e4) - a.oy;
            float[] e = rand.klemmen(dx, dy);
            int px = Math.round(a.ox + e[0]), py = Math.round(a.oy + e[1]);
            Draw.circle(g, px, py, 6, i == 0 ? Stil.akzent() : Stil.RAHMEN);
            Stil.text(g, namen[i], px - Stil.breite(namen[i]) / 2, py - 4, i == 0 ? Stil.aufAkzent() : Stil.LEISE);
        }
    }

    // Info-Zeilen eines Bereichs auf einer gemeinsamen Platte: erste Zeile hell, weitere leiser
    private void platte(GuiGraphicsExtractor g, int von, int bis, int mitte, int y) {
        int n = 0, w = 0;
        for (int i = von; i < bis; i++)
            if (texte[i] != null) {
                n++;
                w = Math.max(w, breiten[i]);
            }
        if (n == 0) return;
        w += 12;
        Stil.panel(g, mitte - w / 2, y, w, n * ZEILE + 6);
        int ly = y + 4, nr = 0;
        for (int i = von; i < bis; i++) {
            if (texte[i] == null) continue;
            Stil.text(g, texte[i], mitte - breiten[i] / 2, ly, nr++ == 0 ? Stil.TEXT : Stil.LEISE);
            ly += ZEILE;
        }
    }

    static Component info(Info i) {
        var mc = mc();
        var p = mc.player;
        var b = p.blockPosition();
        long t = mc.level.getOverworldClockTime();
        return switch (i) {
            case LEER -> Component.empty();
            case ZEIT -> Component.literal(String.format("%02d:%02d", (t / 1000 + 6) % 24, (t % 1000) * 60 / 1000));
            case ORT -> Component.literal(b.getX() + ", " + b.getY() + ", " + b.getZ());
            case BIOM -> biom(b);
            case DIMENSION -> Component.literal(mc.level.dimension().identifier().getPath());
            case FPS -> Component.literal(T.t("info.fps.wert", mc.getFps()));
            case TAG -> Component.literal(T.t("info.tag.wert", t / 24000 + 1));
            case LICHT -> Component.literal(T.t("info.licht.wert", mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(b)));
        };
    }

    static Component biom(net.minecraft.core.BlockPos b) {
        return mc().level.getBiome(b).unwrapKey()
            .<Component>map(k -> Component.translatable(k.identifier().toLanguageKey("biome")))
            .orElse(Component.literal("?"));
    }

    // Quadrat (nie gedreht): die Kacheltexturen direkt zeichnen wie die Weltkarte – kein Neuaufbau beim Laufen, nur der Ausschnitt.
    // In echten Bildschirmpixeln, damit die Karte beim Gehen gleitet statt in GUI-Pixeln zu springen.
    private void kacheln(GuiGraphicsExtractor g, Ansicht a, Kartendaten.Ebene e, int x, int y, float k) {
        Ansicht.rechteck(g, x, y, size, size, 0xFF101014);              // unerkundet
        daten.hoehlen.budget(2);                                        // neue Höhlenbilder (deine Höhe) portionsweise
        daten.uebersichtBudget(1);
        boolean grob = a.scale < 0.5 && e.typ() != Kartendaten.Typ.HOEHLE;   // weit draußen: Übersichten wie auf der Weltkarte
        int span = Kartendaten.TILE * (grob ? Kartendaten.UEBERSICHT : 1);
        double halb = size / 2.0 / a.scale;                             // Blöcke von der Mitte bis zum Rand
        int t0x = Math.floorDiv((int) Math.floor(a.cx - halb), span), t1x = Math.floorDiv((int) Math.floor(a.cx + halb), span);
        int t0z = Math.floorDiv((int) Math.floor(a.cz - halb), span), t1z = Math.floorDiv((int) Math.floor(a.cz + halb), span);
        var r = new Ansicht();                                          // ungedreht, Norden oben, in echten Pixeln
        r.cx = a.cx;
        r.cz = a.cz;
        r.scale = a.scale * k;
        r.ox = a.ox * k;
        r.oy = a.oy * k;
        g.pose().pushMatrix();
        g.pose().scale(1 / k, 1 / k);
        for (int tz = t0z; tz <= t1z; tz++)
            for (int tx = t0x; tx <= t1x; tx++) {
                var id = grob ? daten.uebersicht(e, tx, tz) : daten.textur(e, tx, tz);
                if (id == null) continue;
                int x0 = Math.round(r.sx((double) tx * span, 0)), x1 = Math.round(r.sx((double) (tx + 1) * span, 0));
                int y0 = Math.round(r.sy(0, (double) tz * span)), y1 = Math.round(r.sy(0, (double) (tz + 1) * span));
                Draw.image(g, id, x0, y0, x1 - x0, y1 - y0, 0, 0, Kartendaten.TILE, Kartendaten.TILE, Kartendaten.TILE, Kartendaten.TILE, 0xFFFFFFFF);
            }
        if (gitter) r.gitter(g, Math.round(x * k), Math.round(y * k), Math.round(size * k), Math.round(size * k));
        g.pose().popMatrix();
    }

    // Kartenbild auf der CPU zusammensetzen (nur Kreis): so gehen Kreisform und Drehung ohne Schablonen-Tricks
    // ponytail: bei großer Karte und hoher GUI-Skalierung bis 512² Pixel je Neuaufbau; nur bei Bewegung um ≥ 1 Bildpunkt neu
    private void bild(Ansicht a, Kartendaten.Ebene e, int res) {
        if (bild == null || bildRes != res) {
            bild = new NativeImage(res, res, true);
            bildId = Draw.texture(Karte.ID, "minimap", bild);   // gleicher Name ersetzt die alte Textur
            bildRes = res;
            zuletzt = new Object[0];
        }
        double bpt = size / (a.scale * res);   // Blöcke je Bildpunkt
        Object[] stand = {Math.floor(a.cx / bpt), Math.floor(a.cz / bpt), a.fx, a.fz, a.scale, e, daten.version(), kreis, gitter, res};
        if (Arrays.equals(stand, zuletzt)) return;
        zuletzt = stand;

        double half = res / 2.0;
        for (int j = 0; j < res; j++) {
            double v = j + 0.5 - half;
            for (int i = 0; i < res; i++) {
                double u = i + 0.5 - half;
                if (kreis && u * u + v * v > half * half) {
                    bild.setPixel(i, j, 0);
                    continue;
                }
                double bu = u * bpt, bv = v * bpt;      // Blöcke nach rechts / unten
                double wx = a.cx + bu * a.rx - bv * a.fx, wz = a.cz + bu * a.rz - bv * a.fz;
                int c = daten.pixel(e, (int) Math.floor(wx), (int) Math.floor(wz));
                if (c == 0) c = 0xFF101014;             // unerkundet
                if (gitter && (wx - 16 * Math.floor(wx / 16) < bpt || wz - 16 * Math.floor(wz / 16) < bpt))
                    c = 0xFF000000 | Kartendaten.darker(c & 0xFFFFFF, 0.6f);
                bild.setPixel(i, j, c);
            }
        }
        Draw.upload(bildId);
    }

    // Einstellungen in kurzen Seiten: der Client baut die Liste nach jedem Schalter neu auf und springt dabei nach oben –
    // ohne Scrollen gibt es nichts, wohin er springen kann
    Setting anzeigen() {
        return Setting.toggle(T.t("mm.anzeigen"), T.t("mm.anzeigen.info"), () -> an, v -> cfg.set("mm_an", an = v));
    }

    List<Setting> seiteMinimap() {
        return List.of(
            Setting.choice(T.t("mm.form"), null, List.of(false, true), () -> kreis,
                v -> cfg.set("mm_kreis", kreis = v), v -> T.t(v ? "mm.form.kreis" : "mm.form.quadrat")),
            Setting.toggle(T.t("mm.drehen"), T.t("mm.drehen.info"), () -> drehen,
                v -> cfg.set("mm_drehen", drehen = v)).visibleIf(() -> kreis),
            Setting.slider(T.t("mm.groesse"), T.t("mm.groesse.info"), 64, 256, () -> size, v -> cfg.set("size", size = v), v -> T.t("mm.groesse.wert", v)),
            Setting.choice(T.t("mm.zoom"), T.t("mm.zoom.info"), List.of(0, 1, 2, 3, 4, 5, 6), () -> zoom,
                v -> cfg.set("mm_zoomstufe", zoom = v), v -> T.t("mm.zoom.wert", Math.round(size / ZOOM[v] / 2))),
            Setting.choice(T.t("mm.typ"), T.t("mm.typ.info"), List.of(Modus.values()),
                () -> modus, v -> cfg.set("mm_modus", (modus = v).name()), Modus::text),
            Setting.toggle(T.t("mm.kompass"), T.t("mm.kompass.info"), () -> kompass, v -> cfg.set("mm_kompass", kompass = v)),
            Setting.toggle(T.t("mm.gitter"), null, () -> gitter, v -> cfg.set("mm_gitter", gitter = v)));
    }

    List<Setting> seiteKreaturen() {
        var l = new ArrayList<Setting>();
        for (var art : Ansicht.Art.values())
            l.add(Setting.toggle(art.text(), null, () -> filter.zeigt(art), v -> filter.setze(art, v)));
        l.add(Setting.choice(T.t("mm.kreaturen"), T.t("mm.kreaturen.info"),
            List.of(true, false), () -> filter.koepfe, filter::setzeKoepfe, v -> T.t(v ? "mm.koepfe" : "mm.punkte")));
        l.add(Setting.toggle(T.t("mm.wegpunkte"), null, () -> filter.wegpunkte, filter::setzeWegpunkte));
        l.add(Setting.toggle(T.t("mm.namen"), null, () -> filter.namen, filter::setzeNamen).visibleIf(() -> filter.wegpunkte));
        return l;
    }

    List<Setting> seiteInfo() {
        var l = new ArrayList<Setting>();
        for (int i = 0; i < 4; i++) {
            int n = i;
            l.add(Setting.choice(T.t(n < 2 ? "mm.zeile.oben" : "mm.zeile.unten", n + 1), null, List.of(Info.values()), () -> slots[n],
                v -> cfg.set("mm_info" + n, (slots[n] = v).name()), Info::text));
        }
        return l;
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
