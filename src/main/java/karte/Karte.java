package karte;

import client.api.AddonConfig;
import client.api.HudElement;
import client.api.Keys;
import client.api.Module;
import client.api.Setting;
import client.api.Tasks;
import client.api.Testing;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;

// Karte im Stil von JourneyMap: Minimap (Minimap.java) und Weltkarte (Vollbild.java) aus gemeinsamen Kartendaten
// (Kartendaten.java), die nebenbei alle geladenen Chunks abtasten und speichern.
// Tasten wie bei JourneyMap: J = Weltkarte, M = Minimap an/aus, + / - = Zoom, [ = Kartentyp der Minimap.
public final class Karte implements Module {
    /** Addon-ID (Jar-Name, Config, Ordner, Tasten, Texturen, Wegpunkt-Besitzer). Früher "karte" – siehe Umzug. */
    static final String ID = "minimap";

    static {
        Umzug.vonAlterId();   // vor der ersten Config/Taste: Daten der alten ID "karte" übernehmen
    }

    private final AddonConfig cfg = AddonConfig.of(Karte.ID);
    private final KeyMapping weltKey = Keys.key(Karte.ID, "world", InputConstants.KEY_J);
    private final KeyMapping miniKey = Keys.key(Karte.ID, "minimap", InputConstants.KEY_M);
    private final KeyMapping zoomIn = Keys.key(Karte.ID, "zoom_in", InputConstants.KEY_EQUALS);
    private final KeyMapping zoomOut = Keys.key(Karte.ID, "zoom_out", InputConstants.KEY_MINUS);
    private final KeyMapping typKey = Keys.key(Karte.ID, "type", InputConstants.KEY_LBRACKET);
    private final KeyMapping wpNeuKey = Keys.key(Karte.ID, "waypoint_new", InputConstants.KEY_B);
    private final KeyMapping wpListeKey = Keys.key(Karte.ID, "waypoints", InputConstants.KEY_N);
    private final Wegpunkte wegpunkte = new Wegpunkte(cfg);
    private final Kartendaten daten = new Kartendaten();
    private final Minimap minimap = new Minimap(cfg, daten);
    private final Vollbild vollbild = new Vollbild(cfg, daten);
    private int takt;

    {
        vollbild.manager = wegpunkte::oeffnen;
    }

    @Override
    public List<KeyMapping> keys() {
        return List.of(weltKey, miniKey, zoomIn, zoomOut, typKey, wpNeuKey, wpListeKey);
    }

    @Override
    public void tick() {
        while (weltKey.consumeClick()) vollbild.oeffnen();
        while (miniKey.consumeClick()) minimap.umschalten();
        while (zoomIn.consumeClick()) minimap.zoom(1);
        while (zoomOut.consumeClick()) minimap.zoom(-1);
        while (typKey.consumeClick()) minimap.naechsterModus();
        while (wpNeuKey.consumeClick()) Wegpunkte.hierSetzen();
        while (wpListeKey.consumeClick()) wegpunkte.oeffnen();
        if (++takt % 10 == 0) wegpunkte.ankunft();
        Wegpunkte.aktualisieren();
        minimap.tick();
        daten.tick();
    }

    @Override
    public void death(Component message) {
        wegpunkte.gestorben();
    }

    @Override
    public void respawn() {
        wegpunkte.lebt();
    }

    @Override
    public void leave() {
        wegpunkte.lebt();
        daten.close();
        Gesicht.leeren();
    }

    @Override
    public void unload() {
        daten.close();
        Kartendaten.gespeichert();   // Addon wird entladen: eingereihte Dateien noch schreiben
    }

