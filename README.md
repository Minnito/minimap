# Entwicklerprotokoll: MiniMap

| | |
|---|---|
| **Addon** | MiniMap (ID `minimap`, früher `karte`) |
| **Version** | 1.3.0 |
| **Entwickler** | Minnito |
| **Plattform** | Client-Addon für den MC Launcher, API 6.2.1, Minecraft 26.3 |
| **Sprache / Build** | Java 25 (`--release 25`), Gradle 9.4.1 (Wrapper) |
| **Stand** | 25.10.2026 |
| **Build** | `build/libs/minimap.jar`, 120 851 Byte, SHA-256 `13079ecae0ba0a8acf1c894ae20c82aabede057be9efc77cf3db900a7b535dca` |
| **Selbsttest** | `gradlew runTest`: 21 von 21 Prüfungen bestanden (25.10.2026, 13:11) |

---

## 1. Ziel

Eine Minimap und Weltkarte im Stil von JourneyMap und Xaero's Minimap, die ohne Fabric oder Forge direkt im Client des Launchers läuft:

- Minimap und Weltkarte aus denselben, nebenbei erfassten Kartendaten
- Kartentypen Tag, Nacht, Topo und Höhle, die Höhle auf **jeder** Y-Höhe
- Entity-Radar mit echten Kreaturen-Gesichtern
- Wegpunkte mit Manager und Anzeige in der Welt
- Seed-Karte aus einem Chunkbase-Link: Biome und Gelände von Minecrafts eigenem Generator gerechnet, damit sie mit Chunkbase und mit der Welt übereinstimmt

## 2. Funktionen

### Minimap (`M` an/aus)
- Quadrat oder Kreis; der Kreis dreht sich auf Wunsch mit der Blickrichtung
- Größe einstellbar, 7 Zoomstufen (0,125 bis 8 GUI-Pixel je Block, Tasten `+` / `-`)
- Kartentyp Automatisch, Tag, Nacht, Topo oder Höhle (Taste `[`). Automatisch nimmt unter Tage Höhle und nachts Nacht
- Kompass (N, O, S, W) am Rand, Chunk-Gitter
- Radar: Monster, Tiere, Haustiere, Dorfbewohner und Spieler einzeln filterbar, als Kopf oder als 2×2-Punkt
- Vier Info-Zeilen (zwei oben, zwei unten): Spielzeit, Koordinaten, Biom, Dimension, FPS, Minecraft-Tag oder Lichtstufe
- Wird in echten Bildschirmpixeln gezeichnet, damit sie auch bei hoher GUI-Skalierung scharf bleibt

### Weltkarte (`J`)
- Kartentypen und Filter oben, Zoom links, Positionen von Spieler und Maus unten
- Y-Slider für die Höhlenansicht; ein Wechsel der Ebene blendet in 220 ms über
- Linke Maustaste halten = verschieben, Mausrad = Zoom, Pfeiltasten = 16 Blöcke verschieben, Leertaste = zurück zu dir, `F` = dir folgen, `C` = Koordinaten kopieren, `B` = Wegpunkt an der Maus
- Rechtsklick-Menü: Wegpunkt setzen oder löschen, Wegpunkte verwalten, Koordinaten kopieren oder in den Chat, hier zentrieren, Entfernung anzeigen
- Übersichtsstufe (je 4 × 4 Kacheln) für weites Herauszoomen

### Seed-Karte
- Seed aus einem Chunkbase-Share-Link (`…#seed=…&platform=java_…`) oder als Zahl
- Zwei Modi: Gelände (Vanilla-Kartenfarben, Höhenschattierung, Wassertiefe, Wälder als Blätterdach) und Biomkarte in Chunkbase-Farben
- Wird im Hintergrund gerechnet (bis zu 4 Threads) und als PNG zwischengespeichert, je Welt bzw. Server
- Server können sie über die Funktion `seedmap` abschalten

### Wegpunkte (`B` = hier setzen, `N` = Manager)
- In der Welt als farbiges Quadrat mit Symbol und Name; beim Hinschauen mit Entfernung (Einstellung: beim Hinschauen, immer, nie)
- Manager mit Liste, Neu, Bearbeiten, Löschen; eigenes Symbol (1 bis 2 Zeichen) und Sichtbarkeit je Wegpunkt
- Gespeichert über die Client-API (`Waypoints`). Wegpunkte anderer Addons werden angezeigt, aber nicht bearbeitet

