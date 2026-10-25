# Ideen-Report: Karte (Minimap- & Weltkarten-Addon)

*Erstellt am 25.10.2026 · Basierend auf einem vollständigen Scan von `D:\Builds\Client\Module\karte` (9 Java-Klassen, Build, Ressourcen, Laufzeitdaten in `run/` inkl. Log)*

## Projektübersicht

Client-Addon für den MC-Launcher (API 6.2.1, Minecraft 26.3, Gradle-Vorlage) im Stil von JourneyMap/Xaero. `Kartendaten` tastet geladene Chunks ab (Tag/Nacht/Topo als PNG-Kacheln), `Hoehlen` speichert je Blocksäule die Böden für Höhlenansichten auf jeder Y-Höhe, `Minimap` setzt ihr Bild auf der CPU zusammen (Kreis/Quadrat, Drehung, echte Bildschirmpixel), `Vollbild` ist die Weltkarte mit Y-Slider, Überblendung, Rechtsklick-Menü und Seed-Karte, `SeedKarte` rechnet Biome/Gelände aus einem Chunkbase-Seed, `Ansicht`/`Gesicht` liefern Projektion, Radar und Kreaturen-Gesichter.

## Top 5 Empfehlungen

1. **Seed-Höhen reparieren** – der Abgleich im Log sagt *Biome 7/7, Höhe ±3 0/7*: die Gelände-Seed-Karte ist falsch, das Kernversprechen des Chunkbase-Imports.
2. **Seed-Cache versionieren** – sonst bleiben die jetzt schon falsch gerechneten PNGs (über 100 Kacheln in `run/…/seedkarte/`) nach dem Fix für immer liegen.
3. **Gesichter-Cache beim Weltwechsel leeren** – Entity-IDs beginnen je Verbindung neu, `Gesicht.JE_ENTITY` zeigt sonst falsche Gesichter.
4. **Gleichnamige Einzelspielerwelten trennen** – zwei Welten namens „New World“ teilen sich Karte, Höhlen und Seed-Link.
5. **Übersichtsstufen (LOD) für weites Herauszoomen** – bei Zoom 0,125 sind hunderte Kacheln sichtbar, mehr als die Bild-Caches fassen → Dauer-Neuladen.

---

## 🛡️ Fehlerbehandlung & Robustheit

### Seed-Geländehöhe stimmt nicht mit der Welt überein
- **Beschreibung:** `SeedKarte.abgleich()` meldet im aktuellen `run/logs/latest.log` für Seed 485234206581305446 *Biome 7/7, Höhe ±3 0/7*. Biome stimmen also, `NoiseBasedChunkGenerator.getBaseHeight(…, OCEAN_FLOOR_WG, …)` liefert aber andere Höhen als die Welt (Selbsttest: Seed 42 bei 0/0 → 43, mitten im Dunkelwald).
- **Warum sinnvoll:** Ohne richtige Höhen sind Schattierung, Küstenlinien und Wasser auf der Seed-Karte erfunden.
- **Aufwand:** Mittel · **Impact:** Groß
- **Ansatz:** Erst messen statt raten: im Abgleich je Punkt `echt / berechnet` ausgeben. Prüfen: (a) vergleicht der Abgleich fair? `OCEAN_FLOOR` zählt Blätter mit, `OCEAN_FLOOR_WG` nicht – im Wald ist die echte Höhe 5–10 Blöcke höher, also mit `level.getChunk(...).getHeight(Types.OCEAN_FLOOR_WG, …)` bzw. der Höhlendaten-Oberfläche vergleichen; (b) passt der `LevelHeightAccessor` (`create(minY, height)` aus `noiseSettings()`); (c) liefert `getBaseHeight` in 26.3 die erste Luft oder den obersten Block (dann ist `h - 1` in `rechne()` doppelt abgezogen).

### Seed-Cache ohne Version
- **Beschreibung:** `SeedKarte.textur()` liest `seedkarte/<seed>_<dim>_<modus>/x_z.png` ungeprüft. Jede Änderung an `farbe()`, `Stil` oder dem Höhen-Fix wirkt bei schon gerechneten Gebieten nie.
- **Aufwand:** Klein · **Impact:** Groß
- **Ansatz:** Konstante `static final int VERSION = 2;` in den Ordnernamen (`…_gelaende_v2`), alte Ordner beim Start löschen.

### `Gesicht.JE_ENTITY` überlebt den Weltwechsel
- **Beschreibung:** Gesichter werden je `e.getId()` gemerkt und nur bei > 4096 Einträgen geleert. Entity-IDs fangen auf jedem Server/jeder Welt wieder klein an – Kuh #57 aus Welt A wird in Welt B zum Gesicht von Zombie #57.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** `static void leeren()` in `Gesicht`, aufrufen in `Karte.leave()`; oder Schlüssel `e.getUUID()`.

