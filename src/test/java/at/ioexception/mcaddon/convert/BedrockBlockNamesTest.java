package at.ioexception.mcaddon.convert;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Java block state a Bedrock block id and its states name, and the way back.
 *
 * Bedrock spells as an id of its own what Java holds in a property, and picks a member of a family
 * by a state Java has no property for, so the answer is a state rather than a name.
 */
class BedrockBlockNamesTest {
    private final List<String> reported = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({
        "minecraft:golden_rail, minecraft:powered_rail",
        "minecraft:lit_pumpkin, minecraft:jack_o_lantern",
        "minecraft:grass_path, minecraft:dirt_path",
        "minecraft:snow, minecraft:snow_block",
        "minecraft:snow_layer, minecraft:snow",
        "minecraft:stone_stairs, minecraft:cobblestone_stairs",
        "minecraft:darkoak_standing_sign, minecraft:dark_oak_sign",
        "minecraft:spruce_standing_sign, minecraft:spruce_sign",
        "minecraft:wall_sign, minecraft:oak_wall_sign",
        "minecraft:trip_wire, minecraft:tripwire",
        "minecraft:grass, minecraft:grass_block",
        "minecraft:stone, minecraft:stone",
    })
    void namesTheJavaBlock(String bedrock, String java) {
        assertEquals(java, BedrockBlockNames.java(bedrock));
    }

    @Test
    void picksAFamilyMemberByItsState() {
        assertEquals(block("red_wool"), state("minecraft:wool", Map.of("color", "red")));
        assertEquals(block("light_gray_stained_glass"),
            state("minecraft:stained_glass", Map.of("color", "silver")));
        assertEquals(Blocks.SPRUCE_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS,
            net.minecraft.core.Direction.Axis.X),
            state("minecraft:log", Map.of("old_log_type", "spruce", "pillar_axis", "x")));
        assertEquals(Blocks.STRIPPED_BIRCH_WOOD.defaultBlockState(),
            state("minecraft:wood", Map.of("wood_type", "birch", "stripped_bit", true)));
        assertTrue(reported.isEmpty(), reported.toString());
    }

    @Test
    void picksAFamilyMemberByItsDataValue() {
        assertEquals(block("lime_wool"),
            BedrockBlockNames.state("wool", 5, reported::add).state());
        assertEquals(Blocks.GRANITE.defaultBlockState(),
            BedrockBlockNames.state("minecraft:stone", 1, reported::add).state());
    }

    @Test
    void setsTheStateABedrockIdStandsFor() {
        assertEquals(Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT, true),
            state("minecraft:lit_furnace", Map.of()));
        assertEquals(Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
            state("minecraft:oak_double_slab", Map.of()));
        assertEquals(Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
            state("minecraft:normal_stone_double_slab", Map.of()));
        assertEquals(block("waxed_exposed_cut_copper_slab")
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
            state("minecraft:waxed_exposed_double_cut_copper_slab", Map.of()));
        assertEquals(Blocks.LIGHT.defaultBlockState().setValue(BlockStateProperties.LEVEL, 7),
            state("minecraft:light_block_7", Map.of()));
        assertEquals(Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.POWERED, true)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH),
            state("minecraft:powered_repeater", Map.of("direction", 2)));
    }

    /** A command testing for a block may ask only about what the pack named, or it matches less. */
    @Test
    void writesOnlyTheStatedPropertiesForACommand() {
        assertEquals("minecraft:powered_rail",
            BedrockBlockNames.state("golden_rail", Map.of(), reported::add).command());
        assertEquals("minecraft:furnace[lit=true,facing=south]",
            BedrockBlockNames.state("lit_furnace", Map.of("facing_direction", 3), reported::add).command());
        assertEquals("minecraft:red_wool",
            BedrockBlockNames.state("wool", Map.of("color", "red"), reported::add).command());
    }

    @Test
    void reportsAStateNoPropertyAnswers() {
        assertEquals(Blocks.STONE.defaultBlockState(), state("minecraft:stone", Map.of("no_such_state", 1)));
        assertEquals(List.of("block state minecraft:stone[no_such_state]"), reported);
    }

    @Test
    void answersNothingForABlockJavaLacks() {
        assertNull(BedrockBlockNames.java("minecraft:border_block"));
        assertNull(BedrockBlockNames.state("minecraft:element_1", Map.of(), reported::add));
        assertTrue(BedrockBlockNames.bedrockOnly("minecraft:element_1"));
        assertTrue(BedrockBlockNames.bedrockOnly("minecraft:hard_red_stained_glass_pane"));
    }

    @ParameterizedTest
    @CsvSource({
        "minecraft:powered_rail, minecraft:golden_rail",
        "minecraft:jack_o_lantern, minecraft:lit_pumpkin",
        "minecraft:snow, minecraft:snow_layer",
        "minecraft:snow_block, minecraft:snow",
        "minecraft:wall_torch, minecraft:torch",
        "minecraft:oak_sign, minecraft:standing_sign",
        "minecraft:dark_oak_wall_sign, minecraft:darkoak_wall_sign",
        "minecraft:birch_sign, minecraft:birch_standing_sign",
        "minecraft:red_wall_banner, minecraft:wall_banner",
        "minecraft:potted_poppy, minecraft:flower_pot",
        "minecraft:repeater, minecraft:unpowered_repeater",
        "minecraft:stone, minecraft:stone",
    })
    void reportsAJavaBlockUnderBedrocksName(String java, String bedrock) {
        assertEquals(bedrock, BedrockBlockNames.bedrock(java));
    }

    @Test
    void reportsAStateUnderTheIdBedrockGivesIt() {
        assertEquals("minecraft:lit_furnace",
            BedrockBlockNames.bedrock(Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT, true)));
        assertEquals("minecraft:normal_stone_double_slab", BedrockBlockNames.bedrock(
            Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
        assertEquals("minecraft:waxed_double_cut_copper_slab", BedrockBlockNames.bedrock(
            block("waxed_cut_copper_slab").setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)));
        assertEquals("minecraft:light_block_15", BedrockBlockNames.bedrock(Blocks.LIGHT.defaultBlockState()));
    }

    /** Every block the game has reads back as itself through the name it is reported under. */
    @Test
    void readsBackWhatItReports() {
        List<String> lost = new ArrayList<>();
        for (net.minecraft.world.level.block.Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            String bedrock = BedrockBlockNames.bedrock(state);
            BedrockBlockNames.Resolved back = BedrockBlockNames.state(bedrock, Map.of(), reported::add);
            if (back == null || back.state().getBlock() != block && !splitByBedrock(block)) {
                lost.add(BuiltInRegistries.BLOCK.getKey(block) + " -> " + bedrock);
            }
        }
        assertTrue(lost.isEmpty(), lost.toString());
    }

    /** The Java blocks Bedrock keeps as one id with another: wall forms, colours, pots and plants. */
    private static boolean splitByBedrock(net.minecraft.world.level.block.Block block) {
        String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return name.contains("wall_") || name.endsWith("_banner") || name.endsWith("_bed")
            || name.startsWith("potted_") || name.startsWith("attached_") || name.endsWith("_plant")
            || name.endsWith("cauldron") || name.endsWith("_air") || name.equals("tall_seagrass")
            || name.equals("big_dripleaf_stem");
    }

    private static BlockState block(String java) {
        return BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse("minecraft:" + java))
            .defaultBlockState();
    }

    private BlockState state(String bedrock, Map<String, ?> states) {
        BedrockBlockNames.Resolved resolved = BedrockBlockNames.state(bedrock, states, reported::add);
        return resolved == null ? null : resolved.state();
    }
}
