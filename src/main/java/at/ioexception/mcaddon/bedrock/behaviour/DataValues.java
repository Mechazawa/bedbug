package at.ioexception.mcaddon.bedrock.behaviour;

import net.minecraft.world.item.DyeColor;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * The item a Bedrock id and the data value beside it stand for.
 *
 * Java gave every block and item an id of its own when it flattened them. Bedrock kept the ids that
 * stood for a whole family and the number that picks one out of it, so `minecraft:wool` and a five
 * is `lime_wool`, `minecraft:dye` and a four is `lapis_lazuli`, and `minecraft:bed` covers sixteen
 * beds. Flattening is the mapping rather than the obstacle, and this is that mapping.
 *
 * Each family is in the order the engine publishes it: the block property whose values Bedrock
 * lists for that block, the texture list an item is drawn from, or the recipes crafting its
 * members. A family Java never split has no entry here, and neither does a value nothing answers,
 * because a near answer hands a player the wrong item and says nothing about it.
 */
public final class DataValues {
    private DataValues() {
    }

    /** How many colours a colour index runs over, which is what Bedrock numbers its dyes with. */
    public static final int COLOURS = 16;

    /**
     * The sixteen members of a family Java names by colour, in Bedrock's own index order.
     *
     * Bedrock numbers a colour the way Java numbers a dye, so the engine's own list and
     * `DyeColor` run in step, down to light grey sitting at eight under the name `silver`.
     */
    private static List<String> dyed(String suffix) {
        return IntStream.range(0, COLOURS)
            .mapToObj(index -> DyeColor.byId(index).getName() + suffix)
            .toList();
    }

