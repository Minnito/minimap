package karte;

import client.api.AddonConfig;
import client.api.Draw;
import client.api.ServerInfo;
import client.api.Tasks;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.MapColor;

// Seed-Karte: Seed aus dem Chunkbase-Share-Link, dann mit Minecrafts eigenem Generator gerechnet – Biome UND Geländehöhe.
// Darum deckungsgleich mit Chunkbase (gleiche Version) und mit der Welt. Gemalt wie die erkundete Karte (Vanilla-Kartenfarben,
// Höhenschattierung, Wassertiefe, Wälder als Blätterdach) oder als Chunkbase-Biomkarte. Je Welt/Server gespeichert,
// gerechnete Kacheln liegen als PNG im Cache. Server können das mit der Funktion "seedmap" abschalten.
// ponytail: keine Bäume/Bauwerke/Strukturen (bräuchte die komplette Chunk-Generierung); Seen über Meereshöhe fehlen.
final class SeedKarte {
    private static final Pattern SEED = Pattern.compile("(?:seed=)?(-?\\d{1,20})");
    private static final Pattern VERSION = Pattern.compile("platform=([a-z0-9_]+)");
    static final int TILE = Kartendaten.TILE, RASTER = 4;   // Geländehöhe alle 4 Blöcke gerechnet, dazwischen weich verbunden
    private static final int THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() - 2, 1, 4);
    private static final int MAX_WARTEND = THREADS * 2, MAX_BILDER = 300;
    // Cache-Version: bei jeder Änderung an Farben/Generator erhöhen – alte Ordner werden dann beim ersten Gebrauch gelöscht
    private static final String CACHE_VERSION = "v2";
    private static boolean aufgeraeumt;

    enum Modus {
        GELAENDE, BIOME;

        String text() {
            return T.t(this == GELAENDE ? "seed.gelaende" : "seed.biome");
        }
    }

    private final ExecutorService rechner = Executors.newFixedThreadPool(THREADS, r -> {
        var t = new Thread(r, "minimap-seed");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Map<Long, Identifier> fertig = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Identifier> e) {
            if (size() <= MAX_BILDER) return false;
            Minecraft.getInstance().getTextureManager().release(e.getValue());
            return true;
        }
    };
    private final Map<Long, Boolean> wartend = new HashMap<>();
    private String welt;                 // Seed + Dimension + Modus, für die die Kacheln gelten
    private int serial, generation;      // generation: verwirft Ergebnisse alter Seeds/Dimensionen
    private static Generator generator;  // von allen Rechen-Threads geteilt
    private static String generatorFuer;

    // ───── Link / Seed je Welt ─────
    // Einzelspieler: im Weltordner (karte/chunkbase.txt) – gehört zur Welt wie die Karte. Server: je Server-Adresse in der Instanz.
    private static Path linkDatei() {
        return Minecraft.getInstance().getSingleplayerServer() == null ? null : Kartendaten.weltOrdner().resolve("chunkbase.txt");
    }

    static String link() {
        Path f = linkDatei();
        if (f != null) {                // Einzelspieler: nur die Datei der Welt – alte, namensbasierte Einträge würden neue Welten gleichen Namens erben
            if (!Files.exists(f)) return "";
            try {
                return Files.readString(f).trim();
            } catch (IOException e) {
                System.err.println("[minimap] Chunkbase-Link unlesbar: " + e);
                return "";
            }
        }
        String neu = AddonConfig.of(Karte.ID).text("chunkbase." + Kartendaten.worldName(), "");
        return neu.isEmpty() ? AddonConfig.forServer(Karte.ID).text("chunkbase", "") : neu;   // früher gespeicherte Links weiter finden
    }

    static void setzeLink(String text) {
        Path f = linkDatei();
        if (f == null) {
            AddonConfig.of(Karte.ID).set("chunkbase." + Kartendaten.worldName(), text.trim());
            return;
        }
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, text.trim());
        } catch (IOException e) {
            System.err.println("[minimap] Chunkbase-Link nicht gespeichert: " + e);
        }
    }

    /** Seed aus Link oder Zahl, null = keiner. */
    static Long seed(String text) {
        if (text == null || text.isBlank()) return null;
        var m = SEED.matcher(text.contains("seed=") ? text.substring(text.indexOf("seed=")) : text.trim());
        if (!m.lookingAt()) return null;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String status() {
        Long s = seed(link());
        if (s == null) return T.t(link().isBlank() ? "seed.kein" : "seed.keinimlink");
        var v = VERSION.matcher(link());
        String version = v.find() ? T.t("seed.chunkbase", v.group(1).replace('_', '.').replace("java.", "Java ")) : "";
        return T.t("seed.status", s) + version + (erlaubt() ? "" : T.t("seed.aus"));
    }

    static boolean erlaubt() {
        return ServerInfo.allowed("seedmap");
    }

    // ───── Kacheln ─────
    /**
     * Bild der Kachel (256 × 256 Bildpunkte), null = keins oder noch in Arbeit.
     * massstab = Blöcke je Bildpunkt: 1 = normale Kachel (256 Blöcke), 4 = Übersicht (1024 Blöcke) fürs weite Herauszoomen.
     */
    Identifier textur(int tx, int tz, Modus modus, int massstab) {
        var mc = Minecraft.getInstance();
        Long seed = seed(link());
        if (seed == null || mc.level == null || !erlaubt()) return null;
        String dim = mc.level.dimension().identifier().toString();
        String w = seed + "|" + dim + "|" + modus;
        if (!w.equals(welt)) {
            leeren();
            welt = w;
        }
        long k = Kartendaten.key(tx, tz) * 31 + massstab;
        Identifier id = fertig.get(k);
        if (id != null || wartend.containsKey(k) || wartend.size() >= MAX_WARTEND) return id;
        wartend.put(k, true);
        int gen = generation;
        Path wurzel = AddonConfig.dir(Karte.ID).resolve("seedkarte");
        if (!aufgeraeumt) aufraeumen(wurzel);
        Path datei = wurzel.resolve(seed + "_" + Kartendaten.clean(dim) + "_" + modus.name().toLowerCase() + "_x" + massstab + "_" + CACHE_VERSION)
            .resolve(tx + "_" + tz + ".png");
        rechner.submit(() -> {
            NativeImage img = laden(datei);
            if (img == null && (img = rechne(seed, dim, tx, tz, modus, massstab)) != null) {
                try {
                    Files.createDirectories(datei.getParent());
                    img.writeToFile(datei);
                } catch (IOException e) {
                    System.err.println("[minimap] Seed-Kachel nicht gespeichert: " + e);
                }
            }
            NativeImage bild = img;
            Tasks.main(() -> {
                if (gen != generation) {
                    if (bild != null) bild.close();
                    return;
                }
                wartend.remove(k);
                if (bild != null) fertig.put(k, Draw.texture(Karte.ID, "seed" + serial++, bild));
            });
        });
        return null;
    }

    void leeren() {
        generation++;
        for (var id : fertig.values()) Minecraft.getInstance().getTextureManager().release(id);
        fertig.clear();
        wartend.clear();
    }

    // Cache-Ordner älterer Versionen löschen (nur unter seedkarte/, nur Ordner ohne aktuelle Versionsendung)
    private static void aufraeumen(Path wurzel) {
        aufgeraeumt = true;
        if (!Files.isDirectory(wurzel)) return;
        try (var ordner = Files.list(wurzel)) {
            for (Path o : ordner.filter(Files::isDirectory).filter(o -> !o.getFileName().toString().endsWith("_" + CACHE_VERSION)).toList())
                try (var inhalt = Files.walk(o)) {
                    for (Path p : inhalt.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
        } catch (IOException e) {
            System.err.println("[minimap] Alter Seed-Cache nicht gelöscht: " + e);
        }
    }

    private static NativeImage laden(Path datei) {
        if (!Files.exists(datei)) return null;
        try (InputStream in = Files.newInputStream(datei)) {
            return NativeImage.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    // ───── Generator (einmal je Seed + Dimension, von allen Threads geteilt) ─────
    private record Generator(BiomeResolver biome, NoiseBasedChunkGenerator gen, RandomState zufall, LevelHeightAccessor hoehe, boolean decke) {}

    private static synchronized Generator generator(long seed, String dim) {
        String fuer = seed + "|" + dim;
        if (!fuer.equals(generatorFuer)) {
            generator = baue(seed, dim);
            generatorFuer = fuer;
        }
        return generator;
    }

    private static Generator baue(long seed, String dim) {
        var w = VanillaRegistries.createWorldLookup();
        var settings = w.lookupOrThrow(Registries.NOISE_SETTINGS);
        var presets = w.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        BiomeSource quelle;
        Holder<NoiseGeneratorSettings> noise;
        switch (dim) {
            case "minecraft:overworld" -> {
                quelle = MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
                noise = settings.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            }
            case "minecraft:the_nether" -> {
                quelle = MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
                noise = settings.getOrThrow(NoiseGeneratorSettings.NETHER);
            }
            case "minecraft:the_end" -> {
                quelle = TheEndBiomeSource.create(w.lookupOrThrow(Registries.BIOME));
                noise = settings.getOrThrow(NoiseGeneratorSettings.END);
            }
            default -> {
                return null;                                   // eigene Dimensionen: Generator unbekannt
            }
        }
        var zufall = RandomState.create(w.lookupOrThrow(Registries.NOISE), seed, noise.value());
        var gen = new NoiseBasedChunkGenerator(quelle, noise);
        var ns = noise.value().noiseSettings();
        return new Generator(quelle.createUncachedResolver(zufall), gen, zufall,
            LevelHeightAccessor.create(ns.minY(), ns.height()), dim.endsWith("the_nether"));
    }

    // ───── Malen ─────
    /** Kachel aus dem Seed malen (im Rechen-Thread). */
    static NativeImage rechne(long seed, String dim, int tx, int tz, Modus modus, int massstab) {
        try {
            Generator g = generator(seed, dim);
            if (g == null) return null;
            int k = massstab, x0 = tx * TILE * k, z0 = tz * TILE * k, n = TILE / RASTER + 1, minY = g.hoehe.getMinY(), meer = g.gen.getSeaLevel();
            // Geländehöhe (oberster fester Block + 1) auf einem Raster (alle RASTER Bildpunkte), eine Zeile nördlich mehr für die Schattierung
            int[][] raster = new int[n + 1][n];
            for (int j = 0; j <= n; j++)
                for (int i = 0; i < n; i++)
                    raster[j][i] = g.decke ? meer + 1
                        : g.gen.getBaseHeight(x0 + i * RASTER * k, z0 + (j - 1) * RASTER * k, Heightmap.Types.OCEAN_FLOOR_WG, g.hoehe, g.zufall);
            var biome = new Holder[TILE / 4][TILE / 4];
            for (int j = 0; j < TILE / 4; j++)
                for (int i = 0; i < TILE / 4; i++) {
                    int y = Math.max(hoehe(raster, i * 4 + 2, j * 4 + 2) - 1, meer - 1);
                    biome[j][i] = g.biome.getNoiseBiome((x0 + (i * 4 + 2) * k) >> 2, y >> 2, (z0 + (j * 4 + 2) * k) >> 2);
                }
            var img = new NativeImage(TILE, TILE, true);
            for (int pz = 0; pz < TILE; pz++)
                for (int px = 0; px < TILE; px++) {
                    int h = hoehe(raster, px, pz) - 1;
                    if (h <= minY) continue;                                 // Leere (End): bleibt unerkundet
                    int nord = hoehe(raster, px, pz - 1) - 1;
                    @SuppressWarnings("unchecked") Holder<Biome> b = biome[pz >> 2][px >> 2];
                    img.setPixel(px, pz, farbe(b, x0 + px * k, z0 + pz * k, h, nord, meer, g.decke, modus));
                }
            return img;
        } catch (RuntimeException e) {
            System.err.println("[minimap] Seed-Karte nicht berechenbar: " + e);
            return null;
        }
    }

    // Höhe an (px, pz) der Kachel, bilinear zwischen den Rasterpunkten; pz = −1 ist die Zeile nördlich davon
    private static int hoehe(int[][] r, int px, int pz) {
        float fx = px / (float) RASTER, fz = (pz + RASTER) / (float) RASTER;
        int i = Math.min((int) fx, r[0].length - 2), j = Math.min((int) fz, r.length - 2);
        float u = fx - i, v = fz - j;
        float oben = r[j][i] + (r[j][i + 1] - r[j][i]) * u, unten = r[j + 1][i] + (r[j + 1][i + 1] - r[j + 1][i]) * u;
        return Math.round(oben + (unten - oben) * v);
    }

    private static int farbe(Holder<Biome> b, int x, int z, int h, int nord, int meer, boolean decke, Modus modus) {
        boolean nass = !decke && h < meer - 1;
        int tiefe = nass ? meer - 1 - h : 0;
        Stil s = Stil.von(b);
        MapColor.Brightness hell = nass
            ? (tiefe > 6 ? MapColor.Brightness.LOW : tiefe > 2 ? MapColor.Brightness.NORMAL : MapColor.Brightness.HIGH)
            : decke ? MapColor.Brightness.NORMAL
            : h > nord ? MapColor.Brightness.HIGH : h < nord ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        if (modus == Modus.BIOME) {
            float f = hell == MapColor.Brightness.HIGH ? 1f : hell == MapColor.Brightness.NORMAL ? 0.86f : 0.71f;
            return 0xFF000000 | Kartendaten.darker(Biomfarben.von(b) & 0xFFFFFF, f);
        }
        if (nass) return (s.gefroren ? MapColor.ICE : MapColor.WATER).calculateARGBColor(hell);
        MapColor m = s.boden;
        if (s.schneeAb > 0 && h >= s.schneeAb) m = MapColor.SNOW;
        int zufall = mix(x, z);
        if ((zufall & 0xFF) < s.baeume * 256) {                       // Blätterdach: unruhig hell/dunkel wie echte Bäume
            m = s.baum;
            hell = MapColor.Brightness.values()[(zufall >>> 8) % 3];
        }
        return m.calculateARGBColor(hell);
    }

    private static int mix(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return h ^ (h >>> 15);
    }

    /** Wie ein Biom auf der Karte aussieht: Boden, Bäume (Anteil 0…1), Schnee ab Höhe, gefrorenes Wasser. */
    private record Stil(MapColor boden, MapColor baum, float baeume, int schneeAb, boolean gefroren) {
        static final Map<String, Stil> STILE = Map.ofEntries(
            Map.entry("plains", new Stil(MapColor.GRASS, MapColor.PLANT, 0.02f, 0, false)),
            Map.entry("sunflower_plains", new Stil(MapColor.GRASS, MapColor.PLANT, 0.02f, 0, false)),
            Map.entry("meadow", new Stil(MapColor.GRASS, MapColor.PLANT, 0.03f, 0, false)),
            Map.entry("forest", new Stil(MapColor.GRASS, MapColor.PLANT, 0.75f, 0, false)),
            Map.entry("flower_forest", new Stil(MapColor.GRASS, MapColor.PLANT, 0.6f, 0, false)),
            Map.entry("birch_forest", new Stil(MapColor.GRASS, MapColor.PLANT, 0.75f, 0, false)),
            Map.entry("old_growth_birch_forest", new Stil(MapColor.GRASS, MapColor.PLANT, 0.85f, 0, false)),
            Map.entry("dark_forest", new Stil(MapColor.GRASS, MapColor.COLOR_GREEN, 0.95f, 0, false)),
            Map.entry("pale_garden", new Stil(MapColor.GRASS, MapColor.COLOR_LIGHT_GRAY, 0.9f, 0, false)),
            Map.entry("cherry_grove", new Stil(MapColor.GRASS, MapColor.COLOR_PINK, 0.55f, 0, false)),
            Map.entry("taiga", new Stil(MapColor.PODZOL, MapColor.PLANT, 0.7f, 0, false)),
            Map.entry("old_growth_pine_taiga", new Stil(MapColor.PODZOL, MapColor.PLANT, 0.85f, 0, false)),
            Map.entry("old_growth_spruce_taiga", new Stil(MapColor.PODZOL, MapColor.PLANT, 0.85f, 0, false)),
            Map.entry("snowy_taiga", new Stil(MapColor.SNOW, MapColor.PLANT, 0.5f, 0, true)),
            Map.entry("grove", new Stil(MapColor.SNOW, MapColor.PLANT, 0.35f, 0, true)),
            Map.entry("jungle", new Stil(MapColor.GRASS, MapColor.PLANT, 0.9f, 0, false)),
            Map.entry("sparse_jungle", new Stil(MapColor.GRASS, MapColor.PLANT, 0.4f, 0, false)),
            Map.entry("bamboo_jungle", new Stil(MapColor.GRASS, MapColor.PLANT, 0.9f, 0, false)),
            Map.entry("savanna", new Stil(MapColor.GRASS, MapColor.PLANT, 0.06f, 0, false)),
            Map.entry("savanna_plateau", new Stil(MapColor.GRASS, MapColor.PLANT, 0.08f, 0, false)),
            Map.entry("windswept_savanna", new Stil(MapColor.GRASS, MapColor.PLANT, 0.05f, 0, false)),
            Map.entry("desert", new Stil(MapColor.SAND, MapColor.PLANT, 0f, 0, false)),
            Map.entry("beach", new Stil(MapColor.SAND, MapColor.PLANT, 0f, 0, false)),
            Map.entry("snowy_beach", new Stil(MapColor.SNOW, MapColor.PLANT, 0f, 0, true)),
            Map.entry("stony_shore", new Stil(MapColor.STONE, MapColor.PLANT, 0f, 0, false)),
            Map.entry("badlands", new Stil(MapColor.COLOR_ORANGE, MapColor.PLANT, 0f, 0, false)),
            Map.entry("eroded_badlands", new Stil(MapColor.TERRACOTTA_ORANGE, MapColor.PLANT, 0f, 0, false)),
            Map.entry("wooded_badlands", new Stil(MapColor.TERRACOTTA_ORANGE, MapColor.PLANT, 0.25f, 0, false)),
            Map.entry("swamp", new Stil(MapColor.GRASS, MapColor.PLANT, 0.35f, 0, false)),
            Map.entry("mangrove_swamp", new Stil(MapColor.DIRT, MapColor.PLANT, 0.6f, 0, false)),
            Map.entry("mushroom_fields", new Stil(MapColor.COLOR_PURPLE, MapColor.COLOR_RED, 0.04f, 0, false)),
            Map.entry("snowy_plains", new Stil(MapColor.SNOW, MapColor.PLANT, 0.01f, 0, true)),
            Map.entry("ice_spikes", new Stil(MapColor.SNOW, MapColor.ICE, 0.08f, 0, true)),
            Map.entry("snowy_slopes", new Stil(MapColor.SNOW, MapColor.PLANT, 0f, 0, true)),
            Map.entry("frozen_peaks", new Stil(MapColor.SNOW, MapColor.ICE, 0.1f, 0, true)),
            Map.entry("jagged_peaks", new Stil(MapColor.SNOW, MapColor.STONE, 0.15f, 0, true)),
            Map.entry("stony_peaks", new Stil(MapColor.STONE, MapColor.STONE, 0f, 200, false)),
            Map.entry("windswept_hills", new Stil(MapColor.STONE, MapColor.GRASS, 0.45f, 160, false)),
            Map.entry("windswept_gravelly_hills", new Stil(MapColor.STONE, MapColor.GRASS, 0.2f, 160, false)),
            Map.entry("windswept_forest", new Stil(MapColor.GRASS, MapColor.PLANT, 0.6f, 160, false)),
            Map.entry("river", new Stil(MapColor.SAND, MapColor.PLANT, 0f, 0, false)),
            Map.entry("frozen_river", new Stil(MapColor.SNOW, MapColor.PLANT, 0f, 0, true)),
            Map.entry("frozen_ocean", new Stil(MapColor.SNOW, MapColor.PLANT, 0f, 0, true)),
            Map.entry("deep_frozen_ocean", new Stil(MapColor.SNOW, MapColor.PLANT, 0f, 0, true)),
            Map.entry("nether_wastes", new Stil(MapColor.NETHER, MapColor.NETHER, 0f, 0, false)),
            Map.entry("crimson_forest", new Stil(MapColor.CRIMSON_NYLIUM, MapColor.COLOR_RED, 0.3f, 0, false)),
            Map.entry("warped_forest", new Stil(MapColor.WARPED_NYLIUM, MapColor.COLOR_BLACK, 0.05f, 0, false)),
            Map.entry("soul_sand_valley", new Stil(MapColor.COLOR_BROWN, MapColor.QUARTZ, 0.03f, 0, false)),
            Map.entry("basalt_deltas", new Stil(MapColor.COLOR_BLACK, MapColor.COLOR_LIGHT_GRAY, 0.3f, 0, false)),
            Map.entry("the_end", new Stil(MapColor.SAND, MapColor.COLOR_PURPLE, 0f, 0, false)),
            Map.entry("end_highlands", new Stil(MapColor.SAND, MapColor.COLOR_PURPLE, 0.03f, 0, false)),
            Map.entry("end_midlands", new Stil(MapColor.SAND, MapColor.COLOR_PURPLE, 0f, 0, false)),
            Map.entry("small_end_islands", new Stil(MapColor.SAND, MapColor.COLOR_PURPLE, 0f, 0, false)),
            Map.entry("end_barrens", new Stil(MapColor.SAND, MapColor.COLOR_PURPLE, 0f, 0, false)));
        static final Stil STANDARD = new Stil(MapColor.GRASS, MapColor.PLANT, 0.1f, 0, false);

        static Stil von(Holder<Biome> b) {
            String name = b.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
            if (name.contains("ocean")) return STILE.getOrDefault(name, new Stil(MapColor.SAND, MapColor.PLANT, 0f, 0, false));
            return STILE.getOrDefault(name, STANDARD);
        }
    }

    // ───── Test und Abgleich ─────
    static String biomAn(long seed, int x, int z) {
        var g = generator(seed, "minecraft:overworld");
        return g.biome.getNoiseBiome(x >> 2, 63 >> 2, z >> 2).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
    }

    /** Geländehöhe (oberster fester Block + 1) aus dem Seed. */
    static int hoeheAn(long seed, int x, int z) {
        var g = generator(seed, "minecraft:overworld");
        return g.gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, g.hoehe, g.zufall);
    }

    /**
     * Echte Geländehöhe wie OCEAN_FLOOR_WG (erste Luft über festem Gelände) aus den geladenen Client-Chunks:
     * Bäume, Pflanzen, Schnee und Wasser zählen nicht – die setzt der Generator erst nach getBaseHeight.
     */
    static int gelaendeEcht(net.minecraft.client.multiplayer.ClientLevel level, int x, int z) {
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
        while (pos.getY() > level.getMinY()) {
            var s = level.getBlockState(pos);
            if (s.isSolid() && !s.is(net.minecraft.tags.BlockTags.LEAVES) && !s.is(net.minecraft.tags.BlockTags.LOGS)) break;
            pos.move(0, -1, 0);
        }
        return pos.getY() + 1;
    }

    /** Selbsttest/Diagnose: Biome und Höhen aus dem Seed an Punkten um den Spieler gegen die geladene Welt. "b/n h/n | Details" */
    static String vergleich(long seed) {
        var mc = Minecraft.getInstance();
        var p = mc.player.blockPosition();
        var g = generator(seed, "minecraft:overworld");
        int n = 0, biome = 0, hoehen = 0;
        var details = new StringBuilder();
        for (int dz = -48; dz <= 48; dz += 24)
            for (int dx = -48; dx <= 48; dx += 24) {
                int x = p.getX() + dx, z = p.getZ() + dz;
                if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
                n++;
                int echt = gelaendeEcht(mc.level, x, z), seedH = g.gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, g.hoehe, g.zufall);
                String bEcht = mc.level.getBiome(new net.minecraft.core.BlockPos(x, 63, z)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
                if (bEcht.equals(biomAn(seed, x, z))) biome++;
                if (Math.abs(echt - seedH) <= 2) hoehen++;
                details.append(' ').append(x).append('/').append(z).append(':').append(echt).append("~").append(seedH);
            }
        return biome + "/" + n + " " + hoehen + "/" + n + " |" + details;
    }

    /**
     * Einzelspieler: Seed der Welt gegen die echte Welt prüfen (Biome und Höhe) – zeigt im Log, ob die Seed-Karte deckungsgleich ist.
     * Flache Welten weichen ab (dort gibt es keine normale Generierung).
     */
    static void abgleich() {
        var mc = Minecraft.getInstance();
        var sp = mc.getSingleplayerServer();
        if (sp == null || mc.player == null || !mc.level.dimension().identifier().getPath().equals("overworld")) return;
        long seed = sp.overworld().getSeed();
        var p = mc.player.blockPosition();
        int[][] punkte = {{0, 0}, {48, 0}, {0, 48}, {-48, 0}, {0, -48}, {80, 80}, {-80, -80}};
        var biomEcht = new HashMap<int[], String>();
        var hoeheEcht = new HashMap<int[], Integer>();
        for (int[] d : punkte) {
            int x = p.getX() + d[0], z = p.getZ() + d[1];
            if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
            biomEcht.put(d, mc.level.getBiome(new net.minecraft.core.BlockPos(x, 63, z)).unwrapKey().map(k -> k.identifier().getPath()).orElse("?"));
            hoeheEcht.put(d, gelaendeEcht(mc.level, x, z));   // ohne Bäume – OCEAN_FLOOR zählte Baumkronen mit (alter Fehlalarm)
        }
        var t = new Thread(() -> {
            int biome = 0, hoehen = 0;
            for (var d : biomEcht.keySet()) {
                int x = p.getX() + d[0], z = p.getZ() + d[1];
                if (biomAn(seed, x, z).equals(biomEcht.get(d))) biome++;
                if (Math.abs(hoeheAn(seed, x, z) - hoeheEcht.get(d)) <= 3) hoehen++;   // Höhlen-Carver/Seen höhlen danach noch etwas aus
            }
            System.out.println("[minimap] Seed-Abgleich mit dieser Welt (Seed " + seed + "): Biome " + biome + "/" + biomEcht.size()
                + ", Höhe ±3 " + hoehen + "/" + biomEcht.size());
        }, "minimap-abgleich");
        t.setDaemon(true);
        t.start();
    }
}
