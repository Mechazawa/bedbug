package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The fields of the goals that walk an entity somewhere, read back off where it walked.
 *
 * `tp:roamer` is a plain mob with no goal at the top level. Each of its groups carries one walking
 * behaviour with the fields a test here asks about, switched on by the event of the same name. The
 * tests stand it on a stone floor laid over the structure, because Java's random walks never end on
 * the empty structure's own floor.
 */
public final class RoamGoalGameTests {
    private static final String ROAMER = "tp:roamer";
    private static final String EMPTY = "fabric-gametest-api-v1:empty";

    /** Long enough for a walk across the structure and Java's second between goal checks. */
    private static final int WALK_TICKS = 160;

    /**
     * `minecraft:behavior.move_to_block` passes over a block its `target_block_filters` refuse: the
     * nearer slab holds water, and the filter asks for one that does not.
     */
    @GameTest(structure = EMPTY, maxTicks = WALK_TICKS)
    public void aWalkToABlockPassesOverABlockItsFiltersRefuse(GameTestHelper helper) {
        raisedFloor(helper);
        BlockPos wet = new BlockPos(3, RAISED + 1, 4);
        BlockPos dry = new BlockPos(7, RAISED + 1, 4);
        helper.setBlock(wet, Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        helper.setBlock(dry, Blocks.OAK_SLAB);
        AddonEntity roamer = roamer(helper, "tp:roamer_choosy", new BlockPos(1, RAISED + 1, 4));
        if (roamer == null) {
            helper.succeed();
            return;
        }

        Vec3 slab = Vec3.atBottomCenterOf(helper.absolutePos(dry));
        helper.succeedWhen(() -> helper.assertTrue(Math.hypot(roamer.getX() - slab.x, roamer.getZ() - slab.z) < 1.5,
            ROAMER + " walks to a slab with no water in it and stands " + roamer.position().distanceTo(slab)
                + " from the dry one."));
    }

    /**
     * `minecraft:behavior.move_to_block` with `target_selection_method` set to `random` takes any
     * matching block in range rather than the nearest. Of eight roamers between a near gold block
     * and a far one, some walk to the far one.
     */
    @GameTest(structure = EMPTY, maxTicks = WALK_TICKS)
    public void aRandomBlockWalkTakesAnyMatchingBlockInRange(GameTestHelper helper) {
        raisedFloor(helper);
        BlockPos near = new BlockPos(2, RAISED, 4);
        BlockPos far = new BlockPos(7, RAISED, 4);
        helper.setBlock(near, Blocks.GOLD_BLOCK);
        helper.setBlock(far, Blocks.GOLD_BLOCK);
        List<AddonEntity> roamers = new ArrayList<>();
        for (int i = 0; i < CHOOSERS; i++) {
            AddonEntity roamer = roamer(helper, "tp:roamer_fickle", new BlockPos(1, RAISED + 1, 1 + i % 6));
            if (roamer == null) {
                helper.succeed();
                return;
            }
            roamers.add(roamer);
        }

        Vec3 gold = Vec3.atBottomCenterOf(helper.absolutePos(far));
        helper.succeedWhen(() -> helper.assertTrue(roamers.stream()
                .anyMatch(roamer -> Math.hypot(roamer.getX() - gold.x, roamer.getZ() - gold.z) < 2),
            "None of " + CHOOSERS + " roamers picking a gold block at random walked to the far one."));
    }

    /** Enough that all of them picking the near block by chance is one run in two hundred and fifty. */
    private static final int CHOOSERS = 8;

    /** A roamer with one group on and its goals running. */
    private static AddonEntity roamer(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity roamer = BehaviourGameTests.spawn(helper, ROAMER, where);
        if (roamer == null) return null;

        EntityEvents.trigger(roamer, event, null, ServerHooks.state());
        roamer.setNoAi(false);
        return roamer;
    }

    /** A stone floor over the whole structure, which the entities stand on at a height of one. */
    private static void floor(GameTestHelper helper) {
        BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(7, 0, 7)).forEach(at -> helper.setBlock(at, Blocks.STONE));
    }

    /**
     * Height of a stone floor built above the structure, for a test whose search reaches every height
     * in its range and would otherwise find the blocks of the tests beside it.
     */
    private static final int RAISED = 8;

    private static void raisedFloor(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(0, RAISED, 0), new BlockPos(7, RAISED, 7))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
    }
}
