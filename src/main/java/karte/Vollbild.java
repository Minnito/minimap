package karte;

import client.api.AddonConfig;
import client.api.Anim;
import client.api.Draw;
import client.api.Notify;
import client.api.Page;
import client.api.Waypoints;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;

// Weltkarte im Stil von JourneyMap: Kartentypen und Filter oben, Zoom links, Positionen unten.
// Linke Maustaste halten = verschieben, Mausrad = Zoom, Rechtsklick = Menü (Wegpunkt, Koordinaten …),
// B = Wegpunkt an der Maus, F = Spieler folgen, C = Koordinaten kopieren, Pfeiltasten = 16 Blöcke verschieben, Leertaste = zu dir.
final class Vollbild {
    private final AddonConfig cfg;
    private final Kartendaten daten;
    private final Ansicht.Filter filter;
    private final Ansicht a = new Ansicht();
    private Kartendaten.Typ typ, oberTyp;   // oberTyp = letzter Oberflächen-Typ, zu dem der Slider oben zurückkehrt
    private int hoehe;                      // Y der Höhlenansicht
    private boolean gitter, folgen, schieben, seedAn;
    private final SeedKarte seedKarte = new SeedKarte();
    private SeedKarte.Modus seedModus;
    // Übergang: alte Ebene bleibt stehen, neue blendet in UEBERGANG_MS darüber ein; Slider-Knopf gleitet mit
    private static final int UEBERGANG_MS = 220;
    private Kartendaten.Ebene alt, neu;
    private long seit;
    private final Anim.Tween knopf = Anim.tween(0, 0, 160, Anim.Easing.OUT);
    private int w, h, mausX, mausY;
    private final List<Knopf> knoepfe = new ArrayList<>();
    private Menue menue;   // offenes Rechtsklick-Menü, null = keins
    Runnable manager;      // öffnet den Wegpunkt-Manager (von Karte gesetzt)

