package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The test pack's features, each placed with `/bedrock place feature` and read back block by block.
 *
 * Every feature here places a gold block, or a carved pumpkin or a flower, into an empty structure,
 * so whatever gold stands in the structure afterwards is what the feature placed.
 */
public final class FeatureGameTests {

    /**
     * `tp:mark_grid` walks a fixed grid of two by three cells for three iterations, evaluating z
     * before x, so it fills the first column of three along z and leaves the second empty.
     * `tp:mark_spread` scatters over a uniform extent of 2 to 5, which leaves 5 out, at a height
     * worked out from the x it already chose.
     */
    @GameTest()
    public void aScatterSpreadsOverItsExtentInTheOrderItEvaluates(GameTestHelper helper) {
        if (noPack()) {
            helper.succeed();
            return;
        }

        BlockPos grid = helper.absolutePos(new BlockPos(0, 1, 0));
        List<String> heard = place(helper, "tp:mark_grid", grid);
        for (int z = 0; z < 3; z++) {
            helper.assertTrue(gold(helper, grid.offset(0, 0, z)), "the grid left x 0, z " + z + " empty: " + heard);
            helper.assertFalse(gold(helper, grid.offset(1, 0, z)), "the grid stepped x before z at z " + z);
        }

        BlockPos spread = helper.absolutePos(new BlockPos(0, 4, 0));
        heard = place(helper, "tp:mark_spread", spread);
        int found = 0;
        for (BlockPos at : BlockPos.betweenClosed(spread, spread.offset(7, 3, 7))) {
            if (!gold(helper, at)) continue;
            found++;
            BlockPos offset = at.subtract(spread);
            helper.assertTrue(offset.getX() >= 2 && offset.getX() <= 4 && offset.getZ() >= 2 && offset.getZ() <= 4,
                "the spread placed outside its extent at " + offset.toShortString());
            helper.assertTrue(offset.getY() == Math.abs(at.getX()) % 2,
                "the spread's height did not read the x it chose at " + offset.toShortString());
        }
        helper.assertTrue(found > 0, "the spread placed nothing: " + heard);
        helper.succeed();
    }

    /** `tp:mark_grids` places `tp:mark_grid` twice, four blocks apart, and each draws its own column. */
    @GameTest()
    public void aScatterPlacesAnotherScatter(GameTestHelper helper) {
        if (noPack()) {
            helper.succeed();
            return;
        }

        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        List<String> heard = place(helper, "tp:mark_grids", origin);
        for (int x : List.of(0, 4)) {
            for (int z = 0; z < 3; z++) {
                helper.assertTrue(gold(helper, origin.offset(x, 0, z)),
                    "the inner scatter left x " + x + ", z " + z + " empty: " + heard);
                helper.assertFalse(gold(helper, origin.offset(x + 1, 0, z)),
                    "the inner scatter filled x " + (x + 1) + ", z " + z);
            }
        }
        helper.succeed();
    }

    /**
     * Three aggregates of a mark one block east, a flower that fails in the air and a mark two blocks
     * east.
     * With no early out both marks stand; stopping at the first failure leaves only the first; and
     * stopping at the first success, after the failing one, leaves only the one block east.
     */
    @GameTest()
    public void anAggregateStopsWhereItsEarlyOutSays(GameTestHelper helper) {
        if (noPack()) {
            helper.succeed();
            return;
        }

        BlockPos all = helper.absolutePos(new BlockPos(0, 3, 0));
        List<String> heard = place(helper, "tp:marks_all", all);
        helper.assertTrue(gold(helper, all.east()) && gold(helper, all.east(2)),
            "an aggregate with no early out skipped a feature: " + heard);

        BlockPos failure = helper.absolutePos(new BlockPos(0, 3, 2));
        place(helper, "tp:marks_until_failure", failure);
        helper.assertTrue(gold(helper, failure.east()), "first_failure skipped the feature before the failure.");
        helper.assertFalse(gold(helper, failure.east(2)), "first_failure placed the feature after the failure.");

        BlockPos success = helper.absolutePos(new BlockPos(0, 3, 4));
        place(helper, "tp:marks_until_success", success);
        helper.assertTrue(gold(helper, success.east()), "first_success stopped at the failure.");
        helper.assertFalse(gold(helper, success.east(2)), "first_success placed the feature after the success.");
        helper.succeed();
    }

