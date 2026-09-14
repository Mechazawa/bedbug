package at.ioexception.mcaddon.bedrock.behaviour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a Bedrock id and the data value beside it name, which is a table and nothing else.
 *
 * A wrong entry hands a player the wrong item and reports nothing, so each family is checked at the
 * values that pin its order down: the ends, and the members whose place is not the obvious one.
 */
class DataValuesTest {
    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "wool 0                  -> minecraft:white_wool",
        "wool 5                  -> minecraft:lime_wool",
        "wool 8                  -> minecraft:light_gray_wool",
        "wool 15                 -> minecraft:black_wool",
        "minecraft:bed 14        -> minecraft:red_bed",
        "carpet 3                -> minecraft:light_blue_carpet",
        "stained_hardened_clay 1 -> minecraft:orange_terracotta",
        "dye 0                   -> minecraft:ink_sac",
        "dye 3                   -> minecraft:cocoa_beans",
        "dye 4                   -> minecraft:lapis_lazuli",
        "dye 15                  -> minecraft:bone_meal",
        "dye 16                  -> minecraft:black_dye",
        "skull 1                 -> minecraft:wither_skeleton_skull",
        "skull 4                 -> minecraft:creeper_head",
        "banner_pattern 6        -> minecraft:piglin_banner_pattern",
        "coal 1                  -> minecraft:charcoal",
        "stone 2                 -> minecraft:polished_granite",
        "sandstone 1             -> minecraft:chiseled_sandstone",
        "prismarine 1            -> minecraft:dark_prismarine",
        "quartz_block 2          -> minecraft:quartz_pillar",
        "log2 1                  -> minecraft:dark_oak_log",
        "red_flower 3            -> minecraft:azure_bluet",
        "double_plant 1          -> minecraft:lilac",
        "stone_slab 6            -> minecraft:quartz_slab",
        "stone_slab 7            -> minecraft:nether_brick_slab",
        "cobblestone_wall 10     -> minecraft:end_stone_brick_wall",
    })
    void namesTheJavaItemABedrockIdAndItsDataValueStandFor(String pair, String expected) {
        String[] stated = pair.strip().split(" ");
        assertEquals(expected.strip(), DataValues.item(stated[0], Integer.parseInt(stated[1])));
    }

    /**
     * A banner counts its colours backwards, which Mojang's own recipes are what settle.
     *
     * Every other coloured family starts at white. The banner starts at black, so a family read the
     * ordinary way comes out the exact opposite of what the pack asked for.
     */
    @Test
    void countsABannersColoursTheWayBedrocksOwnRecipesCraftThem() {
        assertEquals("minecraft:black_banner", DataValues.item("banner", 0));
        assertEquals("minecraft:white_banner", DataValues.item("banner", 15));
    }

    @ParameterizedTest
    @CsvSource({"stick, 0", "stick, 4", "wool, 16", "wool, -1", "purpur_block, 1", "sapling, 9"})
    void answersNothingForAPairNoItemStandsFor(String bedrock, int data) {
        assertNull(DataValues.item(bedrock, data));
    }

    /** A pack's own id names whatever that pack made it name, whatever vanilla spells the same. */
    @Test
    void leavesAPacksOwnIdAlone() {
        assertNull(DataValues.item("tp:wool", 5));
        assertNull(DataValues.vanilla("tp:wool"));
        assertEquals("wool", DataValues.vanilla("minecraft:wool"));
        assertEquals("wool", DataValues.vanilla("wool"));
    }
}
