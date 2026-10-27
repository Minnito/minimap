package karte;

import client.api.AddonConfig;
import client.api.Draw;
import client.api.HudElement;
import client.api.Notify;
import client.api.Page;
import client.api.Setting;
import client.api.Waypoints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import client.api.Friends;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

// Wegpunkte wie bei Xaero: in der Welt als farbiges Quadrat mit Symbol und Name, beim Hinschauen mit Entfernung;
// dazu ein Manager (Liste, Neu, Bearbeiten, Löschen). Gespeichert über die Client-API (Waypoints), Symbol und
// Sichtbarkeit je Wegpunkt in der eigenen Config. Fremde Wegpunkte (anderer Addons) werden gezeigt, aber nicht bearbeitet.
final class Wegpunkte {
    static final String ADDON = Karte.ID;
    private static final int HINSCHAUEN_PX = 14;          // so nah am Fadenkreuz gilt ein Wegpunkt als angeschaut
    private static final float GROESSE = 1.5f;              // Bildschirmpixel je Schriftpixel (Xaero-Größe ≈ 1–2)
    private static final List<String> ENTFERNUNG = List.of("hin", "immer", "nie");   // Schlüssel wp.entf.<…>
    private static final int[] FARBEN = {0xFFFF5555, 0xFF55FF55, 0xFF5599FF, 0xFFFFD040, 0xFFFF79C6, 0xFF40E0E0, 0xFFFFFFFF};

    private static final int TOD_FARBE = 0xFFFF5555;
    private static final double TOD_NAH = 4;                // so nah (Blöcke) gilt der Todespunkt als erreicht

    private final AddonConfig cfg;
    private boolean inWelt, todAn, todLoeschen;
    private boolean totWartet;   // gestorben, noch nicht wiederbelebt: so lange nicht „erreicht“ – man steht ja noch dort
    private int entfernung;

    Wegpunkte(AddonConfig cfg) {
        this.cfg = cfg;
        inWelt = cfg.bool("wp_welt", true);
        entfernung = Math.clamp(cfg.number("wp_entfernung", 0), 0, 2);
        todAn = cfg.bool("wp_tod", true);
        todLoeschen = cfg.bool("wp_tod_loeschen", true);
    }

    // ───── Todespunkt: erkannt am Namen in der Spielsprache – im Manager umbenannt wird er ein normaler Wegpunkt ─────
    // ponytail: nach einem Sprachwechsel bleibt ein alter Todespunkt als normaler Wegpunkt stehen

    static Waypoints.Waypoint todespunkt() {
        String n = T.t("wp.tod");
        for (var w : Waypoints.all()) if (eigen(w) && w.name().equals(n)) return w;
        return null;
    }

    void gestorben() {
        var p = Minecraft.getInstance().player;
        if (todAn && p != null) Waypoints.add(T.t("wp.tod"), p.blockPosition(), TOD_FARBE);   // ersetzt den letzten
        totWartet = true;
    }

    /** Wiederbelebt, Welt betreten oder verlassen: ab jetzt zählt Hingehen wieder. */
    void lebt() {
        totWartet = false;
    }

    void ankunft() {
        var p = Minecraft.getInstance().player;
        // isAlive allein reicht nicht: direkt nach dem Tod meldet der Client die Figur teils noch kurz als lebendig
        if (!todLoeschen || totWartet || p == null || !p.isAlive()) return;
        var w = todespunkt();
        if (w != null && w.dimension().equals(Waypoints.dimension()) && p.blockPosition().closerThan(w.pos(), TOD_NAH))
            Waypoints.remove(w.name());
    }

    // ───── Symbol und Sichtbarkeit je Wegpunkt ─────
    private static String schluessel(Waypoints.Waypoint w) {
        return w.dimension() + "|" + w.name();
    }

    /** Symbol im Quadrat: eigenes (1–2 Zeichen) oder der Anfangsbuchstabe. */
    static String symbol(Waypoints.Waypoint w) {
        String s = AddonConfig.of(ADDON).text("wp_sym." + schluessel(w), "");
        if (!s.isBlank()) return s;
        return w.name().isEmpty() ? "?" : w.name().substring(0, 1).toUpperCase(Locale.ROOT);
    }