### Gleichnamige Einzelspielerwelten kollidieren
- **Beschreibung:** `Kartendaten.worldName()` nutzt `getLevelName()` → `sp_new_world`. Zwei Welten mit gleichem Namen schreiben in dieselben Kacheln/Höhlendaten. Der Seed-Link (`AddonConfig.forServer`) landet unter `world-New_World.properties` – gleiches Problem.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** Ordnername der Speicherwelt verwenden (`sp.getWorldPath(LevelResource.ROOT).getParent().getFileName()`), der ist eindeutig.

### Nicht-atomares Schreiben von Kacheln und Höhlendaten
- **Beschreibung:** `Kartendaten.save()` und `Hoehlen.save()` schreiben direkt in die Zieldatei; wird das Spiel dabei beendet (es gibt ja kein `unload` beim Schließen), ist die Region kaputt – `Hoehlen.region()` meldet „unlesbar“ und verwirft alle Chunks der Region.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** In `datei + ".tmp"` schreiben, dann `Files.move(tmp, ziel, ATOMIC_MOVE, REPLACE_EXISTING)`.

### Seed-Threads werden nie beendet
- **Beschreibung:** `SeedKarte.rechner` ist ein fester Thread-Pool je `Vollbild`-Instanz. Jedes `.addon reload karte` erzeugt neue Instanzen → alte Threads (inkl. der ~250 ms teuren `VanillaRegistries`-Lookups im static `generator`) bleiben liegen.
- **Aufwand:** Klein · **Impact:** Klein
- **Ansatz:** `SeedKarte.schliessen()` → `rechner.shutdownNow()` + `leeren()`, aus `Karte.unload()` über `Vollbild` aufrufen.

## ⚡ Performance

### Übersichtsstufen (LOD) für weites Herauszoomen
- **Beschreibung:** `Vollbild.zeichne()` holt bei jedem Bild jede sichtbare 256er-Kachel. Bei `scale = 0.125` und 1000 px Breite sind das ~600 Kacheln – mehr als `SeedKarte.MAX_BILDER` (300) und `Hoehlen.MAX_BILDER` (160). Die LRU-Caches werfen dann laufend Bilder raus, die im selben Bild wieder gebraucht werden.
- **Aufwand:** Mittel · **Impact:** Groß
- **Ansatz:** Ab `scale < 0.5` 4×4 Kacheln zu einer verkleinerten 256er-Übersichtskachel zusammenfassen (wie JourneyMaps „Zoom LODs“), getrennt cachen und bei Änderungen der Teilkacheln neu bauen.

### Kacheln außer Sicht entladen
- **Beschreibung:** `Kartendaten.ebenen` hält jede je geladene Kachel (3 Ebenen × 256 KB + GPU-Textur) bis zum Weltwechsel; im Code bereits als `ponytail:` markiert. Bei langen Sessions wächst das ohne Grenze.
- **Aufwand:** Mittel · **Impact:** Mittel
- **Ansatz:** Je Ebene eine LRU wie in `Hoehlen.bilder`; beim Rauswerfen erst speichern (`unsaved`), dann `release(id)`.

### `Hoehlen.save()` durchsucht alle Chunks je Region
- **Beschreibung:** Für jede geänderte Region läuft ein Stream über die komplette `chunks`-Map – alle 10 s, O(Regionen × alle Chunks).
- **Aufwand:** Klein · **Impact:** Klein
- **Ansatz:** Zusätzlich `Map<Long, Set<Long>> chunksJeRegion` pflegen (in `scan()` und `region()`).

## 🚀 Neue Features

### Strukturen aus dem Seed
- **Beschreibung:** Die Seed-Karte zeigt Biome und Gelände, aber keine Dörfer, Außenposten, Monumente oder Festungen – das, wofür man Chunkbase meistens öffnet (`ponytail:` in `SeedKarte`).
- **Aufwand:** Groß · **Impact:** Groß
- **Ansatz:** `ChunkGeneratorStructureState` aus `RandomState` + StructureSets der `VanillaRegistries`; je Chunk `StructurePlacement.isStructureChunk(...)` und Biom-Prüfung am Chunk-Mittelpunkt (wie Chunkbase), als Symbole über die Karte zeichnen und filterbar machen.

### Seed-Gelände auch auf der Minimap
- **Beschreibung:** Unerkundetes zeigt auf der Minimap nur `0xFF101014`, obwohl `SeedKarte` die Daten schon hat.
- **Aufwand:** Mittel · **Impact:** Mittel
- **Ansatz:** In `Minimap.bild()` bei `c == 0` auf ein CPU-Abbild der Seed-Kacheln zurückgreifen (die `NativeImage`s statt nur Texture-IDs behalten).