    /**
     * A single block goes only onto what `may_replace` lists, against what `may_attach_to` asks for,
     * away from what `may_not_attach_to` names and, under `enforce_survivability_rules`, where the
     * block can stay; `randomize_rotation` turns it.
     */
    @GameTest()
    public void aSingleBlockKeepsToWhereItMayGo(GameTestHelper helper) {
        if (noPack()) {
            helper.succeed();
            return;
        }

        BlockPos air = helper.absolutePos(new BlockPos(0, 1, 0));
        place(helper, "tp:mark_on_bedrock", air);
        helper.assertFalse(gold(helper, air), "may_replace let the block replace air.");
        BlockPos bedrock = helper.absolutePos(new BlockPos(1, 1, 0));
        helper.getLevel().setBlock(bedrock, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        List<String> heard = place(helper, "tp:mark_on_bedrock", bedrock);
        helper.assertTrue(gold(helper, bedrock), "may_replace kept the block off bedrock: " + heard);

        BlockPos open = helper.absolutePos(new BlockPos(3, 1, 0));
        place(helper, "tp:mark_under_stone", open);
        helper.assertFalse(gold(helper, open), "may_attach_to top let the block stand with nothing above it.");
        BlockPos covered = helper.absolutePos(new BlockPos(5, 1, 0));
        helper.getLevel().setBlock(covered.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        place(helper, "tp:mark_under_stone", covered);
        helper.assertTrue(gold(helper, covered), "may_attach_to top kept the block from under stone.");

        BlockPos oneSide = helper.absolutePos(new BlockPos(1, 1, 3));
        helper.getLevel().setBlock(oneSide.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        place(helper, "tp:mark_between_stone", oneSide);
        helper.assertFalse(gold(helper, oneSide), "min_sides_must_attach 2 took one side.");
        BlockPos twoSides = helper.absolutePos(new BlockPos(5, 1, 3));
        helper.getLevel().setBlock(twoSides.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.getLevel().setBlock(twoSides.west(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        place(helper, "tp:mark_between_stone", twoSides);
        helper.assertTrue(gold(helper, twoSides), "min_sides_must_attach 2 refused two sides.");

        BlockPos nearIron = helper.absolutePos(new BlockPos(0, 3, 6));
        helper.getLevel().setBlock(nearIron.below(), Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        place(helper, "tp:mark_shy", nearIron);
        helper.assertFalse(gold(helper, nearIron), "may_not_attach_to let the block stand on iron.");
        BlockPos clear = helper.absolutePos(new BlockPos(3, 3, 6));
        place(helper, "tp:mark_shy", clear);
        helper.assertTrue(gold(helper, clear), "may_not_attach_to kept the block from clear air.");

        BlockPos onStone = helper.absolutePos(new BlockPos(0, 5, 0));
        helper.getLevel().setBlock(onStone.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        place(helper, "tp:glow_bloom", onStone);
        helper.assertTrue(helper.getLevel().getBlockState(onStone).isAir(),
            "enforce_survivability_rules let a flower stand on stone.");
        BlockPos onGrass = helper.absolutePos(new BlockPos(2, 5, 0));
        helper.getLevel().setBlock(onGrass.below(), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        heard = place(helper, "tp:glow_bloom", onGrass);
        helper.assertTrue(helper.getLevel().getBlockState(onGrass).is(Blocks.DANDELION)
            || helper.getLevel().getBlockState(onGrass).is(Blocks.POPPY), "no flower stands on grass: " + heard);

        Set<Direction> facings = new HashSet<>();
        for (int x = 0; x < 8; x++) {
            for (int z = 2; z < 6; z += 3) {
                BlockPos at = helper.absolutePos(new BlockPos(x, 6, z));
                place(helper, "tp:turned_pumpkin", at);
                if (helper.getLevel().getBlockState(at).is(Blocks.CARVED_PUMPKIN)) {
                    facings.add(helper.getLevel().getBlockState(at).getValue(BlockStateProperties.HORIZONTAL_FACING));
                }
            }
        }
        helper.assertTrue(facings.size() > 1, "randomize_rotation turned sixteen pumpkins only " + facings);
        helper.succeed();
    }

    private static boolean noPack() {
        return AddonEntities.typeOf("tp:ember") == null || !Platform.convertsWorldgen();
    }

    private static List<String> place(GameTestHelper helper, String feature, BlockPos at) {
        return BedrockCommandGameTests.typed(helper,
            "place feature " + feature + " " + at.getX() + " " + at.getY() + " " + at.getZ());
    }

    private static boolean gold(GameTestHelper helper, BlockPos at) {
        return helper.getLevel().getBlockState(at).is(Blocks.GOLD_BLOCK);
    }
}