    static boolean sichtbar(Waypoints.Waypoint w) {
        return AddonConfig.of(ADDON).bool("wp_an." + schluessel(w), true);
    }

    static boolean eigen(Waypoints.Waypoint w) {
        return w.addon().equals(ADDON);
    }

    // Sichtbare Wegpunkte dieser Dimension, je Tick neu (Karte.tick): all() kopiert und die Config-Abfragen kosten jedes Bild
    private static List<Waypoints.Waypoint> sichtbarHier = List.of();
    static final String OBERWELT = "minecraft:overworld", NETHER = "minecraft:the_nether", ENDE = "minecraft:the_end";

    // Jede Dimension hat ihre eigenen Wegpunkte: ein Oberwelt-Wegpunkt erscheint nur in der Oberwelt
    static void aktualisieren() {
        sichtbarHier = hier().stream().filter(Wegpunkte::sichtbar).toList();
    }

    // ───── Teilen: als Text im Chat oder an Freunde, und Koordinaten aus Nachrichten als Wegpunkt anbieten ─────
    private static final Pattern MARKE = Pattern.compile("\\[MiniMap] (.{1,32}?) @ (-?\\d{1,8}) (-?\\d{1,4}) (-?\\d{1,8})(?: \\(([a-z0-9_.:/-]+)\\))?");
    private static final Pattern BENANNT = Pattern.compile("(?i)(?<![a-z])x\\s*[:=]?\\s*(-?\\d{1,8})[,;\\s]+(?:y\\s*[:=]?\\s*(-?\\d{1,4})[,;\\s]+)?z\\s*[:=]?\\s*(-?\\d{1,8})");
    // drei Zahlen hintereinander – aber nicht Teil einer längeren Zahlenreihe, keiner Uhrzeit (12:30) und keines Datums (27.10.2026)
    private static final Pattern DREI = Pattern.compile("(?<![\\d.:]|\\d[ ,/]{1,3})(-?\\d{1,8})[ ,/]{1,3}(-?\\d{1,4})[ ,/]{1,3}(-?\\d{1,8})(?![\\d.:]|[ ,/]{1,3}-?\\d)");

    /** Koordinaten aus einer Nachricht; y null = nicht genannt, dim null = nicht genannt. */
    record Fund(String name, int x, Integer y, int z, String dim) {}