    @Override
    public void join(String server) {
        Tasks.later(100, SeedKarte::abgleich);   // Einzelspieler: Seed-Karte gegen die echte Welt prüfen (Log)
        if (Testing.active()) Tasks.later(40, () -> {
            // Testwelt bleibt zwischen Läufen erhalten: aufräumen, unverwundbar, dann Köpfe auf der Minimap zeichnen lassen
            Testing.command("kill @e[type=!player]");
            Testing.command("gamemode creative");
            Testing.command("time set day");
            Testing.command("summon minecraft:cow ~3 ~ ~");
            Testing.command("summon minecraft:zombie ~-3 ~ ~2 {NoAI:1b}");
            var a = new Ansicht();
            a.cx = 100;
            a.cz = -50;
            a.scale = 2;
            a.drehung(37);
            Testing.check(Math.abs(a.wx(a.sx(120, -30), a.sy(120, -30)) - 120) < 1e-6
                && Math.abs(a.wz(a.sx(120, -30), a.sy(120, -30)) + 30) < 1e-6, "Drehung hin und zurück");
            a.drehung(180);
            Testing.check(a.sy(100, -60) < a.oy && a.sx(110, -50) > a.ox, "Norden oben, Osten rechts");
            int[] sp = Kartendaten.spiral(2);
            Testing.check(Kartendaten.key(-1, 2) != Kartendaten.key(2, -1) && sp[0] == 0 && sp[1] == 0, "Kacheln, Spirale beginnt beim Spieler");
            Testing.check(sp[2] == 0 && sp[3] == -1 && sp[4] == 1 && sp[5] == -1 && sp[6] == 1 && sp[7] == 0,
                "Nächster Ring zuerst, im Uhrzeigersinn ab Norden");
            Testing.check(options().size() == 7 && minimap.seiteMinimap().size() >= 6 && minimap.seiteKreaturen().size() >= 7
                && minimap.seiteInfo().size() == 4 && vollbild.options().size() >= 6, "Einstellungen: Hauptseite + kurze Unterseiten");
            Testing.check(Umzug.besitzerUmstellen("[{\"name\":\"Home\",\"addon\":\"karte\"},{\"name\":\"X\",\"addon\":\"karteplus\"},{\"addon\": \"karte\"}]")
                    .equals("[{\"name\":\"Home\",\"addon\":\"minimap\"},{\"name\":\"X\",\"addon\":\"karteplus\"},{\"addon\": \"minimap\"}]"),
                "Umzug: Wegpunkte von „karte“ gehören jetzt „minimap“");
            Testing.check(T.t("name").equals("MiniMap") && !T.t("mm.anzeigen").startsWith("minimap."), "Übersetzungen geladen (" + T.locale() + ": " + T.t("mm.anzeigen") + ")");
            Testing.check(Stil.kontrast(Stil.TEXT, Stil.RAHMEN) >= 7 && Stil.kontrast(Stil.LEISE, Stil.RAHMEN) >= 4.5
                    && Stil.kontrast(Stil.aufAkzent(), Stil.akzent()) >= 4.5,
                "Kontrast: Text, leiser Text und Schrift auf dem Akzent (" + String.format("%06X", Stil.akzent() & 0xFFFFFF) + ", "
                    + String.format(java.util.Locale.ROOT, "%.1f", Stil.kontrast(Stil.aufAkzent(), Stil.akzent())) + ":1)");
            Testing.check(Wegpunkte.selbsttest(), "Wegpunkt anlegen, Symbol „T“, löschen, Entfernung „458,9m“");
            Testing.check(Long.valueOf(-123456789L).equals(SeedKarte.seed("https://www.chunkbase.com/apps/seed-map#seed=-123456789&platform=java_1_21_9&dimension=overworld&x=0&z=0&zoom=0.5"))
                && Long.valueOf(42L).equals(SeedKarte.seed("42")) && SeedKarte.seed("hallo") == null, "Seed aus Chunkbase-Link lesen");
            long t0 = System.nanoTime();
            String biom = SeedKarte.biomAn(42, 0, 0);
            System.out.println("[minimap] Seed 42, Block 0/0: " + biom + " (" + (System.nanoTime() - t0) / 1_000_000 + " ms inkl. Aufbau)");
            Testing.check(!biom.equals("?") && !biom.isBlank(), "Biom aus Seed berechnet (" + biom + ")");
            t0 = System.nanoTime();
            var kachel = SeedKarte.rechne(42, "minecraft:overworld", 0, 0, SeedKarte.Modus.GELAENDE, 1);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int gefuellt = 0;
            if (kachel != null) {
                for (int i = 0; i < 256 * 256; i += 97) if (kachel.getPixel(i % 256, i / 256) != 0) gefuellt++;
                kachel.close();
            }
            System.out.println("[minimap] Seed-Gelände-Kachel 256×256: " + ms + " ms, Höhe bei 0/0: " + SeedKarte.hoeheAn(42, 0, 0));
            Testing.check(kachel != null && gefuellt > 600, "Gelände aus Seed gemalt (" + ms + " ms je Kachel)");
            // Säule: Oberfläche Y 64 (Himmel darüber, rot), Höhle mit Boden Y 30 und Decke Y 40 (blau)
            short[] ys = {64, 30}, decken = {321, 40};
            int[] farben = {0xFFFF0000, 0xFF0000FF};
            Testing.check(Hoehlen.scheibe(ys, decken, farben, 0, 2, 64) == 0xFFFF0000
                && (Hoehlen.scheibe(ys, decken, farben, 0, 2, 35) & 0xFF) > 0                 // im Hohlraum: Höhlenboden
                && (Hoehlen.scheibe(ys, decken, farben, 0, 2, 44) & 0xFF) > 0                 // 14 im Stein darüber: noch sichtbar
                && Hoehlen.scheibe(ys, decken, farben, 0, 2, 50) == Hoehlen.FEST              // 20 darüber: fester Stein
                && ((Hoehlen.scheibe(ys, decken, farben, 0, 2, 100) >> 16) & 0xFF) > 0,       // in der Luft: Oberfläche
                "Höhenansicht für jede Y-Höhe");
            Testing.await("Karte gemalt", () -> daten.geladen() >= 3, 200, () -> Tasks.later(40, () -> {   // 2 s Köpfe zeichnen
                daten.save();
                Kartendaten.gespeichert();
                Testing.check(daten.dateien() >= 3, "Tag, Nacht und Topo gespeichert");
                Testing.check(daten.hoehlen.chunks() >= 1, "Höhlendaten nebenbei erfasst (" + daten.hoehlen.chunks() + " Chunks)");
                var p0 = Minecraft.getInstance().player.blockPosition();
                daten.uebersichtBudget(1);
                Testing.check(daten.uebersicht(Kartendaten.Ebene.of(Kartendaten.Typ.TAG),
                        Math.floorDiv(p0.getX(), Kartendaten.TILE * Kartendaten.UEBERSICHT), Math.floorDiv(p0.getZ(), Kartendaten.TILE * Kartendaten.UEBERSICHT)) != null,
                    "Übersicht (4 × 4 Kacheln) fürs Herauszoomen gebaut");
                var lod = SeedKarte.rechne(42, "minecraft:overworld", 0, 0, SeedKarte.Modus.GELAENDE, Kartendaten.UEBERSICHT);
                Testing.check(lod != null, "Seed-Übersicht (1024 Blöcke) gerechnet");
                if (lod != null) lod.close();
                Testing.check(Kartendaten.worldName().startsWith("sp_") || Kartendaten.worldName().equals(Kartendaten.clean("localhost:25598")),
                    "Weltname eindeutig (" + Kartendaten.worldName() + ")");
                String zombie = null;
                int n = 0;
                for (var e : Minecraft.getInstance().level.entitiesForRendering())
                    if (e.getType() == EntityTypes.ZOMBIE) {
                        n++;
                        if (zombie == null) zombie = Gesicht.ausschnitt64(e);
                    }
                System.out.println("[minimap] Zombies: " + n + ", Gesicht: " + zombie + ", Spieler lebt: " + Minecraft.getInstance().player.isAlive());
                Testing.check("8 8 8 8".equals(zombie), "Zombie-Gesicht aus der Textur (8, 8, 8 × 8)");
                // Testserver: normale Welt mit Seed 42 (build.gradle) – Seed-Karte muss deckungsgleich sein
                String v = SeedKarte.vergleich(42);
                System.out.println("[minimap] Seed-Vergleich (Biome, Höhen ±2 | echt~Seed): " + v);
                String[] teile = v.split("[ /|]+");
                Testing.check(teile[0].equals(teile[1]), "Seed-Biome = Welt (" + teile[0] + "/" + teile[1] + ")");
                Testing.check(Integer.parseInt(teile[2]) >= Integer.parseInt(teile[3]) * 8 / 10, "Seed-Höhen = Welt ±2 (" + teile[2] + "/" + teile[3] + ")");
                // Todespunkt: abseits sterben, wiederbeleben, hingehen
                client.api.Waypoints.remove(T.t("wp.tod"));   // Rest eines abgebrochenen Laufs
                var start = Minecraft.getInstance().player.blockPosition();   // danach zurück: die Testwelt bleibt bestehen
                Testing.command("tp @s ~60 ~ ~");
                Tasks.later(10, () -> {
                    Testing.command("kill @s");
                    Testing.await("Todespunkt gesetzt", () -> Wegpunkte.todespunkt() != null, 100, () -> {
                        var w = Wegpunkte.todespunkt();
                        var mc = Minecraft.getInstance();
                        mc.player.respawn();
                        mc.gui.setScreen(null);
                        // erst weiter, wenn der Server wirklich wiederbelebt hat (Spieler am Spawn), sonst geht das tp vorher verloren
                        Testing.await("Wiederbelebt, Todespunkt noch da", () -> w != null && mc.player.isAlive()
                                && !mc.player.blockPosition().closerThan(w.pos(), 20) && Wegpunkte.todespunkt() != null, 100, () -> {
                            if (w != null) Testing.command("tp @s " + w.pos().getX() + " " + w.pos().getY() + " " + w.pos().getZ());
                            Testing.await("Todespunkt beim Erreichen gelöscht", () -> Wegpunkte.todespunkt() == null, 100, () -> {
                                Testing.command("tp @s " + start.getX() + " " + start.getY() + " " + start.getZ());
                                Tasks.later(10, Testing::done);
                            });
                        });
                    });
                });
            }));
        });
    }