    private record Knopf(int x, int y, int w, int h, String text, String tipp, boolean aktiv, Runnable aktion) {
        boolean trifft(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Eintrag(String text, Runnable aktion) {}

    /** Rechtsklick-Menü an (x, y) für den Block (bx, bz). */
    private record Menue(int x, int y, int bx, int bz, List<Eintrag> eintraege) {
        static final int ZEILE = 14, BREITE = 150;

        int hoehe() {
            return eintraege.size() * ZEILE + 18;
        }

        int treffer(double mx, double my) {
            if (mx < x || mx >= x + BREITE || my < y + 16) return -1;
            int i = (int) ((my - y - 16) / ZEILE);
            return i < eintraege.size() ? i : -1;
        }
    }

    Vollbild(AddonConfig cfg, Kartendaten daten) {
        this.cfg = cfg;
        this.daten = daten;
        filter = new Ansicht.Filter(cfg, "fs_");
        typ = Kartendaten.Typ.valueOf(cfg.text("fs_typ", "TAG"));
        oberTyp = typ == Kartendaten.Typ.HOEHLE ? Kartendaten.Typ.TAG : typ;
        gitter = cfg.bool("fs_gitter", false);
        seedAn = cfg.bool("fs_seed", true);
        seedModus = SeedKarte.Modus.valueOf(cfg.text("seed_modus", "GELAENDE"));
        a.scale = cfg.decimal("fs_zoom", 1);
    }

    void oeffnen() {
        var p = Minecraft.getInstance().player;
        folgen = true;
        hoehe = p == null ? 64 : p.getBlockY();
        alt = null;
        neu = null;
        knopf.to(index());
        knopf.restart();
        Page.open(T.t("welt.titel"), new Page.View() {
            @Override
            public void draw(GuiGraphicsExtractor g, int w, int h, int mouseX, int mouseY) {
                zeichne(g, w, h, mouseX, mouseY);
            }

            @Override
            public boolean click(double x, double y, int button) {
                return klick(x, y, button);
            }

            @Override
            public boolean drag(double x, double y, int button, double dx, double dy) {
                if (button != InputConstants.MOUSE_BUTTON_LEFT || menue != null) return false;
                if (schieben) {
                    sliderAuf(y);
                    return true;
                }
                folgen = false;
                a.cx -= dx / a.scale;
                a.cz -= dy / a.scale;
                return true;
            }

            @Override
            public boolean scroll(double x, double y, double amount) {
                if (aufSlider(x, y)) setzeIndex(index() + (amount > 0 ? -1 : 1));   // Mausrad am Slider: 1 Block hoch/runter
                else zoom(amount > 0 ? 1.25 : 0.8, folgen ? w / 2.0 : x, folgen ? h / 2.0 : y);
                return true;
            }

            @Override
            public boolean key(int key, int modifiers) {
                return taste(key);
            }

        });
    }

    // ───── Y-Slider rechts: Index 0 = Oberfläche, darunter jede Blockhöhe von oben nach unten ─────
    private int sx0() { return w - 24; }
    private int sy0() { return 44; }
    private int sy1() { return h - 34; }

    private int minY() {
        var l = Minecraft.getInstance().level;
        return l == null ? -64 : l.getMinY();
    }

    private int maxY() {
        var l = Minecraft.getInstance().level;
        return l == null ? 319 : l.getMaxY();
    }

    private int stufen() {
        return maxY() - minY() + 2;
    }

    private int index() {
        return typ == Kartendaten.Typ.HOEHLE ? maxY() - hoehe + 1 : 0;
    }

    private int yVon(float i) {
        return sy0() + Math.round((sy1() - sy0()) * i / (stufen() - 1));
    }

    private boolean aufSlider(double x, double y) {
        return x >= sx0() - 6 && x < sx0() + 20 && y >= sy0() - 8 && y <= sy1() + 8;
    }

    private void sliderAuf(double y) {
        setzeIndex(Math.round((float) ((y - sy0()) / (sy1() - sy0()) * (stufen() - 1))));
    }

    private void setzeIndex(int i) {
        i = Math.clamp(i, 0, stufen() - 1);
        if (i == 0) {
            if (typ == Kartendaten.Typ.HOEHLE) setzeTyp(oberTyp);
            return;
        }
        hoehe = maxY() - (i - 1);
        if (typ != Kartendaten.Typ.HOEHLE) setzeTyp(Kartendaten.Typ.HOEHLE);
    }

    private void zeichneSlider(GuiGraphicsExtractor g, int mx, int my) {
        int x = sx0(), y0 = sy0(), y1 = sy1();
        Draw.roundRect(g, x - 2, y0 - 8, 18, y1 - y0 + 16, 4, 0xC0141418);
        Ansicht.rechteck(g, x + 6, y0, 2, y1 - y0, 0xFF505058);
        for (int y = Math.ceilDiv(minY(), 64) * 64; y <= maxY(); y += 64)            // Skala alle 64 Blöcke
            Ansicht.rechteck(g, x + 3, yVon(maxY() - y + 1), 8, 1, 0xFF505058);
        var p = Minecraft.getInstance().player;
        if (p != null) Ansicht.rechteck(g, x, yVon(maxY() - p.getBlockY() + 1), 14, 1, 0xFFFFFFFF);   // deine Höhe
        if (knopf.target() != index()) knopf.to(index());
        int ky = yVon(knopf.value());
        boolean ueber = schieben || aufSlider(mx, my);
        Draw.roundRect(g, x, ky - 4, 14, 8, 3, ueber ? 0xFFE070B0 : 0xFFC05090);
        String text = index() == 0 ? T.t("welt.oberflaeche") : "Y " + hoehe;
        int tw = Minecraft.getInstance().font.width(text);
        Draw.roundRect(g, x - tw - 12, ky - 6, tw + 8, 12, 3, 0xD0141418);
        Draw.text(g, text, x - tw - 8, ky - 4);
    }

    private void zeichne(GuiGraphicsExtractor g, int w, int h, int mouseX, int mouseY) {
        this.w = w;
        this.h = h;
        mausX = mouseX;
        mausY = mouseY;
        var mc = Minecraft.getInstance();
        var p = mc.player;
        if (folgen && p != null) {
            a.cx = p.getX();
            a.cz = p.getZ();
        }
        a.ox = w / 2f;
        a.oy = h / 2f;
        var ebene = new Kartendaten.Ebene(typ, typ == Kartendaten.Typ.HOEHLE ? hoehe : 0);
        if (!ebene.equals(neu)) {              // Wechsel: bisherige Ansicht wird zur alten, neue blendet ein
            alt = neu;
            neu = ebene;
            seit = System.currentTimeMillis();
        }
        float t = Math.clamp((System.currentTimeMillis() - seit) / (float) UEBERGANG_MS, 0, 1);
        float weich = t * t * (3 - 2 * t);     // sanft an- und auslaufen
        daten.hoehlen.budget(4);               // neue Höhen-Bilder portionsweise rechnen – kein Ruckeln beim Ziehen
        daten.uebersichtBudget(2);
        // Weit herausgezoomt: Übersichten (4 × 4 Kacheln je Bild) statt hunderter Einzelkacheln – Höhlen bleiben fein
        boolean grob = a.scale < 0.5 && typ != Kartendaten.Typ.HOEHLE;
        int k = grob ? Kartendaten.UEBERSICHT : 1, span = Kartendaten.TILE * k;

        Draw.clip(g, 0, 0, w, h, () -> {
            Ansicht.rechteck(g, 0, 0, w, h, 0xFF0C0C0F);
            int t0x = Math.floorDiv((int) Math.floor(a.wx(0, 0)), span), t1x = Math.floorDiv((int) Math.floor(a.wx(w, 0)), span);
            int t0z = Math.floorDiv((int) Math.floor(a.wz(0, 0)), span), t1z = Math.floorDiv((int) Math.floor(a.wz(0, h)), span);
            // von der Mitte nach außen: was du ansiehst, wird zuerst gerechnet
            var kacheln = new ArrayList<int[]>();
            for (int tz = t0z; tz <= t1z; tz++)
                for (int tx = t0x; tx <= t1x; tx++) kacheln.add(new int[] {tx, tz});
            double mtx = a.cx / span - 0.5, mtz = a.cz / span - 0.5;
            kacheln.sort(java.util.Comparator.comparingDouble(kk -> (kk[0] - mtx) * (kk[0] - mtx) + (kk[1] - mtz) * (kk[1] - mtz)));
            for (int[] kk : kacheln) {
                    int tx = kk[0], tz = kk[1];
                    var idNeu = bild(neu, tx, tz, k);
                    var idAlt = alt == null || (t >= 1 && idNeu != null) ? null : bild(alt, tx, tz, k);
                    var idSeed = seedAn && typ != Kartendaten.Typ.HOEHLE ? seedKarte.textur(tx, tz, seedModus, k) : null;
                    if (idNeu == null && idAlt == null && idSeed == null) continue;
                    int x0 = Math.round(a.sx((double) tx * span, 0)), x1 = Math.round(a.sx((double) (tx + 1) * span, 0));
                    int y0 = Math.round(a.sy(0, (double) tz * span)), y1 = Math.round(a.sy(0, (double) (tz + 1) * span));
                    // Seed-Karte (Biome aus dem Chunkbase-Seed) ganz unten: zeigt sich überall, wo noch nicht erkundet ist
                    if (idSeed != null) kachel(g, idSeed, x0, y0, x1 - x0, y1 - y0, 0xFFFFFFFF);
                    // alte Ansicht darunter (auch als Platzhalter, solange das neue Bild noch gerechnet wird), neue darüber einblenden
                    if (idAlt != null) kachel(g, idAlt, x0, y0, x1 - x0, y1 - y0, 0xFFFFFFFF);
                    if (idNeu != null) kachel(g, idNeu, x0, y0, x1 - x0, y1 - y0, (Math.round(weich * 255) << 24) | 0xFFFFFF);
                }
            if (gitter) a.gitter(g, 0, 0, w, h);
            var rand = new Ansicht.Rand(false, w / 2f - 6, h / 2f - 6);
            a.wegpunkte(g, filter, rand);
            a.radar(g, filter, rand, 10);
            a.spieler(g, 16);
            oberflaeche(g, mouseX, mouseY);
            if (menue != null) zeichneMenue(g, mouseX, mouseY);
        });
    }

    // Kachel (k = 1) oder Übersicht (k = 4) einer Ebene; Höhlen gibt es nur als Kachel
    private net.minecraft.resources.Identifier bild(Kartendaten.Ebene e, int tx, int tz, int k) {
        if (k == 1) return daten.textur(e, tx, tz);
        return e.typ() == Kartendaten.Typ.HOEHLE ? null : daten.uebersicht(e, tx, tz);
    }

    private static void kachel(GuiGraphicsExtractor g, net.minecraft.resources.Identifier id, int x, int y, int w, int h, int argb) {
        Draw.image(g, id, x, y, w, h, 0, 0, Kartendaten.TILE, Kartendaten.TILE, Kartendaten.TILE, Kartendaten.TILE, argb);
    }

    private void zeichneMenue(GuiGraphicsExtractor g, int mx, int my) {
        var m = menue;
        Draw.shadow(g, m.x, m.y, Menue.BREITE, m.hoehe(), 6, 0x80000000);
        Draw.roundRect(g, m.x, m.y, Menue.BREITE, m.hoehe(), 4, 0xF0141418);
        Draw.text(g, Draw.sub(m.bx + ", " + hoehe(m.bx, m.bz) + ", " + m.bz), m.x + 6, m.y + 4);
        int hit = m.treffer(mx, my);
        for (int i = 0; i < m.eintraege.size(); i++) {
            int y = m.y + 16 + i * Menue.ZEILE;
            if (i == hit) Draw.roundRect(g, m.x + 2, y, Menue.BREITE - 4, Menue.ZEILE, 3, 0xE0C05090);
            Draw.text(g, m.eintraege.get(i).text, m.x + 8, y + 3);
        }
    }

    // Einträge je nach Stelle: auf einem eigenen Wegpunkt auch löschen
    private void oeffneMenue(double x, double y) {
        int bx = (int) Math.floor(a.wx(x, y)), bz = (int) Math.floor(a.wz(x, y));
        var mc = Minecraft.getInstance();
        var l = new ArrayList<Eintrag>();
        var wp = eigenerWegpunkt(x, y);
        if (wp != null) l.add(new Eintrag(T.t("menue.wp_loeschen", wp.name()), () -> Waypoints.remove(wp.name())));
        l.add(new Eintrag(T.t("menue.wp_setzen"), () -> wegpunkt(bx, bz)));
        if (manager != null) l.add(new Eintrag(T.t("menue.verwalten"), manager));
        l.add(new Eintrag(T.t("menue.kopieren"), () -> kopieren(bx, bz)));
        l.add(new Eintrag(T.t("menue.chat"), () -> {
            String text = bx + " " + hoehe(bx, bz) + " " + bz;
            mc.setScreenAndShow(new ChatScreen(text, false));
        }));
        l.add(new Eintrag(T.t("menue.zentrieren"), () -> {
            folgen = false;
            a.cx = bx + 0.5;
            a.cz = bz + 0.5;
        }));
        if (mc.player != null) l.add(new Eintrag(T.t("menue.entfernung"), () -> Notify.show(T.t("welt.entfernung"),
            T.t("welt.bloecke", Math.round(Math.hypot(bx + 0.5 - mc.player.getX(), bz + 0.5 - mc.player.getZ()))))));
        var m = new Menue(0, 0, bx, bz, l);
        // im Bild halten
        int mxp = (int) Math.min(x, w - Menue.BREITE - 2), myp = (int) Math.min(y, h - m.hoehe() - 16);
        menue = new Menue(Math.max(2, mxp), Math.max(2, myp), bx, bz, l);
    }

    private Waypoints.Waypoint eigenerWegpunkt(double x, double y) {
        String dim = Waypoints.dimension();
        for (var wp : Waypoints.all())
            if (wp.addon().equals(Karte.ID) && wp.dimension().equals(dim)
                && Math.abs(a.sx(wp.pos().getX() + 0.5, wp.pos().getZ() + 0.5) - x) <= 5
                && Math.abs(a.sy(wp.pos().getX() + 0.5, wp.pos().getZ() + 0.5) - y) <= 5)
                return wp;
        return null;
    }

    // ───── Knöpfe und Infoleiste ─────
    private void oberflaeche(GuiGraphicsExtractor g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        knoepfe.clear();

        // oben: Kartentypen | Höhlenschicht | Filter | Gitter – in Zeilen, mittig
        var oben = new ArrayList<Knopf>();
        for (var t : Kartendaten.Typ.values())
            oben.add(knopf(t.text(), T.t("knopf.typ", t.text()), typ == t, () -> setzeTyp(t)));
        oben.add(null);
        for (var art : Ansicht.Art.values())
            oben.add(knopf(art.text(), T.t("knopf.anzeigen", art.text()), filter.zeigt(art), () -> filter.setze(art, !filter.zeigt(art))));
        oben.add(knopf(T.t("knopf.koepfe"), T.t("knopf.koepfe.info"), filter.koepfe, () -> filter.setzeKoepfe(!filter.koepfe)));
        oben.add(knopf(T.t("knopf.wegpunkte"), T.t("knopf.wegpunkte.info"), filter.wegpunkte, () -> filter.setzeWegpunkte(!filter.wegpunkte)));
        oben.add(knopf(T.t("knopf.gitter"), T.t("knopf.gitter.info"), gitter, () -> cfg.set("fs_gitter", gitter = !gitter)));
        if (SeedKarte.seed(SeedKarte.link()) != null)
            oben.add(knopf(T.t("knopf.seed"), T.t("knopf.seed.info", SeedKarte.status()), seedAn, () -> cfg.set("fs_seed", seedAn = !seedAn)));
        reihen(oben, 4);

        // links: Folgen, Zoom
        int ly = h / 2 - 40;
        for (var k : List.of(
            knopf("⌖", T.t("knopf.folgen"), folgen, () -> folgen = !folgen),
            knopf("+", T.t("knopf.rein"), false, () -> zoom(1.5, w / 2.0, h / 2.0)),
            knopf("−", T.t("knopf.raus"), false, () -> zoom(1 / 1.5, w / 2.0, h / 2.0)))) {
            knoepfe.add(new Knopf(4, ly, 20, 18, k.text, k.tipp, k.aktiv, k.aktion));
            ly += 22;
        }

        zeichneSlider(g, mx, my);

        Knopf hover = null;
        for (var k : knoepfe) {
            boolean ueber = k.trifft(mx, my);
            if (ueber) hover = k;
            Draw.roundRect(g, k.x, k.y, k.w, k.h, 3, k.aktiv ? 0xE0C05090 : ueber ? 0xE0404048 : 0xD0202026);
            Draw.text(g, k.text, k.x + (k.w - font.width(k.text)) / 2, k.y + (k.h - 8) / 2);
        }

        // unten: eigene Position und Position unter der Maus
        var mc = Minecraft.getInstance();
        Ansicht.rechteck(g, 0, h - 14, w, 14, 0xC0000000);
        var p = mc.player;
        if (p != null) {
            var b = p.blockPosition();
            Draw.text(g, Component.literal(T.t("welt.du", b.getX() + ", " + b.getY() + ", " + b.getZ()) + " · ").append(Minimap.biom(b)), 6, h - 11);
        }
        int bx = (int) Math.floor(a.wx(mx, my)), bz = (int) Math.floor(a.wz(mx, my));
        Component maus = Component.literal(T.t("welt.maus", bx + ", " + bz));
        if (mc.level != null && mc.level.hasChunk(bx >> 4, bz >> 4)) {
            int by = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            maus = Component.literal(T.t("welt.maus", bx + ", " + by + ", " + bz) + " · ").append(Minimap.biom(new BlockPos(bx, by, bz)));
        }
        Draw.text(g, maus, w - font.width(maus) - 6, h - 11);

        if (hover != null) Draw.tooltip(g, List.of(Component.literal(hover.tipp)), mx, my);
    }

    private Knopf knopf(String text, String tipp, boolean aktiv, Runnable aktion) {
        return new Knopf(0, 0, Minecraft.getInstance().font.width(text) + 10, 16, text, tipp, aktiv, aktion);
    }

    // Knöpfe zeilenweise mittig anordnen; null = kleine Lücke
    private void reihen(List<Knopf> liste, int y) {
        var zeile = new ArrayList<Knopf>();
        int breite = 0;
        for (int i = 0; i <= liste.size(); i++) {
            Knopf k = i < liste.size() ? liste.get(i) : null;
            int kw = k == null ? 8 : k.w + 2;
            if (i == liste.size() || breite + kw > w - 8) {
                int x = (w - breite) / 2;
                for (var z : zeile) {
                    if (z != null) knoepfe.add(new Knopf(x, y, z.w, z.h, z.text, z.tipp, z.aktiv, z.aktion));
                    x += z == null ? 8 : z.w + 2;
                }
                zeile.clear();
                breite = 0;
                y += 18;
            }
            if (i < liste.size()) {
                zeile.add(k);
                breite += kw;
            }
        }
    }

    private void setzeTyp(Kartendaten.Typ t) {
        typ = t;
        if (t != Kartendaten.Typ.HOEHLE) oberTyp = t;
        cfg.set("fs_typ", t.name());
    }

    private void zoom(double f, double x, double y) {
        double wx = a.wx(x, y), wz = a.wz(x, y);   // Punkt unter der Maus bleibt stehen
        a.scale = Math.clamp(a.scale * f, 0.125, 8);
        a.cx = wx - (x - a.ox) / a.scale;
        a.cz = wz - (y - a.oy) / a.scale;
        cfg.set("fs_zoom", a.scale);
    }

    // Maustasten wie SDL (Minecraft 26.3): links = 1, rechts = 3
    private boolean klick(double x, double y, int button) {
        boolean links = button == InputConstants.MOUSE_BUTTON_LEFT, rechts = button == InputConstants.MOUSE_BUTTON_RIGHT;
        if (menue != null) {                       // jeder Klick schließt das Menü; auf einem Eintrag führt er ihn aus
            var m = menue;
            menue = null;
            int i = m.treffer(x, y);
            if (i >= 0 && links) m.eintraege.get(i).aktion.run();
            if (i >= 0 || !rechts) return true;
        }
        schieben = links && aufSlider(x, y);
        if (schieben) {
            sliderAuf(y);
            return true;
        }
        if (links) {
            for (var k : knoepfe)
                if (k.trifft(x, y)) {
                    k.aktion.run();
                    return true;
                }
            return false;                          // Karte: Ziehen übernimmt drag
        }
        if (!rechts) return false;
        oeffneMenue(x, y);
        return true;
    }

    private boolean taste(int key) {
        var mc = Minecraft.getInstance();
        var p = mc.player;
        int bx = (int) Math.floor(a.wx(mausX, mausY)), bz = (int) Math.floor(a.wz(mausX, mausY));
        switch (key) {
            case InputConstants.KEY_B -> wegpunkt(bx, bz);
            case InputConstants.KEY_F -> folgen = !folgen;
            case InputConstants.KEY_C -> kopieren(bx, bz);
            case InputConstants.KEY_UP -> verschieben(0, -16);
            case InputConstants.KEY_DOWN -> verschieben(0, 16);
            case InputConstants.KEY_LEFT -> verschieben(-16, 0);
            case InputConstants.KEY_RIGHT -> verschieben(16, 0);
            case InputConstants.KEY_SPACE -> folgen = p != null;
            default -> {
                return false;
            }
        }
        return true;
    }

    private void verschieben(int dx, int dz) {
        folgen = false;
        a.cx += dx;
        a.cz += dz;
    }

    private static void kopieren(int bx, int bz) {
        String text = bx + " " + hoehe(bx, bz) + " " + bz;
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        Notify.show(T.t("name"), T.t("welt.kopiert", text));
    }

    private void wegpunkt(int bx, int bz) {
        String name = Wegpunkte.freierName(T.t("wp.standard"));
        Waypoints.add(name, new BlockPos(bx, hoehe(bx, bz), bz), 0xFFFF79C6);
        Notify.show(T.t("wp.titel"), T.t("wp.bei", name, bx + ", " + bz));
    }

    private static int hoehe(int bx, int bz) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.hasChunk(bx >> 4, bz >> 4)) return mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        return mc.player == null ? 64 : mc.player.getBlockY();
    }

    List<client.api.Setting> options() {
        return List.of(
            client.api.Setting.heading(T.t("opt.welt")),
            client.api.Setting.button(T.t("opt.welt"), T.t("opt.welt.info"), T.t("oeffnen"), this::oeffnen),
            client.api.Setting.info(() -> T.t("opt.kacheln", daten.geladen())),
            client.api.Setting.heading(T.t("opt.chunkbase")),
            client.api.Setting.text(T.t("opt.link"), T.t("opt.link.info"),
                512, SeedKarte::link, v -> {
                    SeedKarte.setzeLink(v);
                    seedKarte.leeren();
                }),
            client.api.Setting.info(SeedKarte::status),
            client.api.Setting.toggle(T.t("opt.seed"), T.t("opt.seed.info"), () -> seedAn,
                v -> cfg.set("fs_seed", seedAn = v)),
            client.api.Setting.choice(T.t("opt.seedmodus"), T.t("opt.seedmodus.info"),
                List.of(SeedKarte.Modus.values()), () -> seedModus,
                v -> cfg.set("seed_modus", (seedModus = v).name()), SeedKarte.Modus::text)
                .visibleIf(() -> seedAn));
    }
}
