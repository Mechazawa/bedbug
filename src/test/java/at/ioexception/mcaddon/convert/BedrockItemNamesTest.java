package at.ioexception.mcaddon.convert;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Java item a Bedrock item id names, and the way back.
 *
 * An id that does not resolve costs a pack a whole loot table, recipe or function, so each kind of
 * difference is pinned down once: a rename, a family picked by a data value written beside the id
 * or after it, a spelling Bedrock reads without regard to case, and a Bedrock name Java spells for
 * something else.
 */
class BedrockItemNamesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({
        "minecraft:golden_rail, minecraft:powered_rail",
        "golden_rail, minecraft:powered_rail",
        "minecraft:lit_pumpkin, minecraft:jack_o_lantern",
        "minecraft:frame, minecraft:item_frame",
        "minecraft:empty_map, minecraft:map",
        "minecraft:record_13, minecraft:music_disc_13",
        "minecraft:muttonRaw, minecraft:mutton",
        "minecraft:appleEnchanted, minecraft:enchanted_golden_apple",
        "minecraft:horsearmorgold, minecraft:golden_horse_armor",
        "minecraft:speckled_melon, minecraft:glistering_melon_slice",
        "minecraft:netherbrick, minecraft:nether_brick",
        "minecraft:nether_brick, minecraft:nether_bricks",
        "minecraft:snow, minecraft:snow_block",
        "minecraft:snow_layer, minecraft:snow",
        "minecraft:stone_stairs, minecraft:cobblestone_stairs",
        "minecraft:normal_stone_stairs, minecraft:stone_stairs",
        "minecraft:darkoak_sign, minecraft:dark_oak_sign",
        "minecraft:wool, minecraft:white_wool",
        "minecraft:wool:14, minecraft:red_wool",
        "minecraft:banner:15, minecraft:white_banner",
        "minecraft:log2:1, minecraft:dark_oak_log",
        "minecraft:light_block_7, minecraft:light",
        "minecraft:stone, minecraft:stone",
        "minecraft:diamond_sword, minecraft:diamond_sword",
    })
    void namesTheJavaItem(String bedrock, String java) {
        assertEquals(java, BedrockItemNames.java(bedrock));
    }

    @Test
    void readsADataValueGivenApart() {
        assertEquals("minecraft:lime_dye", BedrockItemNames.java("minecraft:dye", 10));
        assertEquals("minecraft:emerald", BedrockItemNames.java("minecraft:emerald", 3));
        assertNull(BedrockItemNames.java("minecraft:wool", 16));
    }

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:border_block", "minecraft:white_cushion", "minecraft:no_such_item"})
    void answersNothingForAnItemJavaLacks(String bedrock) {
        assertNull(BedrockItemNames.java(bedrock));
    }

    @Test
    void leavesAPacksOwnItemToTheRegistry() {
        assertEquals("tp:shard", BedrockItemNames.java("tp:shard"));
        assertEquals("tp:shard", BedrockItemNames.bedrock("tp:shard"));
    }

    @ParameterizedTest
    @CsvSource({
        "minecraft:powered_rail, minecraft:golden_rail",
        "minecraft:jack_o_lantern, minecraft:lit_pumpkin",
        "minecraft:item_frame, minecraft:frame",
        "minecraft:map, minecraft:empty_map",
        "minecraft:nether_brick, minecraft:netherbrick",
        "minecraft:nether_bricks, minecraft:nether_brick",
        "minecraft:snow, minecraft:snow_layer",
        "minecraft:snow_block, minecraft:snow",
        "minecraft:red_banner, minecraft:banner",
        "minecraft:red_bed, minecraft:bed",
        "minecraft:oak_sign, minecraft:oak_sign",
        "minecraft:music_disc_13, minecraft:music_disc_13",
        "minecraft:diamond_sword, minecraft:diamond_sword",
    })
    void reportsAJavaItemUnderBedrocksName(String java, String bedrock) {
        assertEquals(bedrock, BedrockItemNames.bedrock(java));
    }

    /**
     * What a pack reads off an item has to name that same item when the pack hands it back.
     *
     * Every item the game has is asked, because a Bedrock name that Java spells for something else
     * (`snow`, `nether_brick`, `stone_stairs`) breaks the round trip in one direction only. Banners
     * and beds are the exception Bedrock makes itself: every colour is one id told apart by a data
     * value, which the stack does not carry.
     */
    @Test
    void readsBackWhatItReports() {
        java.util.List<String> lost = new java.util.ArrayList<>();
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            String java = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
            if (java.endsWith("_banner") || java.endsWith("_bed")) continue;

            String bedrock = BedrockItemNames.bedrock(java);
            if (!java.equals(BedrockItemNames.java(bedrock))) lost.add(java + " -> " + bedrock);
        }
        assertTrue(lost.isEmpty(), lost.toString());
        assertTrue(BedrockItemNames.bedrockOnly("minecraft:white_cushion"));
    }
}
