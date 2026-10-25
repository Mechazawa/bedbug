package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;

/**
 * The goals that carry and place blocks, lay eggs, work at a station and hand items over, each read
 * back off the world through `tp:keeper`, a plain mob whose groups carry one goal each.
 */
public final class KeeperGoalGameTests {
    private static final String KEEPER = "tp:keeper";

    private static final BlockPos STAND = new BlockPos(2, 0, 2);

    /**
     * `minecraft:behavior.take_block` lifts only a block its `blocks` lists. Seven stone blocks and
     * one gold block stand around the keeper, and only the gold may go.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aTakeBlockGoalLiftsOnlyTheBlocksItLists(GameTestHelper helper) {
        BlockPos gold = STAND.east();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) helper.setBlock(STAND.offset(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(gold, Blocks.GOLD_BLOCK);
        AddonEntity keeper = keeper(helper, "tp:keeper_take");
        if (keeper == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(Boolean.TRUE.equals(keeper.value("tp:took")), KEEPER + " has taken no block.");
            helper.assertBlockPresent(Blocks.AIR, gold);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos at = STAND.offset(x, 0, z);
                    if ((x != 0 || z != 0) && !at.equals(gold)) helper.assertBlockPresent(Blocks.STONE, at);
                }
            }
            helper.assertTrue("minecraft:gold_block".equals(keeper.value("carried_block_identifier")),
                KEEPER + " carries " + keeper.value("carried_block_identifier") + " and not the gold.");
        });
    }

    /**
     * `minecraft:behavior.place_block` places nothing while `can_place` fails, then one of its
     * `randomly_placeable_blocks` in its states, carrying nothing itself, and raises `on_place`. The
     * gold entry outweighs the log and its filter never passes, so the log is the only block it may
     * place.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void aPlaceBlockGoalPlacesARandomBlockOnceItsFilterPasses(GameTestHelper helper) {
        AddonEntity keeper = keeper(helper, "tp:keeper_place");
        if (keeper == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenExecuteAfter(REFUSED_TICKS, () -> {
                helper.assertBlockPresent(Blocks.AIR, STAND);
                helper.assertFalse(Boolean.TRUE.equals(keeper.value("tp:placed")),
                    KEEPER + " placed a block while its can_place filter failed.");
                keeper.addTag("tp_may_place");
            })
            .thenWaitUntil(() -> {
                BlockState placed = helper.getBlockState(STAND);
                helper.assertTrue(placed.is(Blocks.OAK_LOG)
                        && placed.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X,
                    KEEPER + " places an oak log lying along x and placed " + placed + ".");
                helper.assertTrue(Boolean.TRUE.equals(keeper.value("tp:placed")),
                    KEEPER + " placed its block and never raised on_place.");
            })
            .thenSucceed();
    }

    /** Ticks a goal checked every second tick has to place a block it is allowed to. */
    private static final int REFUSED_TICKS = 30;

    /**
     * `allow_laying_from_below` lays an egg on a gold block from underneath it. The keeper cannot
     * move, so the spot over the gold is out of its reach and only the rule lets it lay.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aLayEggGoalAllowedFromBelowLaysFromUnderneathItsNest(GameTestHelper helper) {
        BlockPos nest = STAND.above();
        helper.setBlock(nest, Blocks.GOLD_BLOCK);
        AddonEntity keeper = keeper(helper, "tp:keeper_make_laying_from_below");
        if (keeper == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.TURTLE_EGG, nest.above());
            helper.assertTrue(Boolean.TRUE.equals(keeper.value("tp:laid")), KEEPER + " laid and never raised on_lay.");
        });
    }

    /**
     * `use_default_animation` is Java's turtle digging while it lays, which a sculk sensor beside the
     * nest hears as a vibration; with it off the same laying is silent until the egg is down. The
     * padding keeps every other test's footsteps out of the sensor's reach.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120, padding = SENSOR_PADDING)
    public void aLayEggGoalDigsOnlyWithItsDefaultAnimation(GameTestHelper helper) {
        layingHeard(helper, "tp:keeper_make_laying", true);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120, padding = SENSOR_PADDING)
    public void aLayEggGoalWithoutItsDefaultAnimationLaysSilently(GameTestHelper helper) {
        layingHeard(helper, "tp:keeper_make_laying_quietly", false);
    }

    /** Further than a sculk sensor hears, from the edge of a test to the next one. */
    private static final int SENSOR_PADDING = 16;