### Sonstiges
- Sprachen Deutsch und Englisch, andere Sprachen fallen auf Englisch zurück. Zahlen im Format der Spielsprache (458,9 bzw. 458.9)
- Einstellungen auf kurze Unterseiten verteilt (Minimap, Kreaturen & Wegpunkte, Info-Zeilen, Weltkarte & Chunkbase, Wegpunkte, Tasten), damit jede Seite ohne Scrollen passt
- Alle Tasten frei belegbar

## 3. Architektur

12 Klassen im Paket `karte`, zusammen 2 922 Zeilen.

| Klasse | Zeilen | Aufgabe |
|---|---:|---|
| `Karte` | 187 | Einstieg (`client.api.Module`): Tasten, HUD, Einstellungen, Lebenszyklus (`join`, `leave`, `unload`), Selbsttest |
| `Kartendaten` | 460 | Tastet geladene Chunks ab und malt je Block einen Pixel in Kacheln zu 256 × 256 Blöcken, getrennt nach Ebene. Reihenfolge: Ringe um den Spieler, nächste zuerst. Zeitbudget 4 ms je Tick |
| `Hoehlen` | 264 | Höhlendaten für jede Y-Höhe: je Blocksäule die Böden (fester Block mit Luft darüber) mit Farbe und Decke. Daraus ergibt sich jede Höhe sofort |
| `Minimap` | 298 | Setzt das Minimap-Bild auf der CPU aus den Kacheln zusammen; rechnet nur neu, wenn sich etwas geändert hat |
| `Vollbild` | 500 | Weltkarte: Bedienung, Knöpfe, Rechtsklick-Menü, Überblendung, Seed-Karte als Unterlage |
| `SeedKarte` | 477 | Biome und Geländehöhe aus dem Seed mit `MultiNoiseBiomeSource` / `NoiseBasedChunkGenerator`; Thread-Pool, PNG-Cache, Abgleich mit der echten Welt |
| `Ansicht` | 248 | Umrechnung Welt ↔ Bildschirm (Mitte, Maßstab, Drehung); gemeinsames Zeichnen von Wegpunkten, Radar und Spielerpfeil |
| `Gesicht` | 115 | Kreaturen-Gesicht aus der eigenen Textur: Modellteil `head`, größter Würfel, Fläche nach vorn. Funktioniert für alle Kreaturen ohne eigene Bilder |
| `Wegpunkte` | 215 | Wegpunkte in der Welt und Manager |
| `Biomfarben` | 38 | Biomfarben wie bei Chunkbase/Amidst; unbekannte Biome bekommen eine feste Farbe aus dem Namen |
| `Umzug` | 101 | Einmaliger Umzug von der alten ID `karte` auf `minimap` (Einstellungen, Ordner, Tastenbelegung, Wegpunkt-Besitzer) |
| `T` | 19 | Übersetzungen aus `assets/minimap/lang/` |

**Datenfluss:** `Kartendaten` (und darin `Hoehlen`) tasten jeden Tick im Zeitbudget Chunks ab → Kacheln als `NativeImage` + GPU-Textur → `Minimap` und `Vollbild` zeichnen daraus über `Ansicht`. `SeedKarte` liefert unter der erkundeten Karte die gerechnete Unterlage.

### Speicherorte

| Was | Wo |
|---|---|
| Kartenkacheln | `client-modules/minimap/<welt>/<dimension>/<ebene>/<x>_<z>.png` |
| Einzelspieler | im Weltordner unter `<welt>/minimap/` (Berechtigung `files`), damit die Karte mit der Welt wandert |
| Höhlendaten | `<dimension>/hoehlen/<rx>_<rz>.bin` je Region (32 × 32 Chunks), gzip |
| Seed-Cache | `seedkarte/<seed>_<dim>_<modus>_x<maßstab>_v2/` als PNG |
| Einstellungen | `client-modules/minimap.properties` (Client-API `AddonConfig`) |