    /** Erste Koordinatenangabe im Text oder null – rein, darum im Selbsttest geprüft. */
    static Fund koordinaten(String text) {
        var m = MARKE.matcher(text);
        if (m.find()) return new Fund(m.group(1).trim(), zahl(m.group(2)), zahl(m.group(3)), zahl(m.group(4)), m.group(5));
        m = BENANNT.matcher(text);
        if (m.find()) return new Fund(null, zahl(m.group(1)), m.group(2) == null ? null : zahl(m.group(2)), zahl(m.group(3)), null);
        m = DREI.matcher(text);
        while (m.find()) {
            int x = zahl(m.group(1)), y = zahl(m.group(2)), z = zahl(m.group(3));
            // „3 5 7“ ist eher eine Aufzählung als ein Ort: mindestens eine Zahl zweistellig, Y in der Welthöhe
            if (y >= -64 && y <= 320 && Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) >= 10) return new Fund(null, x, y, z, null);
        }
        return null;
    }

    private static int zahl(String s) {
        return Integer.parseInt(s);
    }

    /** Text zum Teilen, den koordinaten() wieder liest. */
    static String alsText(Waypoints.Waypoint w) {
        return "[MiniMap] " + w.name() + " @ " + w.pos().getX() + " " + w.pos().getY() + " " + w.pos().getZ() + " (" + w.dimension() + ")";
    }

    private static String zuletztGesendet = "";
    private static long gesendetUm, angebotenUm;
    private static String angeboten = "";

    /** Eigene Chat-Eingabe merken – kommt gleich als Chatnachricht zurück und soll dann kein Angebot auslösen. */
    static void gesendet(String text) {
        zuletztGesendet = text;
        gesendetUm = System.currentTimeMillis();
    }

    /** Nachricht (Chat oder Freunde) mit Koordinaten: als Wegpunkt anbieten. */
    static void anbieten(String text) {
        long jetzt = System.currentTimeMillis();
        if (Minecraft.getInstance().player == null || !AddonConfig.of(ADDON).bool("wp_chat", true)) return;
        if (jetzt - gesendetUm < 5000 && !zuletztGesendet.isBlank() && text.contains(zuletztGesendet)) return;
        var f = koordinaten(text);
        if (f == null || (f.dim() != null && !f.dim().equals(Waypoints.dimension()))) return;   // andere Dimension: add() setzt nur hier
        String ort = f.x() + ", " + (f.y() == null ? "" : f.y() + ", ") + f.z();
        if (ort.equals(angeboten) && jetzt - angebotenUm < 10_000) return;                     // Server schicken manches doppelt
        angeboten = ort;
        angebotenUm = jetzt;
        Notify.action(T.t("chat.titel"), T.t("chat.text", ort), new Notify.Button(T.t("chat.knopf"), () -> {
            String name = freierName(f.name() == null || f.name().isBlank() ? T.t("chat.name") : f.name());
            int y = f.y() != null ? f.y() : Vollbild.hoehe(f.x(), f.z());
            Waypoints.add(name, new BlockPos(f.x(), y, f.z()), FARBEN[Waypoints.all().size() % FARBEN.length]);
            Notify.show(T.t("wp.titel"), T.t("wp.bei", name, f.x() + ", " + f.z()));
        }));
    }

    static List<Waypoints.Waypoint> sichtbarHier() {
        return sichtbarHier;
    }

    static List<Waypoints.Waypoint> hier() {
        return nurIn(Waypoints.all(), Waypoints.dimension());
    }

    /** Nur die Wegpunkte der Dimension dim (null = außerhalb einer Welt: keine) – rein, darum im Selbsttest geprüft. */
    static List<Waypoints.Waypoint> nurIn(List<Waypoints.Waypoint> alle, String dim) {
        return alle.stream().filter(w -> w.dimension().equals(dim)).toList();
    }

    static String freierName(String basis) {
        var namen = new HashSet<String>();
        for (var w : Waypoints.all()) if (eigen(w)) namen.add(w.name());
        if (!namen.contains(basis)) return basis;
        int n = 2;
        while (namen.contains(basis + " " + n)) n++;
        return basis + " " + n;
    }

    static String entfernungText(double d) {
        return String.format(T.locale(), d < 1000 ? "%.1fm" : "%.0fm", d);   // Deutsch 458,9m – Englisch 458.9m
    }

    private static double abstand(Waypoints.Waypoint w) {
        var p = Minecraft.getInstance().player;
        return p == null ? 0 : Math.sqrt(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(w.pos())));
    }

    // ───── In der Welt (HUD-Element über den ganzen Bildschirm, Draw.project rechnet absolut) ─────
    HudElement hud() {
        return new HudElement(Karte.ID + ".wegpunkte", T.t("hud.wegpunkte"), 0, 0, 0, 0, 1, 1, this::zeichne).visibleIf(() -> inWelt);
    }

    private void zeichne(GuiGraphicsExtractor g, int x0, int y0) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var font = mc.font;
        int mx = mc.getWindow().getGuiScaledWidth() / 2, my = mc.getWindow().getGuiScaledHeight() / 2;
        // Wie Xaero: feste Größe in echten Bildschirmpixeln – unabhängig von GUI-Skalierung und Entfernung
        float k = GROESSE / (float) mc.getWindow().getGuiScale();
        // weit entfernte zuerst, damit nahe obenauf liegen
        var liste = new ArrayList<>(sichtbarHier);
        liste.sort(Comparator.comparingDouble(Wegpunkte::abstand).reversed());
        for (var w : liste) {
            var s = Draw.project(new Vec3(w.pos().getX() + 0.5, w.pos().getY() + 1.2, w.pos().getZ() + 0.5));
            if (s == null) continue;                                   // hinter der Kamera
            int x = Math.round(s.x), y = Math.round(s.y);
            boolean angeschaut = Math.abs(x - mx) <= HINSCHAUEN_PX && Math.abs(y - my) <= HINSCHAUEN_PX;
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(k, k);
            symbolKasten(g, w, 0, 0);
            String name = w.name();
            int nw = font.width(name);
            Ansicht.rechteck(g, -nw / 2 - 2, 7, nw + 4, 10, 0x80000000);
            Draw.text(g, name, -nw / 2, 8);
            if (entfernung == 1 || (entfernung == 0 && angeschaut)) {
                String d = entfernungText(abstand(w));
                int dw = font.width(d);
                Ansicht.rechteck(g, -dw / 2 - 2, 18, dw + 4, 11, 0xA0000000);
                Draw.text(g, d, -dw / 2, 20);
            }
            g.pose().popMatrix();
        }
    }

    /** Farbiges Quadrat mit Symbol, mittig auf (x, y) – auch für die Weltkarte. */
    static void symbolKasten(GuiGraphicsExtractor g, Waypoints.Waypoint w, int x, int y) {
        var font = Minecraft.getInstance().font;
        String sym = symbol(w);
        int b = Math.max(11, font.width(sym) + 4);
        Ansicht.rechteck(g, x - b / 2 - 1, y - 6, b + 2, 13, 0xFF000000);
        Ansicht.rechteck(g, x - b / 2, y - 5, b, 11, w.argb() | 0xFF000000);
        int hell = ((w.argb() >> 16) & 0xFF) * 3 + ((w.argb() >> 8) & 0xFF) * 6 + (w.argb() & 0xFF);
        int schrift = hell > 1600 ? 0xFF000000 : 0xFFFFFFFF;            // auf hellen Farben dunkle Schrift
        g.text(font, sym, x - font.width(sym) / 2 + 1, y - 3, schrift, false);
    }

    // ───── Anlegen ─────
    static void hierSetzen() {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        String name = freierName(T.t("wp.standard"));
        Waypoints.add(name, p.blockPosition(), FARBEN[Waypoints.all().size() % FARBEN.length]);
        Notify.show(T.t("wp.titel"), T.t("wp.gesetzt", name));
    }

    // ───── Manager ─────
    private String ansicht = OBERWELT;   // Dimension, deren Wegpunkte die Liste zeigt

    /** Zur Auswahl im Manager: Oberwelt, Nether, Ende – dazu jede weitere, in der es Wegpunkte gibt (Datapacks, Server). */
    static List<String> dimensionen(List<Waypoints.Waypoint> alle) {
        var l = new ArrayList<>(List.of(OBERWELT, NETHER, ENDE));
        for (var w : alle) if (!l.contains(w.dimension())) l.add(w.dimension());
        return l;
    }

    /** Die Dimension nach dim in der Liste, nach der letzten wieder die erste. */
    static String naechste(List<String> dims, String dim) {
        return dims.get((dims.indexOf(dim) + 1) % dims.size());
    }

    static String dimName(String dim) {
        return switch (dim) {
            case OBERWELT -> T.t("dim.oberwelt");
            case NETHER -> T.t("dim.nether");
            case ENDE -> T.t("dim.ende");
            default -> dim.substring(dim.indexOf(':') + 1);
        };
    }

    void oeffnen() {
        ansicht = Waypoints.dimension() == null ? OBERWELT : Waypoints.dimension();   // beim Öffnen: wo du gerade bist
        Page.open(T.t("wp.liste"), () -> {
            var l = new ArrayList<Setting>();
            l.add(Setting.button(T.t("wp.neu"), T.t("wp.neu.info"), T.t("wp.hinzu"), () -> {
                hierSetzen();
                ansicht = Waypoints.dimension();   // der neue landet hier – also auch hier anzeigen
            }));
            l.add(Setting.toggle(T.t("wp.inwelt"), T.t("wp.inwelt.info"), () -> inWelt, v -> cfg.set("wp_welt", inWelt = v)));
            l.add(Setting.choice(T.t("wp.entf"), null, ENTFERNUNG, () -> ENTFERNUNG.get(entfernung),
                v -> cfg.set("wp_entfernung", entfernung = ENTFERNUNG.indexOf(v)), v -> T.t("wp.entf." + v)));
            l.add(Setting.toggle(T.t("wp.tod.an"), T.t("wp.tod.an.info"), () -> todAn, v -> cfg.set("wp_tod", todAn = v)));
            l.add(Setting.toggle(T.t("wp.tod.loeschen"), T.t("wp.tod.loeschen.info"), () -> todLoeschen,
                v -> cfg.set("wp_tod_loeschen", todLoeschen = v)).visibleIf(() -> todAn));
            l.add(Setting.toggle(T.t("wp.chat"), T.t("wp.chat.info"), () -> cfg.bool("wp_chat", true), v -> cfg.set("wp_chat", v)));
            String hierDim = Waypoints.dimension();
            // Knopf statt Auswahl: Seiten aus Page.open baut der Client nur nach einem Knopf neu auf, nach einer Auswahl nicht –
            // die Liste darunter bliebe sonst bei der alten Dimension stehen
            var dims = dimensionen(Waypoints.all());
            l.add(Setting.button(T.t("wp.dimension", dimName(ansicht) + (ansicht.equals(hierDim) ? T.t("wp.dimension.hier") : "")),
                T.t("wp.dimension.info"), T.t("wp.dimension.weiter"), () -> ansicht = naechste(dims, ansicht)));
            boolean hierDa = ansicht.equals(hierDim);
            var liste = new ArrayList<>(nurIn(Waypoints.all(), ansicht));
            // hier: die nächsten zuerst; in einer anderen Dimension sagt die Entfernung nichts – nach Name
            liste.sort(hierDa ? Comparator.comparingDouble(Wegpunkte::abstand)
                : Comparator.comparing(Waypoints.Waypoint::name, String.CASE_INSENSITIVE_ORDER));
            l.add(Setting.heading(T.t(liste.isEmpty() ? "wp.keine" : "wp.diese", dimName(ansicht))));
            for (var w : liste) {
                String info = w.pos().getX() + ", " + w.pos().getY() + ", " + w.pos().getZ() + (hierDa ? " · " + entfernungText(abstand(w)) : "")
                    + (sichtbar(w) ? "" : T.t("wp.ausgeblendet")) + (eigen(w) ? "" : T.t("wp.von", w.addon()));
                l.add(eigen(w)
                    ? Setting.button("[" + symbol(w) + "] " + w.name(), info, T.t("wp.bearbeiten"), () -> bearbeiten(w.name()))
                    : Setting.info(() -> "[" + symbol(w) + "] " + w.name() + " – " + info));
            }
            return l;
        });
    }

    private static boolean freundeDa() {
        try {
            return Friends.available();
        } catch (SecurityException e) {   // Berechtigung „friends“ nicht erteilt
            return false;
        }
    }

    // Freunde (Client-Freundesliste): Wegpunkt als Text schicken; beim Empfänger wird daraus über anbieten() ein Angebot
    private static void anFreund(String name) {
        Page.open(T.t("wp.freund"), () -> {
            var l = new ArrayList<Setting>();
            var freunde = Friends.list();
            if (freunde.isEmpty()) l.add(Setting.info(() -> T.t("wp.freund.keine")));
            for (var f : freunde)
                l.add(Setting.button(f.name(), T.t(f.online() ? "wp.freund.online" : "wp.freund.offline"), T.t("wp.freund.senden"), () -> {
                    Waypoints.Waypoint w = null;
                    for (var x : Waypoints.all()) if (eigen(x) && x.name().equals(name)) w = x;
                    if (w == null) return;
                    String text = alsText(w);
                    Friends.send(f.uuid(), text.length() > Friends.MAX_LENGTH ? text.substring(0, Friends.MAX_LENGTH) : text)
                        .whenComplete((m, e) -> Notify.show(T.t("wp.titel"), T.t(e == null ? "wp.freund.ok" : "wp.freund.fehler", f.name())));
                }));
            return l;
        });
    }

    private Waypoints.Waypoint finde(String name) {
        for (var w : Waypoints.all()) if (eigen(w) && w.name().equals(name)) return w;
        return null;
    }

    // Name und Farbe ändert man über Löschen + neu Anlegen (die API hat kein Umbenennen); Symbol/Sichtbarkeit wandern mit
    private void bearbeiten(String start) {
        String[] name = {start};
        Page.open(start, () -> {
            var w = finde(name[0]);
            if (w == null) return List.of(Setting.info(() -> T.t("wp.geloescht")));
            // Waypoints.add setzt immer in die aktuelle Dimension: Name, Farbe und Position (= löschen + neu anlegen)
            // darum nur dort ändern, wo der Wegpunkt liegt – sonst würde er die Dimension wechseln
            boolean hier = w.dimension().equals(Waypoints.dimension());
            return List.of(
                Setting.info(() -> T.t("wp.anderedim", dimName(w.dimension()))).visibleIf(() -> !hier),
                Setting.text(T.t("wp.name"), null, 32, () -> name[0], v -> {
                    var alt = finde(name[0]);
                    if (alt == null || v.isBlank() || v.equals(name[0])) return;
                    String neu = freierName(v.trim());
                    String sym = AddonConfig.of(ADDON).text("wp_sym." + schluessel(alt), "");
                    boolean an = sichtbar(alt);
                    Waypoints.remove(alt.name());
                    var n = Waypoints.add(neu, alt.pos(), alt.argb());
                    cfg.set("wp_sym." + schluessel(n), sym);
                    cfg.set("wp_an." + schluessel(n), an);
                    name[0] = neu;
                }).visibleIf(() -> hier),
                Setting.text(T.t("wp.symbol"), T.t("wp.symbol.info"), 2, () -> cfg.text("wp_sym." + schluessel(w), ""),
                    v -> cfg.set("wp_sym." + schluessel(w), v.trim())),
                Setting.color(T.t("wp.farbe"), null, () -> w.argb() & 0xFFFFFF, v -> {
                    var alt = finde(name[0]);
                    if (alt == null) return;
                    Waypoints.remove(alt.name());
                    Waypoints.add(alt.name(), alt.pos(), 0xFF000000 | v);
                }).visibleIf(() -> hier),
                Setting.toggle(T.t("wp.inwelt"), null, () -> sichtbar(w), v -> cfg.set("wp_an." + schluessel(w), v)),
                Setting.info(() -> hier
                    ? T.t("wp.position", w.pos().getX() + ", " + w.pos().getY() + ", " + w.pos().getZ(), entfernungText(abstand(w)))
                    : T.t("wp.position.dort", w.pos().getX() + ", " + w.pos().getY() + ", " + w.pos().getZ(), dimName(w.dimension()))),
                Setting.button(T.t("wp.verschieben"), T.t("wp.verschieben.info"), T.t("wp.verschieben.knopf"), () -> {
                    var p = Minecraft.getInstance().player;
                    var alt = finde(name[0]);
                    if (p == null || alt == null) return;
                    Waypoints.remove(alt.name());
                    Waypoints.add(alt.name(), p.blockPosition(), alt.argb());
                }).visibleIf(() -> hier),
                Setting.button(T.t("wp.freund"), T.t("wp.freund.info"), T.t("wp.freund.waehlen"), () -> anFreund(name[0]))
                    .visibleIf(Wegpunkte::freundeDa),
                Setting.button(T.t("wp.loeschen"), null, T.t("wp.loeschen"), () -> {
                    var alt = finde(name[0]);
                    if (alt != null) Waypoints.remove(alt.name());
                }));
        });
    }

    /** Für den Selbsttest: anlegen, umbenennen per remove/add, löschen. */
    static boolean selbsttest() {
        String n = freierName("Test-WP");
        Waypoints.add(n, new BlockPos(1, 64, 2), FARBEN[0]);
        boolean da = Waypoints.all().stream().anyMatch(w -> eigen(w) && w.name().equals(n));
        String sym = Waypoints.all().stream().filter(w -> w.name().equals(n)).findFirst().map(Wegpunkte::symbol).orElse("");
        boolean weg = Waypoints.remove(n) && Waypoints.all().stream().noneMatch(w -> w.name().equals(n));
        return da && sym.equals("T") && weg && entfernungText(458.9).equals(T.locale().getLanguage().equals("de") ? "458,9m" : "458.9m");
    }
}
