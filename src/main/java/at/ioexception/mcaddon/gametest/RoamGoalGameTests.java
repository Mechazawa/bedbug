package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
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
     * `minecraft:behavior.move_towards_target` walks towards a target only while it is within
     * `within_radius`, which is four blocks here, so a pig three blocks off is walked towards.
     */
    @GameTest(structure = EMPTY, maxTicks = WALK_TICKS)
    public void aWalkTowardsATargetWithinItsRadiusSetsOff(GameTestHelper helper) {
        floor(helper);
        AddonEntity roamer = roamer(helper, "tp:roamer_toward", new BlockPos(1, 1, 4));
        if (roamer == null) {
            helper.succeed();
            return;
        }

        roamer.setTarget(standing(helper, "pig", new BlockPos(4, 1, 4)));
        Vec3 from = roamer.position();
        helper.succeedWhen(() -> helper.assertTrue(roamer.position().distanceTo(from) > 1,
            ROAMER + " never walked towards a target three blocks off."));
    }

    /** The same walk stays put while its target stands six blocks off, past its four. */
    @GameTest(structure = EMPTY, maxTicks = WALK_TICKS)
    public void aWalkTowardsATargetPastItsRadiusStaysPut(GameTestHelper helper) {
        floor(helper);
        AddonEntity roamer = roamer(helper, "tp:roamer_toward", new BlockPos(1, 1, 4));
        if (roamer == null) {
            helper.succeed();
            return;
        }

        roamer.setTarget(standing(helper, "pig", new BlockPos(7, 1, 4)));
        Vec3 from = roamer.position();
        helper.onEachTick(() -> helper.assertTrue(roamer.position().distanceTo(from) < STILL,
            ROAMER + " walks towards a target within four blocks and set off towards one six blocks off."));
        helper.runAfterDelay(WALK_TICKS - 10, helper::succeed);
    }

    /** Less than any step a walk takes. */
    private static final double STILL = 0.5;

    /**
     * `minecraft:behavior.move_to_water` looks at `search_count` blocks picked at random each tick.
     * One block a tick over a search box of eighteen thousand finds a single water block five blocks
     * off about once in five hundred runs within two seconds, while a count of nothing looks at the
     * whole box at once and walks straight to it. The floor stands high, so the box holds no water of
     * the tests beside this one.
     */
    @GameTest(structure = EMPTY, maxTicks = WALK_TICKS)
    public void aWaterSearchLooksAtItsCountOfBlocksEachTick(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(0, HIGH, 0), new BlockPos(7, HIGH, 7))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos pool = new BlockPos(6, HIGH, 2);
        helper.setBlock(pool.below(), Blocks.STONE);
        helper.setBlock(pool, Blocks.WATER);
        AddonEntity sampling = roamer(helper, "tp:roamer_sampling", new BlockPos(1, HIGH + 1, 7));
        AddonEntity scanning = roamer(helper, "tp:roamer_scanning", new BlockPos(1, HIGH + 1, 1));
        if (sampling == null || scanning == null) {
            helper.succeed();
            return;
        }

        Vec3 from = sampling.position();
        Vec3 water = Vec3.atBottomCenterOf(helper.absolutePos(pool));
        long begun = helper.getLevel().getGameTime();
        helper.onEachTick(() -> helper.assertTrue(helper.getLevel().getGameTime() - begun > SAMPLED_FOR
                || sampling.position().distanceTo(from) < STILL,
            ROAMER + " looks at one block a tick and set off for the water at once."));
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getGameTime() - begun > SAMPLED_FOR, "Still sampling.");
            helper.assertTrue(Math.hypot(scanning.getX() - water.x, scanning.getZ() - water.z) < AT_THE_POOL,
                ROAMER + " looks at every block at once and never reached the water.");
        });
    }

    /** Further above the structure than any search of the tests beside it reaches down. */
    private static final int HIGH = 20;

    /** How long the one block a tick search is watched for. */
    private static final int SAMPLED_FOR = 40;

    /** Beside the pool, where a mob that walks to water stops. */
    private static final double AT_THE_POOL = 1.5;

    /**
     * `minecraft:behavior.move_to_liquid` walks to the liquid its `material_type` names, here water
     * past a nearer pool of lava, and counts as there within its `goal_radius` of two and a half
     * blocks, so it never steps in.
     */
    @GameTest(structure = EMPTY, maxTicks = WALK_TICKS)
    public void aWalkToALiquidTakesItsMaterialAndStopsWithinItsRadius(GameTestHelper helper) {
        raisedFloor(helper);
        BlockPos lava = new BlockPos(5, RAISED, 1);
        BlockPos water = new BlockPos(0, RAISED, 1);
        helper.setBlock(lava.below(), Blocks.STONE);
        helper.setBlock(water.below(), Blocks.STONE);
        helper.setBlock(lava, Blocks.LAVA);
        helper.setBlock(water, Blocks.WATER);
        AddonEntity roamer = roamer(helper, "tp:roamer_wading", new BlockPos(6, RAISED + 1, 6));
        if (roamer == null) {
            helper.succeed();
            return;
        }

        Vec3 pool = Vec3.atBottomCenterOf(helper.absolutePos(water));
        helper.onEachTick(() -> helper.assertFalse(roamer.isInWater() || roamer.isInLava(),
            ROAMER + " counts two and a half blocks off as there and stepped into a liquid."));
        helper.succeedWhen(() -> helper.assertTrue(roamer.position().distanceTo(pool) <= WADE_RADIUS,
            ROAMER + " walks to water and stands " + roamer.position().distanceTo(pool) + " from it."));
    }

    /** The wading group's goal radius and the half block the walk may stop short by. */
    private static final double WADE_RADIUS = 3;

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

    private static boolean runs(AddonEntity roamer, String goal) {
        return roamer.runningGoals().stream().anyMatch(running -> running.startsWith(goal));
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

    /** One of the game's own mobs, with no goals of its own, where the test puts it. */
    private static Mob standing(GameTestHelper helper, String kind, BlockPos where) {
        ServerLevel level = helper.getLevel();
        Entity created = Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", kind)).orElseThrow(), level);
        helper.assertTrue(created instanceof Mob, "A " + kind + " could not be made.");

        Mob mob = (Mob) created;
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(mob, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setNoAi(true);
        level.addFreshEntity(mob);
        return mob;
    }
}
