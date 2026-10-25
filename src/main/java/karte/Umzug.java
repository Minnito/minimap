package karte;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;

// Einmaliger Umzug von der alten Addon-ID "karte" auf "minimap" – läuft beim Laden der Klasse Karte,
// also bevor das Addon seine Config liest oder Tasten anmeldet. Jeder Schritt nur, wenn das Ziel noch fehlt.
final class Umzug {
    static final String ALT = "karte";
    private static final Pattern WEGPUNKT_BESITZER = Pattern.compile("(\"addon\"\\s*:\\s*\")" + ALT + "(\")");

    static void vonAlterId() {
        Path mods = Minecraft.getInstance().gameDirectory.toPath().resolve("client-modules");
        if (!Files.isDirectory(mods)) return;
        datei(mods.resolve(ALT + ".properties"), mods.resolve(Karte.ID + ".properties"));      // Einstellungen
        ordner(mods.resolve(ALT), mods.resolve(Karte.ID));                                      // Server-Karten, Seed-Cache, je-Server-Config
        properties(mods.resolve("keys.properties"), p -> {                                       // Tastenbelegung key.karte.* → key.minimap.*
            boolean geaendert = false;
            for (String k : p.stringPropertyNames())
                if (k.startsWith("key." + ALT + ".") && !p.containsKey("key." + Karte.ID + k.substring(4 + ALT.length()))) {
                    p.setProperty("key." + Karte.ID + k.substring(4 + ALT.length()), p.getProperty(k));
                    p.remove(k);
                    geaendert = true;
                }
            return geaendert;
        });
        try (Stream<Path> dateien = Files.isDirectory(mods.resolve("waypoints")) ? Files.list(mods.resolve("waypoints")) : Stream.empty()) {
            for (Path f : dateien.filter(f -> f.toString().endsWith(".properties")).toList())
                properties(f, p -> {                                                             // Wegpunkte gehören weiter diesem Addon
                    String liste = p.getProperty("list");
                    if (liste == null) return false;
                    String neu = besitzerUmstellen(liste);
                    if (neu.equals(liste)) return false;
                    p.setProperty("list", neu);
                    return true;
                });
        } catch (IOException e) {
            System.err.println("[minimap] Wegpunkte nicht umgestellt: " + e);
        }
    }

    /** "addon":"karte" → "addon":"minimap" in der gespeicherten Wegpunkt-Liste; andere Addons bleiben unberührt. */
    static String besitzerUmstellen(String liste) {
        return WEGPUNKT_BESITZER.matcher(liste).replaceAll("$1" + Karte.ID + "$2");
    }

    /** Ordner verschieben, wenn das Ziel fehlt oder leer ist (AddonConfig.dir legt es ggf. schon leer an). */
    static void ordner(Path alt, Path neu) {
        if (!Files.isDirectory(alt)) return;
        try {
            if (Files.isDirectory(neu)) {
                try (var inhalt = Files.list(neu)) {
                    if (inhalt.findAny().isPresent()) return;
                }
                Files.delete(neu);
            }
            Files.move(alt, neu);
            System.out.println("[minimap] Umgezogen: " + alt + " → " + neu);
        } catch (IOException e) {
            System.err.println("[minimap] Umzug fehlgeschlagen: " + alt + " – " + e);
        }
    }

    private static void datei(Path alt, Path neu) {
        if (!Files.exists(alt) || Files.exists(neu)) return;
        try {
            Files.move(alt, neu);
            System.out.println("[minimap] Umgezogen: " + alt + " → " + neu);
        } catch (IOException e) {
            System.err.println("[minimap] Umzug fehlgeschlagen: " + alt + " – " + e);
        }
    }

    private interface Aenderung {
        boolean an(Properties p);
    }

    private static void properties(Path f, Aenderung aenderung) {
        if (!Files.exists(f)) return;
        var p = new Properties();
        try (Reader in = Files.newBufferedReader(f)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        if (!aenderung.an(p)) return;
        try (Writer out = Files.newBufferedWriter(f)) {
            p.store(out, null);
            System.out.println("[minimap] Umgestellt: " + f.getFileName());
        } catch (IOException e) {
            System.err.println("[minimap] Nicht umgestellt: " + f + " – " + e);
        }
    }
}