Weltname: Server = Adresse (`localhost_25598`), Einzelspieler = `sp_` + Ordnername der Welt. Zwei Welten mit gleichem Anzeigenamen kommen sich dadurch nicht mehr in die Quere.

## 4. Verlauf

Die Arbeit lief am 25.10.2026, von der Einarbeitung bis zum fertigen Build 1.3.0.

1. **Einarbeitung (ca. 00:11).** Grundwissen zur Client-API 6.2.1 zusammengetragen (`kb/Grundwissen-Client-Module.md`, Original-Doku in `kb/eclipse-docs.txt`) und das Projekt als Wissensgraph erfasst (`graphify-out/`).
2. **Gerüst (ca. 00:27).** Gradle-Vorlage aufgesetzt: `build`, `runClient`, `install`, `runTest`, `publishAddon`. Minecraft und Libraries lädt der Build direkt von Mojang. Ohne Addon-Server nimmt er API und Client aus dem installierten Launcher.
3. **Kern unter der ID `karte`.** Kartendaten, Minimap, Weltkarte, Höhlen, Seed-Karte, Radar mit Gesichtern.
4. **Ideen-Report (`ideen-report-karte.md`).** Kompletter Scan mit Fehlerliste und Ideen. Die wichtigsten Punkte daraus sind umgesetzt (Abschnitt 5).
5. **Nachbesserungen (`Verbesserungen.md`, 02:08).** Notiert: Icons der Weltkarte überarbeiten, Chunkbase-Karte einfügen können. Die Chunkbase-Karte ist eingebaut (Seed-Karte aus dem Chunkbase-Link); die Knöpfe der Weltkarte haben Beschriftung und Tooltips.
6. **Umbenennung auf `minimap` (Version 1.3.0, bis 13:10).** Neue ID, Name „MiniMap“, Übersetzungen ausgelagert (de_de, en_us), Einstellungen auf Unterseiten aufgeteilt, Wegpunkt-Manager. `Umzug` übernimmt die Daten der alten ID beim ersten Start.
7. **Build und Test (13:10 bis 13:11).** `minimap.jar` gebaut, `runTest` mit 21 von 21 Prüfungen bestanden.

## 5. Behobene Fehler und umgesetzte Ideen

