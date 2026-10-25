package karte;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

// Biomfarben im Stil der Chunkbase-/Amidst-Biomkarte. Unbekannte (z. B. aus Datapacks) bekommen eine feste Farbe aus dem Namen.
final class Biomfarben {
    private static final Map<String, Integer> FARBEN = Map.ofEntries(
        Map.entry("ocean", 0x000070), Map.entry("deep_ocean", 0x000030), Map.entry("warm_ocean", 0x0000AC),
        Map.entry("lukewarm_ocean", 0x000090), Map.entry("deep_lukewarm_ocean", 0x000040), Map.entry("cold_ocean", 0x202070),
        Map.entry("deep_cold_ocean", 0x202038), Map.entry("frozen_ocean", 0x7070D6), Map.entry("deep_frozen_ocean", 0x404090),
        Map.entry("river", 0x0000FF), Map.entry("frozen_river", 0xA0A0FF), Map.entry("beach", 0xFADE55),
        Map.entry("snowy_beach", 0xFAF0C0), Map.entry("stony_shore", 0xA2A284), Map.entry("plains", 0x8DB360),
        Map.entry("sunflower_plains", 0xB5DB88), Map.entry("snowy_plains", 0xFFFFFF), Map.entry("ice_spikes", 0xB4DCDC),
        Map.entry("desert", 0xFA9418), Map.entry("swamp", 0x07F9B2), Map.entry("mangrove_swamp", 0x2CCC8E),
        Map.entry("forest", 0x056621), Map.entry("flower_forest", 0x2D8E49), Map.entry("birch_forest", 0x307444),
        Map.entry("old_growth_birch_forest", 0x589C6C), Map.entry("dark_forest", 0x40511A), Map.entry("pale_garden", 0x8C8F8A),
        Map.entry("taiga", 0x0B6659), Map.entry("snowy_taiga", 0x31554A), Map.entry("old_growth_pine_taiga", 0x596651),
        Map.entry("old_growth_spruce_taiga", 0x818E79), Map.entry("jungle", 0x537B09), Map.entry("sparse_jungle", 0x628B17),
        Map.entry("bamboo_jungle", 0x768E14), Map.entry("savanna", 0xBDB25F), Map.entry("savanna_plateau", 0xA79D64),
        Map.entry("windswept_savanna", 0xE5DA87), Map.entry("badlands", 0xD94515), Map.entry("wooded_badlands", 0xB09765),
        Map.entry("eroded_badlands", 0xFF6D3D), Map.entry("windswept_hills", 0x606060), Map.entry("windswept_gravelly_hills", 0x888888),
        Map.entry("windswept_forest", 0x507A4A), Map.entry("meadow", 0x83BB6D), Map.entry("cherry_grove", 0xFFB7D5),
        Map.entry("grove", 0x80B497), Map.entry("snowy_slopes", 0xDAE0E6), Map.entry("frozen_peaks", 0xB0C4DE),
        Map.entry("jagged_peaks", 0xDCDCDC), Map.entry("stony_peaks", 0x7B8F74), Map.entry("mushroom_fields", 0xFF00FF),
        Map.entry("dripstone_caves", 0x7B5E40), Map.entry("lush_caves", 0x5F8F2B), Map.entry("deep_dark", 0x0A2A33),
        Map.entry("nether_wastes", 0xBF3B3B), Map.entry("soul_sand_valley", 0x5E3830), Map.entry("crimson_forest", 0xDD0808),
        Map.entry("warped_forest", 0x49907B), Map.entry("basalt_deltas", 0x403636), Map.entry("the_end", 0x8080FF),
        Map.entry("end_highlands", 0xB5B572), Map.entry("end_midlands", 0xA8A86C), Map.entry("small_end_islands", 0x4B4B8C),
        Map.entry("end_barrens", 0x7070B0), Map.entry("the_void", 0x000000));

    static int von(Holder<Biome> b) {
        String name = b.unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
        Integer c = FARBEN.get(name);
        return 0xFF000000 | (c != null ? c : (name.hashCode() & 0x7F7F7F) + 0x404040);
    }
}