    @Override
    public List<HudElement> hud() {
        return List.of(minimap.hud(), wegpunkte.hud());
    }

    @Override
    public List<Setting> options() {
        // Kurze Hauptseite mit Unterseiten: der Client springt nach jedem Schalter an den Anfang der Liste –
        // so passt jede Seite ohne Scrollen auf den Bildschirm
        return List.of(
            minimap.anzeigen(),
            seite("minimap", minimap::seiteMinimap),
            seite("kreaturen", minimap::seiteKreaturen),
            seite("info", minimap::seiteInfo),
            seite("welt", vollbild::options),
            Setting.button(T.t("seite.wegpunkte"), T.t("seite.wegpunkte.info"), T.t("oeffnen"), wegpunkte::oeffnen),
            seite("tasten", () -> List.of(Setting.key(weltKey), Setting.key(miniKey), Setting.key(zoomIn),
                Setting.key(zoomOut), Setting.key(typKey), Setting.key(wpNeuKey), Setting.key(wpListeKey))));
    }

    // Knopf zu einer Unterseite; Titel und Beschreibung aus seite.<name> und seite.<name>.info
    private static Setting seite(String name, java.util.function.Supplier<List<Setting>> inhalt) {
        String titel = T.t("seite." + name);
        return Setting.button(titel, T.t("seite." + name + ".info"), T.t("oeffnen"), () -> client.api.Page.open(titel, inhalt));
    }
}