    private static void layingHeard(GameTestHelper helper, String event, boolean heard) {
        helper.setBlock(STAND.below(), Blocks.GOLD_BLOCK);
        BlockPos sensor = STAND.east(3);
        helper.setBlock(sensor, Blocks.SCULK_SENSOR);
        AddonEntity keeper = keeper(helper, event);
        if (keeper == null) {
            helper.succeed();
            return;
        }

        boolean[] stirred = {false};
        helper.onEachTick(() -> {
            if (helper.getBlockState(STAND).is(Blocks.TURTLE_EGG)) return;
            BlockState state = helper.getBlockState(sensor);
            stirred[0] |= state.is(Blocks.SCULK_SENSOR) && SculkSensorBlock.getPhase(state) != SculkSensorPhase.INACTIVE;
        });
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.TURTLE_EGG, STAND);
            helper.assertTrue(stirred[0] == heard, KEEPER + (heard
                ? " laid with its default animation and the sensor beside it heard no digging."
                : " laid without its default animation and the sensor beside it heard digging."));
        });
    }

    /**
     * `minecraft:behavior.work` with `work_in_rain_tolerance` keeps working through rain that comes
     * when no more of its `active_time` ticks are left than the tolerance, and a worker stating
     * neither `can_work_in_rain` nor a tolerance stops at the first drop. The batch's own weather
     * environment keeps the rain away from every other test.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true,
        environment = CLEAR_SKY)
    public void aWorkerKeepsWorkingInRainOnlyWithinItsTolerance(GameTestHelper helper) {
        workingInRain(helper, "working", Blocks.LECTERN);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200, skyAccess = true,
        environment = CLEAR_SKY)
    public void aCompostingWorkerKeepsWorkingInRainOnlyWithinItsTolerance(GameTestHelper helper) {
        workingInRain(helper, "composting", Blocks.COMPOSTER);
    }

    /** A test environment of clear weather, the mod's own so that its batch runs apart. */
    private static final String CLEAR_SKY = "mcaddon:gametest/clear_sky";

    /**
     * Into the tolerant worker's 200 tick shift, which with the second or so the rain takes to set
     * in leaves it fewer than its 150.
     */
    private static final int RAIN_AFTER = 60;

    private static void workingInRain(GameTestHelper helper, String goal, net.minecraft.world.level.block.Block station) {
        BlockPos tolerantAt = new BlockPos(1, 0, 1);
        BlockPos strictAt = new BlockPos(1, 0, 4);
        helper.setBlock(tolerantAt.east(), station);
        helper.setBlock(strictAt.east(), station);
        AddonEntity tolerant = keeper(helper, "tp:keeper_make_" + goal + "_tolerant", tolerantAt);
        AddonEntity strict = keeper(helper, "tp:keeper_make_" + goal + "_strict", strictAt);
        if (tolerant == null || strict == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenWaitUntil(() -> {
                helper.assertTrue(Boolean.TRUE.equals(tolerant.value("tp:arrived")), KEEPER + " never reached its station.");
                helper.assertTrue(Boolean.TRUE.equals(strict.value("tp:arrived")), KEEPER + " never reached its station.");
            })
            .thenExecuteAfter(RAIN_AFTER, () -> {
                helper.assertTrue(working(tolerant) && working(strict), KEEPER + " stopped working under a clear sky.");
                Platform.setWeather(helper.getLevel(), true, false, RAIN_TICKS);
            })
            .thenWaitUntil(() -> helper.assertTrue(
                helper.getLevel().isRainingAt(helper.absolutePos(tolerantAt.east().above())),
                "No rain falls on the station."))
            .thenExecuteAfter(RAIN_NOTICED, () -> {
                helper.assertTrue(working(tolerant),
                    KEEPER + " stopped in rain with fewer ticks of its shift left than its tolerance.");
                helper.assertFalse(working(strict), KEEPER + " that may not work in rain kept working in it.");
            })
            .thenExecute(() -> Platform.setWeather(helper.getLevel(), false, false, RAIN_TICKS))
            .thenSucceed();
    }

    private static final int RAIN_TICKS = 6000;

    /** Past the tick a goal is next asked whether it may go on, once the rain has set in. */
    private static final int RAIN_NOTICED = 6;

    private static boolean working(AddonEntity keeper) {
        return keeper.runningGoals().stream().anyMatch(goal -> goal.startsWith("WorkGoal"));
    }

    /**
     * `goal_cooldown` holds a worker away from its station for that many ticks after a shift ends.
     * Without it the worker comes back as soon as the goal next looks for a station, about ten
     * seconds on.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 700)
    public void aWorkerRestsItsGoalCooldownBetweenShifts(GameTestHelper helper) {
        workingAgain(helper, "working", Blocks.LECTERN);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 700)
    public void aCompostingWorkerRestsItsGoalCooldownBetweenShifts(GameTestHelper helper) {
        workingAgain(helper, "composting", Blocks.COMPOSTER);
    }

    /** Past the station search's ten seconds after a 20 tick shift, inside the 400 tick cooldown. */
    private static final int STILL_RESTING = 300;

    private static void workingAgain(GameTestHelper helper, String goal, net.minecraft.world.level.block.Block station) {
        helper.setBlock(STAND.east(), station);
        AddonEntity keeper = keeper(helper, "tp:keeper_make_" + goal + "_cooling");
        if (keeper == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(Boolean.TRUE.equals(keeper.value("tp:arrived")),
                KEEPER + " never reached its station."))
            .thenExecute(() -> keeper.setValue("tp:arrived", false))
            .thenExecuteAfter(STILL_RESTING, () -> helper.assertFalse(Boolean.TRUE.equals(keeper.value("tp:arrived")),
                KEEPER + " came back to work inside its goal cooldown."))
            .thenWaitUntil(() -> helper.assertTrue(Boolean.TRUE.equals(keeper.value("tp:arrived")),
                KEEPER + " never came back to work after its goal cooldown."))
            .thenSucceed();
    }

    private static AddonEntity keeper(GameTestHelper helper, String event) {
        return keeper(helper, event, STAND);
    }

    private static AddonEntity keeper(GameTestHelper helper, String event, BlockPos at) {
        AddonEntity keeper = BehaviourGameTests.spawn(helper, KEEPER, at);
        if (keeper == null) return null;

        keeper.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        EntityEvents.trigger(keeper, event, null, ServerHooks.state());
        keeper.setNoAi(false);
        return keeper;
    }
}
