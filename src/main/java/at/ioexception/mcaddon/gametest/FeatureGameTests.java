package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * The test pack's features, each placed with `/bedrock place feature` and read back block by block.
 *
 * Every feature here places a gold block into an empty structure, so whatever gold stands in the
 * structure afterwards is what the feature placed.
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