### Wegpunkt-Manager und Wegpunkt-Strahlen
- **Beschreibung:** Wegpunkte lassen sich nur setzen/löschen und heißen immer „Wegpunkt N“ (`Vollbild.freierName()`); keine Umbenennung, keine Farbe, keine Liste, keine Anzeige in der Welt.
- **Aufwand:** Mittel · **Impact:** Mittel
- **Ansatz:** `Page.open` mit `Widgets.list` (Name, Entfernung, Löschen, Farbe), Umbenennen über `remove` + `add`; in `Karte.world()` `Render.beam` + `Render.text3d` für eigene Wegpunkte der Dimension; Todespunkt per `Events.onDeath`.

### Karte als Bild exportieren
- **Beschreibung:** JourneyMaps „Save map to file“ fehlt; alle Kacheln liegen aber schon als PNG vor.
- **Aufwand:** Klein · **Impact:** Klein
- **Ansatz:** Menüeintrag in `Vollbild.oeffneMenue()`: sichtbaren Bereich aus den Kachel-PNGs zu einer Datei unter `AddonConfig.dir("karte")/export/` zusammensetzen, Pfad per `Notify` melden.

## ✅ Tests

### Höhlen-Regionsdatei: Speichern → Laden
- **Beschreibung:** `Hoehlen` hat ein eigenes Binärformat (gzip, `short`-Starts, Decken, Farben); kein Test prüft, dass `save()` und `region()` symmetrisch sind.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** Im `Testing`-Block: `scan` eines Chunks, `save()`, neue `Hoehlen` auf denselben Ordner, `farbe(x, z, y)` für ein paar Höhen vergleichen.

### Seed-Abgleich als echter Test statt nur Log
- **Beschreibung:** `runTest` startet einen flachen Testserver (`level-type=minecraft:flat` in `build.gradle`), dort lässt sich `SeedKarte` gar nicht gegen echte Generierung prüfen – darum ist der Höhenfehler erst im Spiel aufgefallen.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** `startTestServer` mit Normalwelt und festem Seed (`level-seed=42`), dann Biome **und** Höhen an 10 Punkten per `Testing.check` vergleichen.

### Reine Hilfsfunktionen direkt prüfen
- **Beschreibung:** `Kartendaten.night()`, `topo()`, `gradient()`, `darker()`, `Ansicht.Rand.klemmen()` und `SeedKarte.hoehe()` (bilineare Interpolation inkl. Zeile −1) sind reine Funktionen ohne Prüfung.
- **Aufwand:** Klein · **Impact:** Klein
- **Ansatz:** Ein paar `Testing.check`-Zeilen mit festen Werten im bestehenden Testblock von `Karte.join()`.

## 📖 Dokumentation & Developer Experience

### Testcode aus `Karte.join()` herausziehen
- **Beschreibung:** `Karte.join()` ist zu ~70 % Selbsttest (Mobs spawnen, Seed 42, Zombie-Gesicht …); dazu laufen im echten Spiel `System.out`-Ausgaben und der Abgleich bei jedem Betreten.
- **Aufwand:** Klein · **Impact:** Klein
- **Ansatz:** Eigene Klasse `Selbsttest` mit `static void start(...)`; `abgleich()` nur mit Einstellung „Diagnose“ oder in `Testing.active()`.

### README fürs Projekt
- **Beschreibung:** Keine README; Tastenbelegung, Speicherorte (`client-modules/karte/<welt>/<dim>/…`, `hoehlen/*.bin`, `seedkarte/`) und die bewussten Grenzen stehen nur in Code-Kommentaren.
- **Aufwand:** Klein · **Impact:** Klein
- **Ansatz:** Kurze `README.md`: Funktionen, Tasten, Ordner, Server-Funktionen (`esp`, `freelook`, `inventory`, `seedmap`), `gradlew runTest`/`runClient`.

## 🔒 Sicherheit

Keine nennenswerten Baustellen: Das Addon braucht keine Berechtigungen, schreibt nur unter `AddonConfig.dir("karte")`, Pfadteile aus Server-Adressen und Dimensionen laufen durch `Kartendaten.clean()`, der Chunkbase-Link wird nur auf Ziffern geparst. Einziger Punkt: die Reflection auf `ModelPart.cubes` in `Gesicht` bricht bei einem Minecraft-Update still (dann nur Punkte) – ein einmaliger Log-Hinweis wäre ehrlicher als stilles Zurückfallen.

---

## Was bereits gut abgedeckt ist

- Abtast-Reihenfolge (Spirale, Uhrzeigersinn, Zeitbudget, „frisch“-Sperre) und Speichern alle 10 s inkl. Beenden ohne `unload`.
- Server-Regeln: `ServerInfo.allowed("seedmap")`, Radar ohne Durch-Wände-Effekte.
- Leistung beim Ziehen: Höhenbilder im Budget (`Hoehlen.budget`), Platzhalter (`zuletzt`), Upload erst beim Zeichnen.
- Zeichnen in echten Bildschirmpixeln (Minimap), scharfe Rechtecke statt `Draw.box`, SDL-Maustasten.
- Selbsttests im Spiel für Projektion, Spirale, Seed-Parser, Höhenansicht, Speichern und Gesichter.
