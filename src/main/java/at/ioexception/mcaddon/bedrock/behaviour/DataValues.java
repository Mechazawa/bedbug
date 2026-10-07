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
        // Bedrock's block went on to be called `stone_block_slab`, keeping the numbering.
        Map.entry("stone_block_slab", List.of("smooth_stone_slab", "sandstone_slab",
            "petrified_oak_slab", "cobblestone_slab", "brick_slab", "stone_brick_slab", "quartz_slab",
            "nether_brick_slab")),
        Map.entry("stone_block_slab2", List.of("red_sandstone_slab", "purpur_slab", "prismarine_slab",
            "dark_prismarine_slab", "prismarine_brick_slab", "mossy_cobblestone_slab",
            "smooth_sandstone_slab", "red_nether_brick_slab")),
        Map.entry("stone_block_slab3", List.of("end_stone_brick_slab", "smooth_red_sandstone_slab",
            "polished_andesite_slab", "andesite_slab", "diorite_slab", "polished_diorite_slab",
            "granite_slab", "polished_granite_slab")),
        Map.entry("stone_block_slab4", List.of("mossy_stone_brick_slab", "smooth_quartz_slab",
            "stone_slab", "cut_sandstone_slab", "cut_red_sandstone_slab")),
        // A dead coral is the live one's number with eight added, which is its `dead_bit`.
        Map.entry("coral", List.of("tube_coral", "brain_coral", "bubble_coral", "fire_coral",
            "horn_coral", "", "", "", "dead_tube_coral", "dead_brain_coral", "dead_bubble_coral",
            "dead_fire_coral", "dead_horn_coral")),
        Map.entry("coral_block", List.of("tube_coral_block", "brain_coral_block",
            "bubble_coral_block", "fire_coral_block", "horn_coral_block", "", "", "",
            "dead_tube_coral_block", "dead_brain_coral_block", "dead_bubble_coral_block",
            "dead_fire_coral_block", "dead_horn_coral_block")),
        Map.entry("coral_fan", List.of("tube_coral_fan", "brain_coral_fan", "bubble_coral_fan",
            "fire_coral_fan", "horn_coral_fan")),
        Map.entry("coral_fan_dead", List.of("dead_tube_coral_fan", "dead_brain_coral_fan",
            "dead_bubble_coral_fan", "dead_fire_coral_fan", "dead_horn_coral_fan")),
        // The fans on a wall come two kinds to a block, told apart by one bit, and dead by a second.
        Map.entry("coral_fan_hang", List.of("tube_coral_wall_fan", "brain_coral_wall_fan",
            "dead_tube_coral_wall_fan", "dead_brain_coral_wall_fan")),
        Map.entry("coral_fan_hang2", List.of("bubble_coral_wall_fan", "fire_coral_wall_fan",
            "dead_bubble_coral_wall_fan", "dead_fire_coral_wall_fan")),
        Map.entry("coral_fan_hang3", List.of("horn_coral_wall_fan", "", "dead_horn_coral_wall_fan")),
        // A stripped wood is the plain one's number with eight added, which is its `stripped_bit`.
        Map.entry("wood", List.of("oak_wood", "spruce_wood", "birch_wood", "jungle_wood",
            "acacia_wood", "dark_oak_wood", "", "", "stripped_oak_wood", "stripped_spruce_wood",
            "stripped_birch_wood", "stripped_jungle_wood", "stripped_acacia_wood",
            "stripped_dark_oak_wood")),
        Map.entry("fence", List.of("oak_fence", "spruce_fence", "birch_fence", "jungle_fence",
            "acacia_fence", "dark_oak_fence")),
        Map.entry("tallgrass", List.of("short_grass", "short_grass", "fern")),
        Map.entry("boat", List.of("oak_boat", "spruce_boat", "birch_boat", "jungle_boat",
            "acacia_boat", "dark_oak_boat", "mangrove_boat")),
        Map.entry("chest_boat", List.of("oak_chest_boat", "spruce_chest_boat", "birch_chest_boat",
            "jungle_chest_boat", "acacia_chest_boat", "dark_oak_chest_boat", "mangrove_chest_boat")),
        Map.entry("bucket", List.of("bucket", "milk_bucket", "cod_bucket", "salmon_bucket",
            "tropical_fish_bucket", "pufferfish_bucket", "", "", "water_bucket", "", "lava_bucket")));

    /** The colours as a Bedrock block state names them, which calls light grey `silver`. */
    private static final List<String> COLOUR_STATES = IntStream.range(0, COLOURS)
        .mapToObj(index -> index == 8 ? "silver" : DyeColor.byId(index).getName())
        .toList();

    private static final List<String> WOODS =
        List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak");

    private static final List<String> CORALS = List.of("blue", "pink", "purple", "red", "yellow");

    /**
     * The block state that picks a member out of a family, its values in data value order.
     *
     * Bedrock moved from the number to a named state before it gave each member an id of its own,
     * so a pack written in between says `["color"="red"]` where an older one wrote fourteen. A flag
     * adds its weight to the number, which is how a dead coral and a stripped wood are counted.
     */
    private record Keyed(String state, List<String> values, Map<String, Integer> flags) {
        static Keyed by(String state, List<String> values) {
            return new Keyed(state, values, Map.of());
        }

        static Keyed by(String state, String... values) {
            return by(state, List.of(values));
        }

        static Keyed flags(Map<String, Integer> flags) {
            return new Keyed(null, List.of(), flags);
        }

        /** The data value the states name, or null where they name a value the family lacks. */
        Integer data(Map<String, String> states) {
            String chosen = state == null ? null : states.get(state);
            int index = chosen == null ? 0 : values.indexOf(chosen);
            if (index < 0) return null;

            for (Map.Entry<String, Integer> flag : flags.entrySet()) {
                String set = states.get(flag.getKey());
                if ("true".equals(set) || "1".equals(set)) index += flag.getValue();
            }
            return index;
        }

        boolean names(String name) {
            return name.equals(state) || flags.containsKey(name);
        }
    }

    private static final Map<String, Keyed> STATES = Map.ofEntries(
        Map.entry("wool", Keyed.by("color", COLOUR_STATES)),
        Map.entry("carpet", Keyed.by("color", COLOUR_STATES)),
        Map.entry("concrete", Keyed.by("color", COLOUR_STATES)),
        Map.entry("concrete_powder", Keyed.by("color", COLOUR_STATES)),
        Map.entry("stained_glass", Keyed.by("color", COLOUR_STATES)),
        Map.entry("stained_glass_pane", Keyed.by("color", COLOUR_STATES)),
        Map.entry("stained_hardened_clay", Keyed.by("color", COLOUR_STATES)),
        Map.entry("shulker_box", Keyed.by("color", COLOUR_STATES)),
        Map.entry("log", Keyed.by("old_log_type", "oak", "spruce", "birch", "jungle")),
        Map.entry("log2", Keyed.by("new_log_type", "acacia", "dark_oak")),
        Map.entry("leaves", Keyed.by("old_leaf_type", "oak", "spruce", "birch", "jungle")),
        Map.entry("leaves2", Keyed.by("new_leaf_type", "acacia", "dark_oak")),
        Map.entry("planks", Keyed.by("wood_type", WOODS)),
        Map.entry("wooden_slab", Keyed.by("wood_type", WOODS)),
        Map.entry("fence", Keyed.by("wood_type", WOODS)),
        Map.entry("wood", new Keyed("wood_type", WOODS, Map.of("stripped_bit", 8))),
        Map.entry("sapling", Keyed.by("sapling_type", WOODS)),
        Map.entry("stone", Keyed.by("stone_type", "stone", "granite", "granite_smooth", "diorite",
            "diorite_smooth", "andesite", "andesite_smooth")),
        Map.entry("stonebrick", Keyed.by("stone_brick_type", "default", "mossy", "cracked",
            "chiseled")),
        Map.entry("sandstone", Keyed.by("sand_stone_type", "default", "heiroglyphs", "cut", "smooth")),
        Map.entry("red_sandstone", Keyed.by("sand_stone_type", "default", "heiroglyphs", "cut",
            "smooth")),
        Map.entry("quartz_block", Keyed.by("chisel_type", "default", "chiseled", "lines", "smooth")),
        Map.entry("purpur_block", Keyed.by("chisel_type", "default", "chiseled", "lines", "smooth")),
        Map.entry("prismarine", Keyed.by("prismarine_block_type", "default", "dark", "bricks")),
        Map.entry("sand", Keyed.by("sand_type", "normal", "red")),
        Map.entry("dirt", Keyed.by("dirt_type", "normal", "coarse")),
        Map.entry("sponge", Keyed.by("sponge_type", "dry", "wet")),
        Map.entry("red_flower", Keyed.by("flower_type", "poppy", "orchid", "allium", "houstonia",
            "tulip_red", "tulip_orange", "tulip_white", "tulip_pink", "oxeye", "cornflower",
            "lily_of_the_valley")),
        Map.entry("double_plant", Keyed.by("double_plant_type", "sunflower", "syringa", "grass",
            "fern", "rose", "paeonia")),
        Map.entry("tallgrass", Keyed.by("tall_grass_type", "default", "tall", "fern")),
        Map.entry("monster_egg", Keyed.by("monster_egg_stone_type", "stone", "cobblestone",
            "stone_brick", "mossy_stone_brick", "cracked_stone_brick", "chiseled_stone_brick")),
        Map.entry("stone_slab", Keyed.by("stone_slab_type", "smooth_stone", "sandstone", "wood",
            "cobblestone", "brick", "stone_brick", "quartz", "nether_brick")),
        Map.entry("stone_slab2", Keyed.by("stone_slab_type_2", "red_sandstone", "purpur",
            "prismarine_rough", "prismarine_dark", "prismarine_brick", "mossy_cobblestone",
            "smooth_sandstone", "red_nether_brick")),
        Map.entry("stone_slab3", Keyed.by("stone_slab_type_3", "end_stone_brick",
            "smooth_red_sandstone", "polished_andesite", "andesite", "diorite", "polished_diorite",
            "granite", "polished_granite")),
        Map.entry("stone_slab4", Keyed.by("stone_slab_type_4", "mossy_stone_brick", "smooth_quartz",
            "stone", "cut_sandstone", "cut_red_sandstone")),
        Map.entry("stone_block_slab", Keyed.by("stone_slab_type", "smooth_stone", "sandstone", "wood",
            "cobblestone", "brick", "stone_brick", "quartz", "nether_brick")),
        Map.entry("stone_block_slab2", Keyed.by("stone_slab_type_2", "red_sandstone", "purpur",
            "prismarine_rough", "prismarine_dark", "prismarine_brick", "mossy_cobblestone",
            "smooth_sandstone", "red_nether_brick")),
        Map.entry("stone_block_slab3", Keyed.by("stone_slab_type_3", "end_stone_brick",
            "smooth_red_sandstone", "polished_andesite", "andesite", "diorite", "polished_diorite",
            "granite", "polished_granite")),
        Map.entry("stone_block_slab4", Keyed.by("stone_slab_type_4", "mossy_stone_brick",
            "smooth_quartz", "stone", "cut_sandstone", "cut_red_sandstone")),
        Map.entry("cobblestone_wall", Keyed.by("wall_block_type", "cobblestone", "mossy_cobblestone",
            "granite", "diorite", "andesite", "sandstone", "brick", "stone_brick",
            "mossy_stone_brick", "nether_brick", "end_brick", "prismarine", "red_sandstone",
            "red_nether_brick")),
        Map.entry("coral", new Keyed("coral_color", CORALS, Map.of("dead_bit", 8))),
        Map.entry("coral_block", new Keyed("coral_color", CORALS, Map.of("dead_bit", 8))),
        Map.entry("coral_fan", Keyed.by("coral_color", CORALS)),
        Map.entry("coral_fan_dead", Keyed.by("coral_color", CORALS)),
        Map.entry("coral_fan_hang", Keyed.flags(Map.of("coral_hang_type_bit", 1, "dead_bit", 2))),
        Map.entry("coral_fan_hang2", Keyed.flags(Map.of("coral_hang_type_bit", 1, "dead_bit", 2))),
        Map.entry("coral_fan_hang3", Keyed.flags(Map.of("dead_bit", 2))));

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

    /** Whether Java split this id into one item per data value. */
    public static boolean split(String bedrock) {
        String named = vanilla(bedrock);
        return named != null && FAMILIES.containsKey(named);
    }

    /**
     * The member of a family its block states pick, or null where they pick none.
     *
     * A state the family is not keyed on is left for the caller to apply to the member, so
     * `["color"="red","facing_direction"=2]` on a glazed family would pick by colour alone.
     */
    public static String block(String bedrock, Map<String, String> states) {
        String named = vanilla(bedrock);
        Keyed keyed = named == null ? null : STATES.get(named);
        Integer data = keyed == null ? Integer.valueOf(0) : keyed.data(states);
        return data == null ? null : item(bedrock, data);
    }

    /** Whether this state is one the family picks its member by, rather than one the member has. */
    public static boolean picks(String bedrock, String state) {
        String named = vanilla(bedrock);
        Keyed keyed = named == null ? null : STATES.get(named);
        return keyed != null && keyed.names(state);
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