    /**
     * Every Bedrock id Java split, and the item each of its data values became.
     *
     * A blank stands for a value Java kept no item for: Bedrock carries a chiselled and a smooth
     * purpur block that Java never had.
     */
    private static final Map<String, List<String>> FAMILIES = Map.ofEntries(
        Map.entry("wool", dyed("_wool")),
        Map.entry("carpet", dyed("_carpet")),
        Map.entry("bed", dyed("_bed")),
        Map.entry("concrete", dyed("_concrete")),
        Map.entry("concrete_powder", dyed("_concrete_powder")),
        Map.entry("stained_glass", dyed("_stained_glass")),
        Map.entry("stained_glass_pane", dyed("_stained_glass_pane")),
        Map.entry("stained_hardened_clay", dyed("_terracotta")),
        Map.entry("shulker_box", dyed("_shulker_box")),
        // A banner counts its colours the other way about: Mojang's own recipes craft the black one
        // at zero and the white one at fifteen.
        Map.entry("banner", dyed("_banner").reversed()),
        // The dyes that were something else before Java gave every colour a dye of its own: ink, a
        // cocoa bean, lapis and bone meal. The four at the end are the ones Bedrock added once those
        // four stopped dyeing anything.
        Map.entry("dye", List.of("ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli",
            "purple_dye", "cyan_dye", "light_gray_dye", "gray_dye", "pink_dye", "lime_dye",
            "yellow_dye", "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal", "black_dye",
            "brown_dye", "blue_dye", "white_dye", "glow_ink_sac")),
        Map.entry("skull", List.of("skeleton_skull", "wither_skeleton_skull", "zombie_head",
            "player_head", "creeper_head", "dragon_head", "piglin_head")),
        Map.entry("banner_pattern", List.of("creeper_banner_pattern", "skull_banner_pattern",
            "flower_banner_pattern", "mojang_banner_pattern", "field_masoned_banner_pattern",
            "bordure_indented_banner_pattern", "piglin_banner_pattern")),
        Map.entry("coal", List.of("coal", "charcoal")),
        Map.entry("stone", List.of("stone", "granite", "polished_granite", "diorite",
            "polished_diorite", "andesite", "polished_andesite")),
        Map.entry("stonebrick", List.of("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
            "chiseled_stone_bricks")),
        Map.entry("sandstone", List.of("sandstone", "chiseled_sandstone", "cut_sandstone",
            "smooth_sandstone")),
        Map.entry("red_sandstone", List.of("red_sandstone", "chiseled_red_sandstone",
            "cut_red_sandstone", "smooth_red_sandstone")),
        Map.entry("quartz_block", List.of("quartz_block", "chiseled_quartz_block", "quartz_pillar",
            "smooth_quartz")),
        Map.entry("purpur_block", List.of("purpur_block", "", "purpur_pillar", "")),
        Map.entry("prismarine", List.of("prismarine", "dark_prismarine", "prismarine_bricks")),
        Map.entry("log", List.of("oak_log", "spruce_log", "birch_log", "jungle_log")),
        Map.entry("log2", List.of("acacia_log", "dark_oak_log")),
        Map.entry("leaves", List.of("oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves")),
        Map.entry("leaves2", List.of("acacia_leaves", "dark_oak_leaves")),
        Map.entry("planks", List.of("oak_planks", "spruce_planks", "birch_planks", "jungle_planks",
            "acacia_planks", "dark_oak_planks")),
        Map.entry("wooden_slab", List.of("oak_slab", "spruce_slab", "birch_slab", "jungle_slab",
            "acacia_slab", "dark_oak_slab")),
        Map.entry("sapling", List.of("oak_sapling", "spruce_sapling", "birch_sapling",
            "jungle_sapling", "acacia_sapling", "dark_oak_sapling", "pale_oak_sapling")),
        Map.entry("sand", List.of("sand", "red_sand")),
        Map.entry("dirt", List.of("dirt", "coarse_dirt")),
        Map.entry("sponge", List.of("sponge", "wet_sponge")),
        Map.entry("red_flower", List.of("poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
            "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
            "lily_of_the_valley")),
        Map.entry("double_plant", List.of("sunflower", "lilac", "tall_grass", "large_fern",
            "rose_bush", "peony")),
        Map.entry("monster_egg", List.of("infested_stone", "infested_cobblestone",
            "infested_stone_bricks", "infested_mossy_stone_bricks", "infested_cracked_stone_bricks",
            "infested_chiseled_stone_bricks")),
        Map.entry("stone_slab", List.of("smooth_stone_slab", "sandstone_slab", "petrified_oak_slab",
            "cobblestone_slab", "brick_slab", "stone_brick_slab", "quartz_slab",
            "nether_brick_slab")),
        Map.entry("stone_slab2", List.of("red_sandstone_slab", "purpur_slab", "prismarine_slab",
            "dark_prismarine_slab", "prismarine_brick_slab", "mossy_cobblestone_slab",
            "smooth_sandstone_slab", "red_nether_brick_slab")),
        Map.entry("stone_slab3", List.of("end_stone_brick_slab", "smooth_red_sandstone_slab",
            "polished_andesite_slab", "andesite_slab", "diorite_slab", "polished_diorite_slab",
            "granite_slab", "polished_granite_slab")),
        Map.entry("stone_slab4", List.of("mossy_stone_brick_slab", "smooth_quartz_slab", "stone_slab",
            "cut_sandstone_slab", "cut_red_sandstone_slab")),
        Map.entry("cobblestone_wall", List.of("cobblestone_wall", "mossy_cobblestone_wall",
            "granite_wall", "diorite_wall", "andesite_wall", "sandstone_wall", "brick_wall",
            "stone_brick_wall", "mossy_stone_brick_wall", "nether_brick_wall",
            "end_stone_brick_wall", "prismarine_wall", "red_sandstone_wall",
            "red_nether_brick_wall")),
        Map.entry("coral", List.of("tube_coral", "brain_coral", "bubble_coral", "fire_coral",
            "horn_coral")),
        Map.entry("coral_fan", List.of("tube_coral_fan", "brain_coral_fan", "bubble_coral_fan",
            "fire_coral_fan", "horn_coral_fan")),
        Map.entry("coral_block", List.of("tube_coral_block", "brain_coral_block",
            "bubble_coral_block", "fire_coral_block", "horn_coral_block")));

    /**
     * The Java item a Bedrock id and its data value name, or null where the pair names none.
     *
     * A value outside the family, and one Java kept no item for, are both no answer rather than a
     * near one. An id Java never split is no answer either: its data value picks nothing, which is
     * what leaves the item as the pack named it.
     */
    public static String item(String bedrock, int data) {
        String named = vanilla(bedrock);
        List<String> family = named == null ? null : FAMILIES.get(named);
        if (family == null || data < 0 || data >= family.size()) return null;
        return family.get(data).isEmpty() ? null : "minecraft:" + family.get(data);
    }

    /**
     * The bare name of a vanilla id, or null for one a pack coined.
     *
     * A data value belongs to the families Java itself split, so an id under a pack's own namespace
     * names whatever that pack made it name and nothing here answers for it.
     */
    public static String vanilla(String bedrock) {
        if (bedrock == null) return null;

        int colon = bedrock.indexOf(':');
        if (colon < 0) return bedrock;
        return bedrock.startsWith("minecraft:") ? bedrock.substring(colon + 1) : null;
    }
}