| Problem | Lösung | Nachweis |
|---|---|---|
| Seed-Geländehöhen passten nicht zur Welt (Abgleich: Höhe ±3 bei 0 von 7 Punkten) | Höhenberechnung und Abgleich überarbeitet | Test „Seed-Höhen = Welt ±2 (25/25)“ |
| Alter Seed-Cache mit falsch gerechneten Kacheln blieb liegen | Cache-Version `v2` im Ordnernamen, alte Ordner werden beim Start gelöscht | `SeedKarte.CACHE_VERSION` |
| Gesichter-Cache überlebte den Weltwechsel (Kuh #57 wurde zum Zombie #57) | `Gesicht.leeren()` in `Karte.leave()` | Code |
| Gleichnamige Einzelspielerwelten teilten sich die Karte | Weltname aus dem Ordnernamen der Welt | Test „Weltname eindeutig“ |
| Weites Herauszoomen lud ständig Kacheln nach | Übersichtsstufe aus 4 × 4 Kacheln, auch für die Seed-Karte | Tests „Übersicht (4 × 4 Kacheln)“, „Seed-Übersicht (1024 Blöcke)“ |
| Testserver war eine flache Welt, die Seed-Karte also nicht prüfbar | `runTest` startet eine normale Welt mit Seed 42 und vergleicht Biome und Höhen an 25 Punkten | Tests „Seed-Biome = Welt“, „Seed-Höhen = Welt“ |
| Wegpunkte hießen immer „Wegpunkt N“, ohne Liste und ohne Anzeige in der Welt | Wegpunkt-Manager, Symbole, Anzeige in der Welt mit Entfernung | Test „Wegpunkt anlegen, Symbol „T“, löschen, Entfernung „458,9m““ |

## 6. Tests

`gradlew runTest` startet einen lokalen Vanilla-Server (Port 25598, Seed 42, Offline-Modus) und das Spiel mit dem Addon. Das Addon prüft sich über `client.api.Testing` selbst. Ergebnis vom 25.10.2026:

```
PASS Drehung hin und zurück
PASS Norden oben, Osten rechts
PASS Kacheln, Spirale beginnt beim Spieler
PASS Nächster Ring zuerst, im Uhrzeigersinn ab Norden
PASS Einstellungen: Hauptseite + kurze Unterseiten
PASS Umzug: Wegpunkte von „karte“ gehören jetzt „minimap“
PASS Übersetzungen geladen (de_DE: Minimap anzeigen)
PASS Wegpunkt anlegen, Symbol „T“, löschen, Entfernung „458,9m“
PASS Seed aus Chunkbase-Link lesen
PASS Biom aus Seed berechnet (dark_forest)
PASS Gelände aus Seed gemalt (854 ms je Kachel)
PASS Höhenansicht für jede Y-Höhe
PASS Karte gemalt
PASS Tag, Nacht und Topo gespeichert
PASS Höhlendaten nebenbei erfasst (49 Chunks)
PASS Übersicht (4 × 4 Kacheln) fürs Herauszoomen gebaut
PASS Seed-Übersicht (1024 Blöcke) gerechnet
PASS Weltname eindeutig (localhost_25598)
PASS Zombie-Gesicht aus der Textur (8, 8, 8 × 8)
PASS Seed-Biome = Welt (25/25)
PASS Seed-Höhen = Welt ±2 (25/25)
```

Die GitHub Action (`.github/workflows/build.yml`) baut bei jedem Push nur das Jar. `runTest` braucht OpenGL und läuft darum lokal.

## 7. Bekannte Grenzen

Diese Abkürzungen sind bewusst so gewählt und im Code mit `ponytail:` markiert:

- **Kacheln bleiben im Speicher.** Alle gesehenen Kacheln bleiben bis zum Weltwechsel geladen (256 KB je Kachel und Ebene). Bei sehr großen Karten sollten Kacheln außer Sicht entladen werden (`Kartendaten`).
- **Seed-Karte ohne Strukturen.** Bäume, Dörfer, Bauwerke und Seen über Meereshöhe fehlen, weil sie die komplette Chunk-Generierung bräuchten (`SeedKarte`).
- **Minimap-Neuaufbau.** Bei großer Minimap und hoher GUI-Skalierung bis 512² Pixel je Neuaufbau, aber nur bei Bewegung um mindestens 1 Bildpunkt (`Minimap`).
- **Gesichter-Cache.** Wechselt eine Kreatur ihre Variante, sieht man das erst nach einem Weltwechsel (`Gesicht`).

Offen aus dem Ideen-Report: Kacheln und Höhlendaten atomar schreiben, Seed-Threads bei `.addon reload` beenden, Strukturen aus dem Seed, Seed-Gelände auf der Minimap, Karte als Bild exportieren.

## 8. Bauen und Starten

Voraussetzung: JDK 25 oder neuer. Minecraft und Libraries lädt der erste Build einmalig nach `~/.gradle/caches/client-mc`.

```bash
./gradlew build        # → build/libs/minimap.jar
./gradlew runClient    # Spiel mit Addon, offline als „Dev“, Ordner run/
./gradlew runTest      # Testserver + Spiel, Selbsttest
./gradlew install      # Jar ins Spiel kopieren; im Chat „.addon reload minimap“
```

Ohne erreichbaren Addon-Server nimmt der Build API und Client aus dem installierten Launcher (`%LOCALAPPDATA%/Programs/mc-launcher/resources/client.jar`, überschreibbar mit `-PclientJar=…`).

## 9. Tastenbelegung

| Taste | Aktion |
|---|---|
| `J` | Weltkarte öffnen |
| `M` | Minimap ein/aus |
| `+` / `-` | Minimap vergrößern / verkleinern |
| `[` | Kartentyp der Minimap wechseln |
| `B` | Wegpunkt hier setzen (auf der Weltkarte: an der Maus) |
| `N` | Wegpunkte verwalten |
| `F`, `C`, Pfeiltasten, Leertaste | auf der Weltkarte: folgen, Koordinaten kopieren, verschieben, zu dir |
