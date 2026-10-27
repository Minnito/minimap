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
    private boolean anzeigenOffen;   // Liste „Anzeigen“ (Kreaturen, Köpfe, Wegpunkte, Gitter, Seed) aufgeklappt
    Runnable manager;      // öffnet den Wegpunkt-Manager (von Karte gesetzt)

    private record Knopf(int x, int y, int w, int h, String text, String tipp, boolean aktiv, Runnable aktion) {
        boolean trifft(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Eintrag(String text, Runnable aktion) {}

    /** Rechtsklick-Menü an (x, y) für den Block (bx, bz). */
    private record Menue(int x, int y, int bx, int bz, List<Eintrag> eintraege, int breite) {
        static final int ZEILE = 16, KOPF = 20;

        int hoehe() {
            return eintraege.size() * ZEILE + KOPF + 4;
        }

        int treffer(double mx, double my) {
            if (mx < x || mx >= x + breite || my < y + KOPF) return -1;
            int i = (int) ((my - y - KOPF) / ZEILE);
            return i < eintraege.size() ? i : -1;
        }
    }

    Vollbild(AddonConfig cfg, Kartendaten daten, Ansicht.Filter filter) {
        this.cfg = cfg;
        this.daten = daten;
        this.filter = filter;
        typ = Karte.wahl(cfg, "fs_typ", Kartendaten.Typ.TAG);
        oberTyp = typ == Kartendaten.Typ.HOEHLE ? Kartendaten.Typ.TAG : typ;
        gitter = cfg.bool("fs_gitter", false);
        seedAn = cfg.bool("fs_seed", true);
        seedModus = Karte.wahl(cfg, "seed_modus", SeedKarte.Modus.GELAENDE);
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
                client.api.Profiler.section("minimap.weltkarte", () -> zeichne(g, w, h, mouseX, mouseY));
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
    private int sy0() { return 48; }
    private int sy1() { return h - 46; }

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
        Stil.panel(g, x - 2, y0 - 8, 18, y1 - y0 + 16);
        Ansicht.rechteck(g, x + 6, y0, 2, y1 - y0, Stil.LINIE);
        for (int y = Math.ceilDiv(minY(), 64) * 64; y <= maxY(); y += 64)            // Skala alle 64 Blöcke
            Ansicht.rechteck(g, x + 4, yVon(maxY() - y + 1), 6, 1, Stil.LINIE);
        var p = Minecraft.getInstance().player;
        if (p != null) Ansicht.rechteck(g, x + 1, yVon(maxY() - p.getBlockY() + 1), 12, 1, Stil.TEXT);   // deine Höhe
        if (knopf.target() != index()) knopf.to(index());
        int ky = yVon(knopf.value());
        if (schieben || aufSlider(mx, my)) Draw.roundRect(g, x - 1, ky - 5, 16, 10, 4, Stil.TEXT);   // Hover: heller Ring
        Draw.roundRect(g, x, ky - 4, 14, 8, 3, Stil.akzent());
        String text = index() == 0 ? T.t("welt.oberflaeche") : "Y " + hoehe;
        int tw = Stil.breite(text);
        Draw.roundRect(g, x - tw - 16, ky - 7, tw + 10, 14, 3, Stil.FLAECHE);
        Stil.text(g, text, x - tw - 11, ky - 3, Stil.TEXT);
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
        Draw.dropShadow(g, m.x, m.y, m.breite, m.hoehe(), Stil.RADIUS, 0, 2, 6, 0x60000000);
        Draw.roundRect(g, m.x, m.y, m.breite, m.hoehe(), Stil.RADIUS, Stil.RAHMEN);
        Stil.text(g, titel(m.bx, m.bz), m.x + 10, m.y + 6, Stil.LEISE);
        Ansicht.rechteck(g, m.x + 6, m.y + Menue.KOPF - 2, m.breite - 12, 1, Stil.LINIE);
        int hit = m.treffer(mx, my);
        for (int i = 0; i < m.eintraege.size(); i++) {
            int y = m.y + Menue.KOPF + i * Menue.ZEILE;
            if (i == hit) {
                Draw.roundRect(g, m.x + 3, y, m.breite - 6, Menue.ZEILE, 3, Stil.ERHOBEN);
                Ansicht.rechteck(g, m.x + 3, y + 3, 2, Menue.ZEILE - 6, Stil.akzent());
            }
            Stil.text(g, m.eintraege.get(i).text, m.x + 10, y + 4, Stil.TEXT);
        }
    }

    private static String titel(int bx, int bz) {
        return bx + ", " + hoehe(bx, bz) + ", " + bz;
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
        // Portale verbinden: Nether-Block = Oberwelt-Block / 8
        String dim = Waypoints.dimension();
        if (Wegpunkte.OBERWELT.equals(dim))
            l.add(new Eintrag(T.t("menue.nether"), () -> kopieren(Math.floorDiv(bx, 8) + " " + hoehe(bx, bz) + " " + Math.floorDiv(bz, 8))));
        else if (Wegpunkte.NETHER.equals(dim))
            l.add(new Eintrag(T.t("menue.oberwelt"), () -> kopieren(bx * 8 + " " + hoehe(bx, bz) + " " + bz * 8)));
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
        int breite = Stil.breite(titel(bx, bz)) + 20;
        for (var e : l) breite = Math.max(breite, Stil.breite(e.text) + 20);
        var m = new Menue(0, 0, bx, bz, l, breite);
        // im Bild halten
        int mxp = (int) Math.min(x, w - breite - 2), myp = (int) Math.min(y, h - m.hoehe() - 2);
        menue = new Menue(Math.max(2, mxp), Math.max(2, myp), bx, bz, l, breite);
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

    // ───── Bedienelemente: Kartentyp oben links, „Anzeigen“ oben rechts, Folgen/Zoom links, Positionen unten ─────
    private static final int RAND = 8, HOCH = 20;

    private void oberflaeche(GuiGraphicsExtractor g, int mx, int my) {
        knoepfe.clear();

        // Kartentyp: ein Umschalter, der gewählte Teil in der Akzentfarbe
        int gesamt = 4;
        for (var t : Kartendaten.Typ.values()) gesamt += Stil.breite(t.text()) + 16;
        Stil.panel(g, RAND, RAND, gesamt, HOCH + 4);
        int x = RAND + 2;
        for (var t : Kartendaten.Typ.values()) {
            var k = neu(x, RAND + 2, Stil.breite(t.text()) + 16, HOCH, t.text(), T.t("knopf.typ", t.text()), typ == t, () -> setzeTyp(t));
            boolean ueber = k.trifft(mx, my);
            if (k.aktiv) Draw.roundRect(g, k.x, k.y, k.w, k.h, 3, Stil.akzent());
            else if (ueber) Draw.roundRect(g, k.x, k.y, k.w, k.h, 3, Stil.ERHOBEN);
            Stil.text(g, k.text, k.x + 8, k.y + 6, k.aktiv ? Stil.aufAkzent() : ueber ? Stil.TEXT : Stil.LEISE);
            x += k.w;
        }

        // „Anzeigen“: Ebenen hinter einem Knopf statt einer Reihe aus neun Schaltern
        String titel = T.t("knopf.anzeigen.titel");
        int aw = Stil.breite(titel) + 16, ax = sx0() + 16 - aw;   // rechts bündig mit der Slider-Leiste
        var ak = neu(ax, RAND, aw, HOCH + 4, titel, T.t("knopf.anzeigen.info"), anzeigenOffen, () -> anzeigenOffen = !anzeigenOffen);
        Draw.roundRect(g, ak.x, ak.y, ak.w, ak.h, Stil.RADIUS, anzeigenOffen || ak.trifft(mx, my) ? Stil.ERHOBEN : Stil.FLAECHE);
        Stil.text(g, titel, ak.x + 8, ak.y + 8, Stil.TEXT);

        // links: Folgen, hinein, hinaus – gezeichnete Symbole statt Schriftzeichen
        int lw = 22, ly = h / 2 - lw * 3 / 2;
        Stil.panel(g, RAND, ly, lw, lw * 3);
        String[] tipps = {T.t("knopf.folgen"), T.t("knopf.rein"), T.t("knopf.raus")};
        Runnable[] aktionen = {() -> folgen = !folgen, () -> zoom(1.5, w / 2.0, h / 2.0), () -> zoom(1 / 1.5, w / 2.0, h / 2.0)};
        for (int i = 0; i < 3; i++) {
            var k = neu(RAND, ly + i * lw, lw, lw, "", tipps[i], i == 0 && folgen, aktionen[i]);
            boolean ueber = k.trifft(mx, my);
            if (ueber) Draw.roundRect(g, k.x + 2, k.y + 2, lw - 4, lw - 4, 3, Stil.ERHOBEN);
            if (i > 0) Ansicht.rechteck(g, k.x + 5, k.y, lw - 10, 1, Stil.LINIE);
            int c = k.aktiv ? Stil.akzent() : Stil.TEXT, cx = k.x + lw / 2, cy = k.y + lw / 2;
            if (i == 0) {                                   // Folgen: Ring mit Punkt
                Draw.circle(g, cx, cy, 5, c);
                Draw.circle(g, cx, cy, 3, ueber ? Stil.ERHOBEN : Stil.RAHMEN);
                Draw.circle(g, cx, cy, 1, c);
            } else {
                Ansicht.rechteck(g, cx - 5, cy - 1, 10, 2, c);
                if (i == 1) Ansicht.rechteck(g, cx - 1, cy - 5, 2, 10, c);
            }
        }

        zeichneSlider(g, mx, my);

        // unten: deine Position links, die unter der Maus rechts
        var mc = Minecraft.getInstance();
        int fy = h - RAND - 18;
        var p = mc.player;
        if (p != null) {
            var b = p.blockPosition();
            fuss(g, RAND, fy, T.t("welt.du"), b.getX() + ", " + b.getY() + ", " + b.getZ(), Minimap.biom(b).getString(), false);
        }
        int bx = (int) Math.floor(a.wx(mx, my)), bz = (int) Math.floor(a.wz(mx, my));
        String ort = bx + ", " + bz, biom = "";
        if (mc.level != null && mc.level.hasChunk(bx >> 4, bz >> 4)) {
            int by = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            ort = bx + ", " + by + ", " + bz;
            biom = Minimap.biom(new BlockPos(bx, by, bz)).getString();
        }
        fuss(g, w - RAND, fy, T.t("welt.maus"), ort, biom, true);

        if (anzeigenOffen) anzeigen(g, ax + aw, RAND + HOCH + 8, mx, my);

        Knopf hover = null;
        for (var k : knoepfe) if (k.trifft(mx, my)) hover = k;
        if (hover != null && hover.tipp != null) tipp(g, hover.tipp, mx, my);
    }

    private Knopf neu(int x, int y, int w, int h, String text, String tipp, boolean aktiv, Runnable aktion) {
        var k = new Knopf(x, y, w, h, text, tipp, aktiv, aktion);
        knoepfe.add(k);
        return k;
    }

    // Aufgeklappte Liste „Anzeigen“, rechtsbündig unter dem Knopf; Kästchen gefüllt = an (nicht nur über die Farbe)
    private void anzeigen(GuiGraphicsExtractor g, int rechts, int y, int mx, int my) {
        record Zeile(String text, boolean an, Runnable aktion) {}
        var l = new ArrayList<Zeile>();
        for (var art : Ansicht.Art.values()) l.add(new Zeile(art.text(), filter.zeigt(art), () -> filter.setze(art, !filter.zeigt(art))));
        l.add(null);
        l.add(new Zeile(T.t("knopf.koepfe"), filter.koepfe, () -> filter.setzeKoepfe(!filter.koepfe)));
        l.add(new Zeile(T.t("knopf.wegpunkte"), filter.wegpunkte, () -> filter.setzeWegpunkte(!filter.wegpunkte)));
        l.add(new Zeile(T.t("knopf.gitter"), gitter, () -> cfg.set("fs_gitter", gitter = !gitter)));
        if (SeedKarte.aktiverSeed() != null) l.add(new Zeile(T.t("knopf.seed"), seedAn, () -> cfg.set("fs_seed", seedAn = !seedAn)));
        int bw = 0, hoch = 8;
        for (var z : l) {
            if (z != null) bw = Math.max(bw, Stil.breite(z.text));
            hoch += z == null ? 7 : 16;
        }
        bw += 36;
        int x = rechts - bw;
        Draw.dropShadow(g, x, y, bw, hoch, Stil.RADIUS, 0, 2, 6, 0x60000000);
        Draw.roundRect(g, x, y, bw, hoch, Stil.RADIUS, Stil.RAHMEN);
        int zy = y + 4;
        for (var z : l) {
            if (z == null) {
                Ansicht.rechteck(g, x + 8, zy + 3, bw - 16, 1, Stil.LINIE);
                zy += 7;
                continue;
            }
            var k = neu(x + 3, zy, bw - 6, 16, z.text, null, z.an, z.aktion);
            if (k.trifft(mx, my)) Draw.roundRect(g, k.x, k.y, k.w, k.h, 3, Stil.ERHOBEN);
            if (z.an) {
                Draw.roundRect(g, x + 10, zy + 3, 10, 10, 2, Stil.akzent());
                Ansicht.rechteck(g, x + 13, zy + 6, 4, 4, Stil.aufAkzent());
            } else {
                Draw.roundRect(g, x + 10, zy + 3, 10, 10, 2, Stil.LINIE);
                Draw.roundRect(g, x + 11, zy + 4, 8, 8, 2, Stil.RAHMEN);
            }
            Stil.text(g, z.text, x + 26, zy + 4, z.an ? Stil.TEXT : Stil.LEISE);
            zy += 16;
        }
    }

    // Feld unten: Bezeichnung leise, Koordinaten hell, Biom leise; rechts = an der rechten Kante ausgerichtet
    private static void fuss(GuiGraphicsExtractor g, int x, int y, String name, String ort, String biom, boolean rechts) {
        int b = Stil.breite(name) + 6 + Stil.breite(ort) + (biom.isEmpty() ? 0 : 8 + Stil.breite(biom)) + 16;
        if (rechts) x -= b;
        Stil.panel(g, x, y, b, 18);
        int tx = x + 8;
        tx += Stil.text(g, name, tx, y + 5, Stil.LEISE) + 6;
        tx += Stil.text(g, ort, tx, y + 5, Stil.TEXT) + 8;
        if (!biom.isEmpty()) Stil.text(g, biom, tx, y + 5, Stil.LEISE);
    }

    private void tipp(GuiGraphicsExtractor g, String text, int mx, int my) {
        int tw = Stil.breite(text) + 12;
        int x = Math.min(mx + 10, w - tw - 2), y = Math.min(my + 12, h - 18);
        Draw.roundRect(g, x, y, tw, 16, 3, Stil.RAHMEN);
        Stil.text(g, text, x + 6, y + 4, Stil.TEXT);
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
            if (anzeigenOffen) {                   // Klick neben die Liste schließt sie
                anzeigenOffen = false;
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
        kopieren(bx + " " + hoehe(bx, bz) + " " + bz);
    }

    private static void kopieren(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        Notify.show(T.t("name"), T.t("welt.kopiert", text));
    }

    private void wegpunkt(int bx, int bz) {
        String name = Wegpunkte.freierName(T.t("wp.standard"));
        Waypoints.add(name, new BlockPos(bx, hoehe(bx, bz), bz), 0xFFFF79C6);
        Notify.show(T.t("wp.titel"), T.t("wp.bei", name, bx + ", " + bz));
    }

    /** Oberfläche an (bx, bz), wenn der Chunk geladen ist, sonst deine Höhe. */
    static int hoehe(int bx, int bz) {
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
