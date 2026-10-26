# Developer Log: MiniMap

| | |
|---|---|
| **Addon** | MiniMap (ID `minimap`) |
| **Version** | 1.5.1 |
| **Developer** | Minnito / Claude |
| **Platform** | Client addon for the Swift Client, API 7.1.03, Minecraft 26.3 |
| **Language / build** | Java 25 (`--release 25`), Gradle 9.4.1 (wrapper) |
| **Status** | 2026-10-26 |
| **Build** | `build/libs/minimap.jar`, 123,829 bytes, SHA-256 `e32e3e52d81b4b1a22f8ad3bf7561fd4778ec64e8a4b7bb2bbc2f8bf9485ee4b` |
| **Self-test** | `gradlew runTest`: 21 of 21 checks passed (2026-10-26, 17:16, client 7.1.03) |
| **Download** | [Release v1.5.1](https://github.com/Minnito/minimap/releases/tag/v1.5.1) (API 7.1.03). Older releases up to v1.4.0 are built for API 6.2.1 and no longer load on clients from 7.1.03 on |

---

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
- Compass (N, E, S, W) on the border, chunk grid
- Radar: monsters, animals, pets, villagers and players, each filterable, shown as a head or a 2×2 dot
- Four info lines (two above, two below): play time, coordinates, biome, dimension, FPS, Minecraft day or light level
- Drawn in real screen pixels so it stays sharp even at high GUI scale

### World map (`J`)
- Map types and filters at the top, zoom on the left, player and mouse positions at the bottom
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
- Stored via the client API (`Waypoints`). Waypoints from other addons are shown but cannot be edited

### Other
- German and English; other languages fall back to English. Numbers use the game language's format (458,9 or 458.9)
- Settings split into short sub-pages (Minimap, Creatures & Waypoints, Info lines, World map & Chunkbase, Waypoints, Keys) so each page fits without scrolling
- All keys can be rebound

## 3. Architecture

12 classes in package `karte`, 3,023 lines in total.

| Class | Lines | Purpose |
|---|---:|---|
| `Karte` | 189 | Entry point (`client.api.Module`): keys, HUD, settings, lifecycle (`join`, `leave`, `unload`), self-test |
| `Kartendaten` | 529 | Scans loaded chunks and paints one pixel per block into tiles of 256 × 256 blocks, separated by layer. Order: rings around the player, nearest first. Time budget 4 ms per tick. All file reading and writing runs on one background thread (`minimap-dateien`) |
| `Hoehlen` | 287 | Cave data for any Y level: per block column, the floors (solid block with air above) with color and ceiling. Any height can be derived from this instantly |
| `Minimap` | 298 | Composes the minimap image on the CPU from the tiles; only recomputes when something changed |
| `Vollbild` | 500 | World map: controls, buttons, right-click menu, cross-fade, seed map as underlay |
| `SeedKarte` | 477 | Biomes and terrain height from the seed via `MultiNoiseBiomeSource` / `NoiseBasedChunkGenerator`; thread pool, PNG cache, comparison against the real world |
| `Ansicht` | 248 | World ↔ screen conversion (center, scale, rotation); shared drawing of waypoints, radar and player arrow |
| `Gesicht` | 115 | Creature face from its own texture: model part `head`, largest cube, front face. Works for all creatures without custom images |
| `Wegpunkte` | 222 | In-world waypoints and the manager |
| `Biomfarben` | 38 | Biome colors as in Chunkbase/Amidst; unknown biomes get a fixed color derived from their name |
| `Umzug` | 101 | One-time migration from the old ID `karte` to `minimap` (settings, folders, key bindings, waypoint owner) |
| `T` | 19 | Translations from `assets/minimap/lang/` |

**Data flow:** `Kartendaten` (and `Hoehlen` inside it) scan chunks every tick within the time budget → tiles as `NativeImage` + GPU texture → `Minimap` and `Vollbild` draw from them via `Ansicht`. `SeedKarte` supplies the computed underlay beneath the explored map.

### Storage locations

| What | Where |
|---|---|
| Map tiles | `client-modules/minimap/<world>/<dimension>/<layer>/<x>_<z>.png` |
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

`gradlew runTest` starts a local vanilla server (port 25598, seed 42, offline mode) and the game with the addon. The addon checks itself via `client.api.Testing`. Result from 2026-10-26 (check names are output by the addon in German, translated in brackets):

```
PASS Drehung hin und zurück                              [rotation round trip]
PASS Norden oben, Osten rechts                           [north up, east right]
PASS Kacheln, Spirale beginnt beim Spieler               [tiles, spiral starts at the player]
PASS Nächster Ring zuerst, im Uhrzeigersinn ab Norden    [nearest ring first, clockwise from north]
PASS Einstellungen: Hauptseite + kurze Unterseiten       [settings: main page + short sub-pages]
PASS Umzug: Wegpunkte von „karte" gehören jetzt „minimap"  [migration: waypoints now owned by minimap]
PASS Übersetzungen geladen (de_DE: Minimap anzeigen)     [translations loaded]
PASS Wegpunkt anlegen, Symbol „T", löschen, Entfernung „458,9m"  [create waypoint, symbol, delete, distance]
PASS Seed aus Chunkbase-Link lesen                       [read seed from Chunkbase link]
PASS Biom aus Seed berechnet (dark_forest)               [biome computed from seed]
PASS Gelände aus Seed gemalt (854 ms je Kachel)          [terrain painted from seed, 854 ms per tile]
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
```

The GitHub Action (`.github/workflows/build.yml`) only builds the jar on every push. `runTest` needs OpenGL and therefore runs locally.

## 7. Known limitations

These shortcuts are deliberate and marked with `ponytail:` in the code:

- **Tiles stay in memory.** All seen tiles stay loaded until the world changes (256 KB per tile and layer). For very large maps, tiles out of view should be unloaded (`Kartendaten`).
- **Seed map without structures.** Trees, villages, buildings and lakes above sea level are missing because they would require full chunk generation (`SeedKarte`).
- **Minimap rebuild.** With a large minimap and high GUI scale, up to 512² pixels per rebuild, but only when moving by at least 1 pixel (`Minimap`).
- **Face cache.** If a creature changes its variant, this only shows after a world change (`Gesicht`).

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
