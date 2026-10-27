# Ideen-Report: MiniMap 1.7.0

*Erstellt am 27.10.2026 · Basierend auf einem vollständigen Scan von `D:\Builds\Client\Module\karte` (13 Klassen / 3.456 Zeilen Java, `module.json`, Sprachdateien, `build.gradle`, README, alter Report `ideen-report-karte.md`) plus den Signaturen der Client-API 7.1.03*

> **Stand:** umgesetzt in MiniMap 1.8.0 (siehe README, „What's new in 1.8.0“). Nach dem Ausprobieren wieder entfernt: Kompassleiste, Wegpunkt-Pfeile am Rand und Oberwelt-Wegpunkte im Nether. `Faults.add` ist dem Client vorbehalten – Fehlermeldungen laufen über `Notify`.

## Projektübersicht

Client-Addon (API 7.1.03, MC 26.3, Java 25) im Stil von JourneyMap/Xaero. `Kartendaten` tastet Chunks im 4-ms-Budget ab und malt Tag/Nacht/Topo-Kacheln, `Hoehlen` speichert je Blocksäule die Böden für die Höhlenansicht auf jeder Y-Höhe, `Minimap` baut ihr Bild auf der CPU, `Vollbild` ist die Weltkarte mit Y-Slider, Übersichtsstufe und Seed-Unterlage, `SeedKarte` rechnet Biome und Gelände aus einem Chunkbase-Seed, `Wegpunkte` macht Manager, Anzeige in der Welt und Todespunkt. Der alte Report ist zum größten Teil umgesetzt. Dieser hier sucht nur Neues.

## Top 5 Empfehlungen

1. **Höhlendaten-Speicher begrenzen**: `Hoehlen.chunks` wächst ohne Ende, und `save()` läuft alle 10 s über *alle* Chunks. Auf lange Sicht ist das der größte Speicherfresser im Addon, und die `ponytail:`-Notiz erfasst ihn nicht.
2. **Chunkbase-Link nicht pro Kachel und Bild von der Platte lesen**: Im Einzelspieler liest die Weltkarte in jedem Bild für jede sichtbare Kachel `chunkbase.txt`. Das ist ein Einzeiler-Fix für die Bildrate auf der Weltkarte.
3. **Mehrere Welten auf einem Server trennen (Proxy, Lobby)**: Lobby und Survival hinter derselben Adresse malen sich gegenseitig über die Karte.
4. **Seed im Einzelspieler automatisch nehmen**: Den Seed kennt das Addon schon (`abgleich()`). Trotzdem bleibt die Seed-Karte ohne Chunkbase-Link leer.
5. **Wegpunkte außerhalb des Bildes anzeigen**: Ein Wegpunkt hinter dir wird übersprungen (`if (s == null) continue;`), und `Hud.arrow` / `Hud.compass` gibt es in der API schon.

---

## 🚀 Neue Features

### Seed im Einzelspieler automatisch
- **Beschreibung:** Ohne Link in `chunkbase.txt` nimmt die Seed-Karte im Einzelspieler `sp.overworld().getSeed()`.
- **Warum sinnvoll:** `SeedKarte.abgleich()` holt sich diesen Seed schon für das Log, aber `textur()` kennt nur `seed(link())`. Wer im Einzelspieler spielt, muss den Seed seiner eigenen Welt bisher von Hand eintragen.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** Neue Methode `SeedKarte.aktiverSeed()`: Link-Seed, sonst der Seed des integrierten Servers. `status()` zeigt dann „aus der Welt“. Das passt zu Idee „Link-Cache“ unten, beides läuft über dieselbe Stelle.

### Seed-Check auf Servern: „passt der Seed zur Welt?“
- **Beschreibung:** Clients bekommen den gehashten Seed (`BiomeManager`, Feld `biomeZoomSeed`). `BiomeManager.obfuscateSeed(seed)` aus dem eingegebenen Link muss genau diesen Wert ergeben. Damit zeigt `SeedKarte.status()` ✓ oder „Seed passt nicht zu diesem Server“.
- **Warum sinnvoll:** Zurzeit legt die Seed-Karte bei einem falschen Seed stillschweigend eine falsche Unterlage unter die Weltkarte. Der Vergleich ist exakt, nicht ±2 wie bei `vergleich()`.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** Das Feld per Reflection lesen, wie `Gesicht.wuerfel()` das schon macht, und das Ergebnis je Seed merken. Den Feldnamen vorher im MC-26.3-Jar prüfen.

### Höhe auf dem Radar (oben/unten)
- **Beschreibung:** Kreaturen, die deutlich über oder unter dir sind, werden blasser gezeichnet oder bekommen ein kleines ▲/▼.
- **Warum sinnvoll:** `Ansicht.radar()` beachtet Y gar nicht. Ein Creeper in der Höhle 30 Blöcke unter dir sieht genauso aus wie einer neben dir. Gerade mit der Höhlenkarte ist das irreführend.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** In `radar()` `dy = e.getY() - mc.player.getY()` rechnen. Ab |dy| > 8 die Alpha-Farbe von `Draw.image` bzw. vom Punkt senken. Gesichter bekommen schon `0xFFFFFFFF` als Tönung, die lässt sich einfach austauschen.

### Overworld-Wegpunkte im Nether (÷ 8)
- **Beschreibung:** Im Nether zeigen Minimap und Weltkarte die Overworld-Wegpunkte zusätzlich gedimmt an x/8, z/8. Das Rechtsklick-Menü der Weltkarte bekommt „Nether-/Overworld-Koordinaten kopieren“.
- **Warum sinnvoll:** `Wegpunkte.hier()` filtert strikt nach Dimension. Portale zu verbinden ist der häufigste Grund, eine Karte im Nether zu öffnen.
- **Aufwand:** Klein–Mittel · **Impact:** Mittel
- **Ansatz:** `sichtbarHier` um umgerechnete Kopien ergänzen, gekennzeichnet über ein eigenes Feld oder eine eigene Liste. Diese Kopien darf `eigenerWegpunkt()` nicht als löschbar erkennen.

### Koordinaten aus dem Chat als Wegpunkt
- **Beschreibung:** Steht in einer Chatnachricht ein Koordinatentripel (`123 64 -456`, `x: 123 z: -456`), erscheint `Notify.action(…, new Button("Als Wegpunkt", …))`.
- **Warum sinnvoll:** Die Weltkarte *schickt* schon Koordinaten in den Chat (`menue.chat`). Die Gegenrichtung fehlt, dabei teilen Mitspieler ihre Wegpunkte genau so.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** `Module.chat(Component)` in `Karte` überschreiben, mit einem reinen Parser `Wegpunkte.koordinaten(String)` (gleich testbar, siehe Tests). Nur die eigenen Nachrichten weglassen.

## ⚡ Performance

### Chunkbase-Link je Bild einmal statt je Kachel lesen
- **Beschreibung:** `Vollbild.zeichne()` ruft für jede sichtbare Kachel `seedKarte.textur()` auf. Das wiederum ruft `seed(link())` auf, und `link()` macht im Einzelspieler `Files.exists` + `Files.readString` auf dem Render-Thread. Bei Herauszoomen mit 20–40 Kacheln sind das genauso viele Dateizugriffe pro Bild, auch ganz ohne Link (dann nur `exists`). `anzeigen()` liest ein weiteres Mal.
- **Warum sinnvoll:** Das ist die einzige Platten-I/O, die noch im Zeichenpfad übrig ist. 1.4.0 hat alle anderen herausgeholt.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** In `SeedKarte` den Link merken (statisches Feld plus Welt, für die er gilt), `setzeLink()` setzt ihn neu, `Karte.leave()` leert ihn. Der Text ändert sich nur über die Einstellungsseite.

### Höhlendaten nach Region speichern und entladen
- **Beschreibung:** `Hoehlen.chunks` ist eine flache `HashMap<Long, Chunk>`, aus der nie etwas entfernt wird. `region()` lädt außerdem ganze Regionen (bis 1.024 Chunks), sobald die Weltkarte sie streift. `save()` geht alle 10 s über *alle* Einträge, nur um die geänderten Regionen herauszusuchen.
- **Warum sinnvoll:** Geschätzt 5–10 KB je Chunk (`start` 514 B plus 8 B je Boden). Ein erkundetes Gebiet von 2.000 × 2.000 Blöcken (~15.600 Chunks) kostet damit grob 100–150 MB Heap, und die Zeit für das Speichern wächst mit. Die `ponytail:`-Notiz in `Kartendaten` nennt nur die PNG-Kacheln.
- **Aufwand:** Mittel · **Impact:** Groß
- **Ansatz:** `Map<Long region, Map<Long, Chunk>>`. Dann ist `save()` O(geänderte Regionen), und Entladen ist ein einziges `remove(region)` plus `regionenGeladen.remove`, für Regionen weiter als z. B. 2 vom Spieler und nicht in `regionenGeaendert`. `farbe()`/`textur()` laden sie bei Bedarf schon heute nach.

### Quadratische Minimap über GPU-Kacheln statt CPU-Bild
- **Beschreibung:** Im Quadrat-Modus ohne Drehung die Kacheltexturen direkt mit `Draw.clip` + `Draw.image` und UV-Ausschnitt zeichnen, so wie es `Vollbild` schon macht. Das CPU-Bild bleibt für Kreis und Drehung.
- **Warum sinnvoll:** `Minimap.bild()` rechnet bei jeder Bewegung um ≥ 1 Bildpunkt bis zu 512² Pixel neu, und zusätzlich bei jedem `daten.version()`. Das steigt bei *jeder* geänderten Kachel irgendwo, auch außerhalb der Minimap. Beim Laufen ist das praktisch jedes Bild.
- **Aufwand:** Mittel · **Impact:** Mittel
- **Ansatz:** Erst mit `Profiler.section("minimap.bild", …)` messen (siehe DX), während du läufst, nicht im Stand. Lohnt sich nur, wenn es deutlich über den gemessenen 220–260 µs liegt.

## 🛡️ Fehlerbehandlung & Robustheit

### Mehrere Welten hinter einer Server-Adresse
- **Beschreibung:** Der Ordner ist `worldName()` = `server.ip` + Dimension. Wechselt ein Proxy (Velocity/Bungee) von der Lobby in den Survival-Server, bleibt der Pfad gleich (`minecraft:overworld`). `Kartendaten.tick()` merkt keinen Wechsel, also kein `close()`, und `gescannt`, Kacheln und Höhlen der Lobby werden mit dem Survival-Gelände überschrieben.
- **Warum sinnvoll:** Das war bei JourneyMap/Xaero der häufigste Grund für „kaputte“ Server-Karten.
- **Aufwand:** Mittel · **Impact:** Groß
- **Ansatz:** Mindestens bei einer neuen `ClientLevel`-Instanz `close()` aufrufen. Zum Trennen einen Unterordner nach dem gehashten Seed anlegen (das gleiche Feld wie beim Seed-Check oben), z. B. `<server>/<seedhash8>/<dimension>`. Bestehende Ordner ohne Hash bleiben als Standard bestehen.

### Ungültige Config-Werte legen das Addon lahm
- **Beschreibung:** `Modus.valueOf(cfg.text("mm_modus", …))`, `Info.valueOf(…)` (4×), `Typ.valueOf(cfg.text("fs_typ", …))` und `SeedKarte.Modus.valueOf(…)` werfen bei einem unbekannten Wert eine `IllegalArgumentException` im Konstruktor. Dann lädt `Karte` gar nicht.
- **Warum sinnvoll:** Das passiert schon, sobald eine spätere Version einen Enum-Wert umbenennt oder streicht. Der Umzug `karte` → `minimap` zeigt, dass so etwas vorkommt.
- **Aufwand:** Klein · **Impact:** Mittel
- **Ansatz:** Eine Hilfsmethode `static <E extends Enum<E>> E wert(AddonConfig, String, E standard)` mit try/catch, die überall die vier `valueOf` ersetzt.

### Fehler sichtbar machen statt nur `System.err`
- **Beschreibung:** Rund 15 Stellen schreiben nur ins Log („Speichern fehlgeschlagen“, „Höhlendaten nicht gespeichert“, „Seed-Karte nicht berechenbar“). Wer spielt, erfährt nie, dass z. B. bei voller Platte nichts mehr gespeichert wird.
- **Warum sinnvoll:** Die API hat dafür `Faults.add(ClassLoader, String, Throwable)`.
- **Aufwand:** Klein · **Impact:** Klein–Mittel
- **Ansatz:** `Karte.fehler(String, Throwable)` schreibt ins Log *und* meldet an `Faults`, gedrosselt auf einmal je Meldungsart, damit eine volle Platte nicht alle 10 s meldet.

## ✅ Tests

### Seed-Hash-Check im Selbsttest
- **Beschreibung:** `obfuscateSeed(42)` muss gleich dem `biomeZoomSeed` der Testwelt sein, und `obfuscateSeed(43)` darf es nicht sein.
- **Warum sinnvoll:** Das sichert gleich zwei Ideen ab (Seed-Check, Trennung der Welten), und die Testwelt mit Seed 42 gibt es schon.
- **Aufwand:** Klein · **Impact:** Mittel

### Chat-Koordinaten-Parser
- **Beschreibung:** Eine reine Funktion mit Fällen wie `"123 64 -456"`, `"x: 12, z: -3"`, `"Preis 3 5 7 Euro"` (das soll nicht zählen?), Nachrichten mit Uhrzeit-Präfix.
- **Aufwand:** Klein · **Impact:** Klein (gehört zum Feature)

### Unbekannter Config-Wert
- **Beschreibung:** `cfg.set("mm_modus", "GIBTSNICHT")`, dann `new Minimap(cfg, daten)` darf nicht werfen und muss auf `AUTO` stehen.
- **Aufwand:** Klein · **Impact:** Klein

## 🔒 Sicherheit

Hier gibt es nichts Relevantes. Ordner- und Dateinamen laufen über `Kartendaten.clean()`, der Chunkbase-Link wird nur per Regex nach einer Zahl durchsucht, Dateien landen nur unter `client-modules/minimap` bzw. `saves/<Welt>/minimap` (Berechtigung `files`).

## 🔌 Integrationen

### Wegpunkt-Pfeil und Kompassleiste über `Hud`
- **Beschreibung:** Liegt ein Wegpunkt außerhalb des Bildes oder hinter dir, zeigt `Hud.arrow(g, pos, x, y)` am Bildschirmrand die Richtung. Optional gibt es oben eine Kompassleiste `Hud.compass(g, x, y, w, markers)` mit allen `sichtbarHier()` als `Hud.Marker`.
- **Warum sinnvoll:** `Wegpunkte.zeichne()` lässt Punkte hinter der Kamera einfach weg. Der „Wegpunkt-Strahl“ aus dem alten Report ist auch noch offen, und das hier deckt den gleichen Bedarf mit fertiger API ab.
- **Aufwand:** Klein · **Impact:** Mittel

### Wegpunkt an Freunde schicken
- **Beschreibung:** Im Wegpunkt-Editor ein Knopf „An Freund senden“ über `Friends.send(uuid, text)`. Beim Empfänger macht der Chat-Parser bzw. `Friends.onMessage` daraus das Angebot „Als Wegpunkt“.
- **Aufwand:** Klein–Mittel (wenn der Chat-Parser steht) · **Impact:** Klein–Mittel
- **Ansatz:** Ein Format, das beide Wege lesen: `[MiniMap] Name @ x y z (dimension)`.

## 📖 Dokumentation & Developer Experience

### Messpunkte dauerhaft über `Profiler`
- **Beschreibung:** `Minimap.bild()`, `Kartendaten.tick()`, `Vollbild.zeichne()` und `Hoehlen.textur()` in `Profiler.section("minimap.…", …)` packen.
- **Warum sinnvoll:** Laut README liefen die Messungen für 1.4.0 und 1.7.0 über *temporäre* Hooks, die danach wieder entfernt wurden. Mit `Profiler` bleiben sie und kosten nichts, solange der Profiler aus ist.
- **Aufwand:** Klein · **Impact:** Klein–Mittel

### Veraltete Kommentare in `Kartendaten`
- **Beschreibung:** Der Kopfkommentar spricht noch von „Höhle je 16er-Schicht“, „~27 Ebenen“ und `client-modules/karte/…`. Heute gibt es 3 Kachel-Ebenen, die Höhlen liegen in `Hoehlen` auf jeder Y-Höhe, die ID ist `minimap`. Das gilt genauso für die Javadoc von `weltOrdner()` („saves/<Welt>/karte“).
- **Aufwand:** Klein · **Impact:** Klein

---

## Aus dem alten Report noch offen (nicht neu gezählt)

Seed-Threads (`SeedKarte.rechner`) bei `unload()` beenden (hält über `generator` auch die Registries und damit den Classloader fest), Strukturen aus dem Seed, Seed-Gelände auf der Minimap, Karte als Bild exportieren (`Screenshots.png(NativeImage)` gibt es in der API), Höhlen-Region speichern → laden als Test, Testcode aus `Karte.join()` herausziehen.

## Was bereits gut abgedeckt ist

- Datei-I/O vom Render-Thread weg, atomares Schreiben über `.tmp`, ein gemeinsamer Datei-Thread, damit Lesen nicht vor dem Speichern läuft
- Generationszähler gegen späte Ladeergebnisse nach einem Weltwechsel (`Kartendaten`, `Hoehlen`, `SeedKarte`)
- Leere Platzhalter-Chunks werden übersprungen (1.5.1), Todespunkt-Race nach dem Respawn (1.7.0)
- Übersichtsstufe (LOD) und Rechenbudgets je Bild für Höhlen und Übersichten
- Seed-Cache mit Version, Seed-Abgleich als echter Test (25/25 Punkte)
- Kontrast als automatischer Test (WCAG ≥ 4,5:1), Übersetzungen de/en mit identischen Schlüsseln
- Eindeutige Weltnamen im Einzelspieler, Umzug von der alten ID inklusive Tastenbelegung und Wegpunkt-Besitzer
