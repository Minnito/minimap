# Developer Log: MiniMap

| | |
|---|---|
| **Addon** | MiniMap (ID `minimap`) |
| **Version** | 1.8.0 |
| **Developer** | Minnito / Claude |
| **Platform** | Client addon for the Swift Client, API 7.1.03, Minecraft 26.3 |
| **Language / build** | Java 25 (`--release 25`), Gradle 9.4.1 (wrapper) |
| **Status** | 2026-10-27 |
| **Build** | `build/libs/minimap.jar`, 146,006 bytes, SHA-256 `dcf75af36879128bb96a4f5c5d4959aa779384e363051c8c75965de7df8b4a6b` |
| **Self-test** | `gradlew runTest`: 33 of 33 checks passed (2026-10-27, 01:15, client 7.1.03) |
| **Download** | [Release v1.8.0](https://github.com/Minnito/minimap/releases/tag/v1.8.0) (API 7.1.03). Older releases up to v1.4.0 are built for API 6.2.1 and no longer load on clients from 7.1.03 on |

---

## What's new in 1.8.0

From the ideas report `ideen-report-minimap.md`, adjusted after trying it in the game (see the last point under "New").

### New
- **Seed map in singleplayer without a link.** Without a Chunkbase link, the seed map uses the world's own seed.
- **Seed check on servers.** Servers send every client a hash of the world seed. The Chunkbase settings page now says whether the entered seed matches this world ("matches this world" / "does NOT match this world").
- **Height on the radar.** Creatures more than 6 blocks above or below you get darker; more than 20 blocks away they are not shown at all (cave below you, sea floor under your boat).
- **One creature filter for both maps.** What the world map's "Show" list or the "Creatures & Waypoints" page switches (creature types, heads or dots, waypoints, names) applies to the minimap and the world map alike. The world map's existing settings are kept and now also apply to the minimap.
- **Nether coordinates.** The world map's right-click menu can copy Nether coordinates (÷ 8) or Overworld coordinates (× 8). Waypoints themselves stay in their dimension: an Overworld waypoint shows only in the Overworld.
- **Coordinates from chat.** When a chat message contains a location (`123 64 -456`, `x: 12 z: -3`, or the MiniMap share format), a notification offers "Add as waypoint". Your own messages, times and dates do not count.
- **Send a waypoint to a friend** from the waypoint editor via the client's friend list; the friend gets the same "Add as waypoint" offer. Needs the new permission `friends`; without it, MiniMap still runs without this feature.
- The switch for chat coordinates is in the waypoint manager.
- Built and removed again after trying them: edge arrows to off-screen waypoints (they got in the way), a compass bar (the client has its own compass) and Overworld waypoints shown in the Nether at x/8, z/8 (waypoints stay in their dimension).
- **Waypoint manager by dimension.** A "Dimension: …" button with "Switch ›" cycles the list through Overworld, The Nether and The End (plus any other dimension that has waypoints, e.g. from datapacks). It opens on the dimension you are in. Waypoints of another dimension are sorted by name instead of distance. Their symbol, visibility and deletion can be changed from anywhere; name, color and position only in their own dimension, because the client API always sets waypoints in the current dimension. It is a button rather than a choice because the client rebuilds `Page.open` pages only after a button, not after a choice or toggle.

### Robustness
- **Several worlds behind one server address** (proxy: lobby, survival …) now each get their own folder `<server>/welt_<seed hash>`. They used to paint over each other's map. A new world instance at the same folder also starts a fresh scan. Maps from before 1.8.0 move into the folder of the first world you join there.
- **Unknown config values** (renamed or edited by hand) fall back to the default instead of stopping the addon from loading.
- **Errors that can lose data** (map tile, cave data, Chunkbase link or seed tile not saved) now also show once per kind as a notification, not only in the log. The client's `Faults` is reserved for the client itself, so this goes through `Notify`.

### Faster
- **The world map no longer reads `chunkbase.txt` from disk** for every visible tile in every frame (singleplayer). Link and seed are read once per world.
- **Cave data is kept per region, and unused regions are unloaded.** Regions nobody needed for a minute (not the scan, the minimap or the world map) leave memory; they come back from disk when needed. Saving only goes through changed regions instead of all chunks every 10 s.
- **The square minimap draws the map tiles directly on the GPU** like the world map, in real screen pixels, instead of rebuilding up to 512² pixels on the CPU whenever you move. The circle (optionally rotated) stays on the CPU.
- Timing points for the client profiler: `minimap.abtasten`, `minimap.kacheln`, `minimap.bild`, `minimap.hoehlenbild`, `minimap.weltkarte`.

## What's new in 1.7.0

### New look
- **One typeface.** All text on the minimap and world map uses the client's menu font (`Draw.ui`) instead of a mix of the pixel font and the menu font.
- **Clear contrast.** Dark panels with light text (16.2:1) and muted text (7.7:1). The accent is the client's HUD color; if that is white, grey or a gradient, the pink of the client menus. Text on the accent uses whichever color has the higher contrast (on the pink: dark, 5.6:1). The self-test fails if any of these drops below 4.5:1.
- **Minimap:** solid frame with a light shadow, compass with "N" in the accent color, info lines on one shared plate instead of single black boxes.
- **World map:** map type as one segmented control at the top left. The nine filters moved behind a "Show" button as a list with check boxes (filled = on, not color alone). Follow and zoom as a compact bar with drawn icons, positions in two panels at the bottom, right-click menu and tooltips in the same style.
- All colors and the font live in one place: `Stil`.

### Faster
Measured in the self-test, per frame. Minimap: 290–400 µs before, 220–260 µs now. Info lines, visible waypoints and radar candidates are now built once per tick instead of every frame (waypoints 48 → 8 µs, info lines 50 → 18 µs, radar ~90 → ~73 µs); the menu font costs about 20 µs of that back. World map controls: 355 → 160–180 µs.

### Bug fixes
- **Death point could vanish right away.** Right after dying, the client sometimes still reports the player as alive for a moment. If the arrival check ran then, it deleted the new death point, because you are standing on it. Arrival now only counts after respawning.
- **Self-test drifted.** The death point test left the test player 60 blocks further away on every run (the test world is kept), which sometimes broke the seed comparison. The player now returns to the start.

## What's new in 1.6.0

- **Death point.** When you die, a red waypoint "Tod" / "Death" is set where you died. There is only one: a new death replaces it. Once you get within 4 blocks of it, it disappears by itself. It belongs to MiniMap, so you can edit or delete it in the manager like any other waypoint; renamed, it becomes a normal waypoint and stays. Both can be switched off in the waypoint manager ("Death point", "Delete death point on arrival").

## What's new in 1.5.1

- **Black squares on the cave map are gone.** Servers sometimes send a chunk empty first (placeholder, flying fast). The cave scan took it anyway: without a single floor every column counted as solid stone (`FEST`, almost black), and it replaced good data saved for that chunk. On the world map these squares added up to black bars and clusters. Empty chunks are now skipped and scanned again later, so saved data is never overwritten by an empty chunk. Black areas already saved stay until the chunk is scanned again.

## What's new in 1.5.0

- **Runs on API 7.1.03.** Clients from 7.1.03 on no longer load addons built for older APIs. `module.json` now has `"api": "7.1.03"` and `"minecraft": ["26.3"]`, and `build.gradle` follows the 7.1.03 template (the seed-42 test world stays). No code changes were needed: every client API call still exists. `runTest` passed 21 of 21 checks.

## What's new in 1.4.0

### Bug fixes
- **Short freezes every 10 seconds are gone.** Saving map tiles (PNG) and cave data (gzip) used to run on the render thread. With a normal render distance that meant several hundred milliseconds of freeze every 10 s. Now only a pixel snapshot is taken on the render thread (memcpy); encoding and writing run in the background. Measured in the self-test: **50 ms → 1.4 ms** on the render thread, with twice the number of tiles.
- **Hitches when crossing tile borders and on the world map are gone.** Map tiles (~1–2 ms each) and cave regions (2–6 ms each) were read synchronously when entering a new 256-block tile, and for every unloaded tile visible on the world map (an overview needs 16). They now load in the background and appear a few frames later. Measured tick spikes: **25 ms → 9.5 ms** (the rest is the scan budget plus JIT warm-up right after joining).
- **Cave data compression was very slow.** `DataOutputStream` wrote directly into `GZIPOutputStream`, so every single `writeShort` went to the deflater (same when reading). A buffer now sits in between.
- **Saving cave regions searched all chunks once per region.** Chunks are now grouped by region in one pass.
- **Half-written map files.** Tiles and cave data are written to a `.tmp` file first and then replaced atomically, so a crash or a quick rejoin never reads a half-written file. One shared file thread for reading and writing guarantees that a file is never read before its queued save has finished.
- **Waypoints in the world grew relative to the landscape and covered a lot of the view.** They were drawn at GUI scale, so with a high GUI scale they were huge, and since their size never changed they covered ever more landscape with distance.

### Changes
- **Waypoints at a fixed size like Xaero:** small, in real screen pixels, independent of distance and GUI scale (`Wegpunkte.GROESSE`, default 1.5 screen pixels per font pixel).
- When the addon is unloaded, it waits until all queued files are written.

---

## 1. Goal

A minimap and world map in the style of JourneyMap and Xaero's Minimap that runs directly in the launcher's client, without Fabric or Forge:

- Minimap and world map built from the same map data, collected in the background
- Map types Day, Night, Topo and Cave, with the cave view at **any** Y level
- Entity radar with real creature faces
- Waypoints with a manager and in-world display
- Seed map from a Chunkbase link: biomes and terrain computed by Minecraft's own generator, so it matches both Chunkbase and the actual world

## 2. Features

### Minimap (`M` toggles)
- Square or circle; the circle can optionally rotate with your view direction
- Adjustable size, 7 zoom levels (0.125 to 8 GUI pixels per block, keys `+` / `-`)
- Map type Auto, Day, Night, Topo or Cave (key `[`). Auto uses Cave underground and Night at night
- Compass (N, E, S, W) on the frame, N in the accent color; chunk grid
- Radar: monsters, animals, pets, villagers and players, each filterable, shown as a head or a 2×2 dot
- Four info lines (two above, two below, each group on one plate): play time, coordinates, biome, dimension, FPS, Minecraft day or light level
- Drawn in real screen pixels so it stays sharp even at high GUI scale

### World map (`J`)
- Map type at the top left, "Show" list (creatures, heads, waypoints, grid, seed map) at the top right, follow and zoom on the left, player and mouse positions at the bottom
- Y slider for the cave view; switching layers cross-fades over 220 ms
- Hold left mouse button = pan, mouse wheel = zoom, arrow keys = pan 16 blocks, space = back to you, `F` = follow you, `C` = copy coordinates, `B` = waypoint at the mouse
- Right-click menu: set or delete a waypoint, manage waypoints, copy coordinates or send them to chat, center here, show distance
- Overview level (4 × 4 tiles each) for zooming far out

### Seed map
- Seed from a Chunkbase share link (`…#seed=…&platform=java_…`) or as a plain number
- Two modes: terrain (vanilla map colors, height shading, water depth, forests as canopy) and a biome map in Chunkbase colors
- Computed in the background (up to 4 threads) and cached as PNG, per world or server
- Servers can disable it via the `seedmap` feature

### Waypoints (`B` = set here, `N` = manager)
- Shown in the world as a small colored square with symbol and name, at a fixed size in real screen pixels like Xaero (independent of distance and GUI scale); distance shown when you look at it (setting: when looking, always, never)
- Manager with list, new, edit, delete; custom symbol (1 to 2 characters) and visibility per waypoint
- Death point: set automatically on death, deleted on arrival (both can be switched off in the manager)
- Stored via the client API (`Waypoints`). Waypoints from other addons are shown but cannot be edited

### Other
- German and English; other languages fall back to English. Numbers use the game language's format (458,9 or 458.9)
- Settings split into short sub-pages (Minimap, Creatures & Waypoints, Info lines, World map & Chunkbase, Waypoints, Keys) so each page fits without scrolling
- All keys can be rebound

## 3. Architecture

13 classes in package `karte`, 3,733 lines in total.

| Class | Lines | Purpose |
|---|---:|---|
| `Karte` | 337 | Entry point (`client.api.Module`): keys, HUD, settings, lifecycle (`join`, `leave`, `unload`), self-test |
| `Kartendaten` | 580 | Scans loaded chunks and paints one pixel per block into tiles of 256 × 256 blocks, separated by layer. Order: rings around the player, nearest first. Time budget 4 ms per tick. All file reading and writing runs on one background thread (`minimap-dateien`) , folder per world (seed hash) on servers |
| `Hoehlen` | 326 | Cave data for any Y level: per block column, the floors (solid block with air above) with color and ceiling. Any height can be derived from this instantly . Kept per region; unused regions leave memory after a minute |
| `Minimap` | 354 | Square: draws the tiles on the GPU; circle: composes the image on the CPU, only when something changed. Frame, compass and info plates |
| `Vollbild` | 576 | World map: segmented map type, "Show" list, zoom bar, Y slider, right-click menu, cross-fade, seed map as underlay |
| `SeedKarte` | 529 | Biomes and terrain height from the seed via `MultiNoiseBiomeSource` / `NoiseBasedChunkGenerator`; thread pool, PNG cache, comparison against the real world , seed check against the world's seed hash |
| `Ansicht` | 273 | World ↔ screen conversion (center, scale, rotation); shared drawing of waypoints, radar and player arrow |
| `Gesicht` | 115 | Creature face from its own texture: model part `head`, largest cube, front face. Works for all creatures without custom images |
| `Wegpunkte` | 412 | In-world waypoints, the manager, the death point, sharing via chat and friends |
| `Biomfarben` | 38 | Biome colors as in Chunkbase/Amidst; unknown biomes get a fixed color derived from their name |
| `Umzug` | 101 | One-time migration from the old ID `karte` to `minimap` (settings, folders, key bindings, waypoint owner) |
| `Stil` | 73 | Colors, font and contrast of the map UI in one place; accent from the client's HUD color |
| `T` | 19 | Translations from `assets/minimap/lang/` |

**Data flow:** `Kartendaten` (and `Hoehlen` inside it) scan chunks every tick within the time budget → tiles as `NativeImage` + GPU texture → `Minimap` and `Vollbild` draw from them via `Ansicht`. `SeedKarte` supplies the computed underlay beneath the explored map.

### Storage locations

| What | Where |
|---|---|
| Map tiles | `client-modules/minimap/<server>/welt_<seed hash>/<dimension>/<layer>/<x>_<z>.png` (servers) |
| Singleplayer | inside the world folder under `<world>/minimap/` (permission `files`), so the map travels with the world |
| Cave data | `<dimension>/hoehlen/<rx>_<rz>.bin` per region (32 × 32 chunks), gzip |
| Seed cache | `seedkarte/<seed>_<dim>_<mode>_x<scale>_v2/` as PNG |
| Settings | `client-modules/minimap.properties` (client API `AddonConfig`) |

World name: server = address (`localhost_25598`), singleplayer = `sp_` + the world's folder name. Two worlds with the same display name therefore no longer collide.

## 4. History

All work happened on 2026-10-25, from onboarding to the finished 1.3.0 build and the 1.4.0 performance and waypoint update.

1. **Onboarding (approx. 00:11).** Collected background knowledge on client API 6.2.1 (`kb/Grundwissen-Client-Module.md`, original docs in `kb/eclipse-docs.txt`) and captured the project as a knowledge graph (`graphify-out/`).
2. **Scaffold (approx. 00:27).** Set up the Gradle template: `build`, `runClient`, `install`, `runTest`, `publishAddon`. The build downloads Minecraft and its libraries directly from Mojang. Without an addon server it takes the API and client from the installed launcher.
3. **Core under the ID `karte`.** Map data, minimap, world map, caves, seed map, radar with faces.
4. **Ideas report (`ideen-report-karte.md`).** Full scan with a list of bugs and ideas. The most important items have been implemented (section 5).
5. **Follow-ups (`Verbesserungen.md`, 02:08).** Noted: rework the world map icons, allow inserting the Chunkbase map. The Chunkbase map is built in (seed map from the Chunkbase link); the world map buttons have labels and tooltips.
6. **Rename to `minimap` (version 1.3.0, until 13:10).** New ID, name "MiniMap", translations moved out (de_de, en_us), settings split into sub-pages, waypoint manager. `Umzug` takes over the data of the old ID on first launch.
7. **Build and test (13:10 to 13:11).** Built `minimap.jar`, `runTest` passed 21 of 21 checks.
8. **Version 1.4.0 (evening).** Measured freezes in the self-test with temporary timing points, then moved all file reading and writing off the render thread (see "What's new in 1.4.0"). Waypoints in the world switched to a fixed size in real screen pixels after a side-by-side comparison with Xaero's Minimap.
9. **Version 1.5.0 (2026-10-26).** Clients from 7.1.03 on no longer load addons built for older APIs. `module.json` now has `"api": "7.1.03"` and `"minecraft": ["26.3"]`, and `build.gradle` follows the 7.1.03 template (the seed-42 test world stays). No code changes were needed: every client API call still exists. `runTest` passed 21 of 21 checks.
10. **Version 1.5.1 (2026-10-26).** Found the cause of black bars on the world map reported on servers: servers sometimes send a chunk empty first, and the cave scan saved it as solid stone over good data. Empty chunks are now skipped and scanned again later. `runTest` passed 21 of 21 checks.
11. **Version 1.6.0 (2026-10-26).** Death point built into MiniMap (first tried as a separate addon, then merged here so the point belongs to MiniMap and can be edited in the manager). `runTest` passed 24 of 24 checks.
12. **Version 1.7.0 (2026-10-26/27).** Redesign of minimap and world map after a review against Apple's HIG and frontend design guidelines: before/after screenshots and per-frame timings from temporary hooks in the self-test (removed again). Per-tick caching kept where it measurably helped; caching the menu-font texts was within noise and reverted. Fixed the death point race and the drifting test player. `runTest` passed 25 of 25 checks, three runs in a row.
13. **Version 1.8.0 (2026-10-27).** New ideas report (`ideen-report-minimap.md`) and all of it implemented, see "What's new in 1.8.0". The GPU-drawn square minimap was checked on a screenshot from a temporary test hook (removed again). Found on the way: `Friends` needs the permission `friends`, without it the addon did not load at all; now it loads either way.

## 5. Fixed bugs and implemented ideas

| Problem | Fix | Evidence |
|---|---|---|
| Seed terrain heights did not match the world (comparison: height ±3 at 0 of 7 points) | Reworked height computation and comparison | Test "Seed-Höhen = Welt ±2 (25/25)" |
| Old seed cache with wrongly computed tiles stayed around | Cache version `v2` in the folder name; old folders are deleted on startup | `SeedKarte.CACHE_VERSION` |
| Face cache survived world changes (cow #57 became zombie #57) | `Gesicht.leeren()` in `Karte.leave()` | Code |
| Singleplayer worlds with the same name shared one map | World name taken from the world's folder name | Test "Weltname eindeutig" |
| Zooming far out kept reloading tiles | Overview level built from 4 × 4 tiles, also for the seed map | Tests "Übersicht (4 × 4 Kacheln)", "Seed-Übersicht (1024 Blöcke)" |
| Test server was a flat world, so the seed map could not be checked | `runTest` starts a normal world with seed 42 and compares biomes and heights at 25 points | Tests "Seed-Biome = Welt", "Seed-Höhen = Welt" |
| Waypoints were always named "Wegpunkt N", with no list and no in-world display | Waypoint manager, symbols, in-world display with distance | Test "Wegpunkt anlegen, Symbol „T", löschen, Entfernung „458,9m"" |

## 6. Tests

`gradlew runTest` starts a local vanilla server (port 25598, seed 42, offline mode) and the game with the addon. The addon checks itself via `client.api.Testing`. Result from 2026-10-27 (check names are output by the addon in German, translated in brackets):

```
PASS Drehung hin und zurück                              [rotation round trip]
PASS Norden oben, Osten rechts                           [north up, east right]
PASS Kacheln, Spirale beginnt beim Spieler               [tiles, spiral starts at the player]
PASS Nächster Ring zuerst, im Uhrzeigersinn ab Norden    [nearest ring first, clockwise from north]
PASS Einstellungen: Hauptseite + kurze Unterseiten       [settings: main page + short sub-pages]
PASS Umzug: Wegpunkte von „karte" gehören jetzt „minimap"  [migration: waypoints now owned by minimap]
PASS Übersetzungen geladen (de_DE: Minimap anzeigen)     [translations loaded]
PASS Kontrast: Text, leiser Text und Schrift auf dem Akzent (E0679F, 5.6:1)  [contrast of text, muted text and text on the accent]
PASS Wegpunkt anlegen, Symbol „T", löschen, Entfernung „458,9m"  [create waypoint, symbol, delete, distance]
PASS Seed aus Chunkbase-Link lesen                       [read seed from Chunkbase link]
PASS Seed-Hash der Welt = Seed 42, Seed 43 passt nicht    [world seed hash = seed 42, seed 43 does not match]
PASS Server-Welt im eigenen Ordner (welt_c6f218bc)        [server world in its own folder]
PASS Unbekannter Config-Wert → Standard                   [unknown config value → default]
PASS Koordinaten aus dem Chat lesen                       [read coordinates from chat]
PASS Wegpunkte nur in ihrer Dimension                     [waypoints only in their dimension]
PASS Manager: Oberwelt, Nether, Ende und weitere Dimensionen zur Auswahl  [manager: Overworld, Nether, End and other dimensions to choose from]
PASS Radar: Kreaturen über/unter dir blasser, ab 20 Blöcken weg  [radar: creatures above/below you dimmer, hidden beyond 20 blocks]
PASS Biom aus Seed berechnet (dark_forest)               [biome computed from seed]
PASS Gelände aus Seed gemalt (820 ms je Kachel)          [terrain painted from seed, 820 ms per tile]
PASS Höhenansicht für jede Y-Höhe                        [view for any Y level]
PASS Karte gemalt                                        [map painted]
PASS Tag, Nacht und Topo gespeichert                     [day, night and topo saved]
PASS Höhlendaten nebenbei erfasst (49 Chunks)            [cave data collected, 49 chunks]
PASS Übersicht (4 × 4 Kacheln) fürs Herauszoomen gebaut  [overview (4 × 4 tiles) built for zooming out]
PASS Seed-Übersicht (1024 Blöcke) gerechnet              [seed overview (1024 blocks) computed]
PASS Weltname eindeutig (localhost_25598)                [world name unique]
PASS Zombie-Gesicht aus der Textur (8, 8, 8 × 8)         [zombie face from texture]
PASS Seed-Biome = Welt (25/25)                           [seed biomes = world]
PASS Seed-Höhen = Welt ±2 (25/25)                        [seed heights = world ±2]
PASS Todespunkt gesetzt                                  [death point set]
PASS Wiederbelebt, Todespunkt noch da                    [respawned, death point still there]
PASS Todespunkt beim Erreichen gelöscht                  [death point deleted on arrival]
PASS Höhlen-Region gespeichert, entladen und wieder geladen  [cave region saved, unloaded and loaded again]
```

The GitHub Action (`.github/workflows/build.yml`) only builds the jar on every push. `runTest` needs OpenGL and therefore runs locally.

## 7. Known limitations

These shortcuts are deliberate and marked with `ponytail:` in the code:

- **Tiles stay in memory.** All seen tiles stay loaded until the world changes (256 KB per tile and layer). For very large maps, tiles out of view should be unloaded (`Kartendaten`).
- **Seed map without structures.** Trees, villages, buildings and lakes above sea level are missing because they would require full chunk generation (`SeedKarte`).
- **Minimap rebuild (circle only).** With a large round minimap and high GUI scale, up to 512² pixels per rebuild, but only when moving by at least 1 pixel (`Minimap`). The square one draws tiles on the GPU.
- **Face cache.** If a creature changes its variant, this only shows after a world change (`Gesicht`).
- **Death point self-test is timing-dependent.** In 1 of 11 runs on 2026-10-27, "Todespunkt beim Erreichen gelöscht" failed: the test teleports back to the death point right after a respawn the server may not have confirmed yet. The addon's own check is unaffected; the test needs a firmer wait for the respawn.
- **World folders by seed hash.** A server that re-rolls the seed hash on every join would get an empty map each time; not seen so far (`Kartendaten`).
- **Error notifications** name the error in German (it comes from the code, not the language files); the notification text around it is translated.

Still open from the ideas report: shut down seed threads on `.addon reload`, structures from the seed, seed terrain on the minimap, export the map as an image.

## 8. Building and running

Requirement: JDK 25 or newer. The first build downloads Minecraft and its libraries once into `~/.gradle/caches/client-mc`.

```bash
./gradlew build        # → build/libs/minimap.jar
./gradlew runClient    # game with the addon, offline as "Dev", folder run/
./gradlew runTest      # test server + game, self-test
./gradlew install      # copy jar into the game; in chat ".addon reload minimap"
```

API and client come as Maven artifacts from `https://api.gta5eclipse.de/maven` (other server: `-PclientServer=…`). The build no longer falls back to the installed launcher, whose client may be older than the API in `module.json`.

## 9. Key bindings

| Key | Action |
|---|---|
| `J` | Open world map |
| `M` | Toggle minimap |
| `+` / `-` | Zoom minimap in / out |
| `[` | Switch minimap map type |
| `B` | Set waypoint here (on the world map: at the mouse) |
| `N` | Manage waypoints |
| `F`, `C`, arrow keys, space | on the world map: follow, copy coordinates, pan, back to you |
