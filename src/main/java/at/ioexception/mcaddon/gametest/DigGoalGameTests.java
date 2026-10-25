package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The goals that look for a block and do something there: Bedrock's sniffer digging, its rabbit and
 * fox raiding a garden and its librarian looking for a shelf.
 *
 * `tp:digger` is a plain mob whose groups each carry one of those goals, and whose events set a
 * property for each moment of the dig. Every test builds its own stone floor high over the
 * structure, so no other test's blocks are within any search here.
 */
public final class DigGoalGameTests {
    private static final String DIGGER = "tp:digger";

    /** Height of the floor every test here builds, clear of the other block searching tests. */
    private static final int FLOOR = 12;

    private static final int STAND_Y = FLOOR + 1;

    /** Past Java's check cadence, which looks at a goal that is not running every second tick. */
    private static final int SLACK = 3;

    /**
     * A dig walks to gold, digs for its `digging_duration_range` of one second, turns up the emerald
     * its `item_table` holds `spawn_item_after_seconds` into the dig and `spawn_item_pos_offset` two
     * blocks ahead, and raises `on_searching_start`, `on_digging_start`, `on_item_found` and
     * `on_success` on the way.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 160)
    public void aDigTurnsUpItsItemPartWayThroughAndEndsAfterItsDuration(GameTestHelper helper) {
        floor(helper, Blocks.GOLD_BLOCK);
        AddonEntity digger = digger(helper, "tp:digger_make_digging", new BlockPos(3, STAND_Y, 3));
        if (digger == null) {
            helper.succeed();
            return;
        }

        long begun = helper.getLevel().getGameTime();
        long[] dugAt = {-1};
        long[] itemAt = {-1};
        long[] succeededAt = {-1};
        double[] itemReach = {-1};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime() - begun;
            if (dugAt[0] < 0 && on(digger, "tp:dug")) dugAt[0] = now;
            if (succeededAt[0] < 0 && on(digger, "tp:succeeded")) succeededAt[0] = now;
            if (itemAt[0] >= 0) return;

            List<ItemEntity> emeralds = emeralds(helper);
            if (emeralds.isEmpty()) return;

            itemAt[0] = now;
            Vec3 item = emeralds.get(0).position();
            itemReach[0] = Math.hypot(item.x - digger.getX(), item.z - digger.getZ());
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(on(digger, "tp:searched"), DIGGER + " never raised on_searching_start.");
            helper.assertTrue(on(digger, "tp:succeeded"), DIGGER + " has not finished digging.");
            helper.assertTrue(on(digger, "tp:found"), DIGGER + " never raised on_item_found.");
            helper.assertTrue(itemAt[0] >= 0, DIGGER + " dug up nothing from its item table.");
            helper.assertTrue(Math.abs(itemAt[0] - dugAt[0] - HALF_SECOND) <= SLACK,
                DIGGER + " turns its item up half a second into the dig and did at tick "
                    + (itemAt[0] - dugAt[0]) + ".");
            helper.assertTrue(Math.abs(succeededAt[0] - dugAt[0] - ONE_SECOND) <= SLACK,
                DIGGER + " digs for one second and dug for " + (succeededAt[0] - dugAt[0]) + " ticks.");
            helper.assertTrue(Math.abs(itemReach[0] - ITEM_OFFSET) < 0.4,
                DIGGER + " drops its item two blocks ahead and dropped it " + itemReach[0] + " off.");
        });
    }

    private static final int HALF_SECOND = 10;
    private static final int ONE_SECOND = 20;
    private static final double ITEM_OFFSET = 2;

    /**
     * A dig looks `search_range_xz` blocks either way. Gold five blocks off and further is out of the
     * three the group says, so it never starts.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = NEVER_TICKS + 20)
    public void aDigLooksNoFurtherThanItsRange(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        BlockPos.betweenClosed(new BlockPos(0, FLOOR, 0), new BlockPos(7, FLOOR, 7)).forEach(at -> {
            if (at.getX() >= 6 || at.getZ() >= 6) helper.setBlock(at, Blocks.GOLD_BLOCK);
        });
        neverSearches(helper, digger(helper, "tp:digger_make_digging", new BlockPos(1, STAND_Y, 1)),
            "looks three blocks either way and found gold five blocks off.");
    }

    /**
     * A dig looks `search_range_y` blocks up and down. Gold to stand on three blocks down, through a
     * hole in the floor, is out of the one the group says.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = NEVER_TICKS + 20)
    public void aDigLooksNoDeeperThanItsHeight(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        BlockPos.betweenClosed(new BlockPos(4, FLOOR, 4), new BlockPos(6, FLOOR, 6)).forEach(at -> {
            helper.setBlock(at, Blocks.AIR);
            helper.setBlock(at.below(3), Blocks.GOLD_BLOCK);
        });
        neverSearches(helper, digger(helper, "tp:digger_make_digging", new BlockPos(2, STAND_Y, 2)),
            "looks one block up and down and found gold three blocks down.");
    }

    /** Long enough for two hundred draws a look to have found what is in range many times over. */
    private static final int NEVER_TICKS = 60;

    private static void neverSearches(GameTestHelper helper, AddonEntity digger, String failure) {
        if (digger == null) {
            helper.succeed();
            return;
        }
        helper.onEachTick(() -> helper.assertFalse(on(digger, "tp:searched"), DIGGER + " " + failure));
        helper.runAfterDelay(NEVER_TICKS, helper::succeed);
    }

    /**
     * A look draws `find_valid_position_retries` places. The patient group draws five thousand in a
     * box of 243, which finds the one gold block on its first look; five draws a look would not.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aDigDrawsAsManyPlacesALookAsItsRetries(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        helper.setBlock(new BlockPos(5, FLOOR, 5), Blocks.GOLD_BLOCK);
        AddonEntity digger = digger(helper, "tp:digger_make_patient", new BlockPos(1, STAND_Y, 1));
        if (digger == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(FIRST_LOOK, () -> {
            helper.assertTrue(on(digger, "tp:searched"),
                DIGGER + " draws five thousand places a look and missed the gold on its first.");
            helper.succeed();
        });
    }

    /** The goal's first look, past the tick the group goes on and the selector's second-tick cadence. */
    private static final int FIRST_LOOK = 4;

    /** A dig that ends waits its `cooldown_range` of three seconds before it searches again. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aDigWaitsItsCooldownBeforeSearchingAgain(GameTestHelper helper) {
        floor(helper, Blocks.GOLD_BLOCK);
        AddonEntity digger = digger(helper, "tp:digger_make_cooling", new BlockPos(3, STAND_Y, 3));
        if (digger == null) {
            helper.succeed();
            return;
        }

        long begun = helper.getLevel().getGameTime();
        long[] succeededAt = {-1};
        long[] againAt = {-1};
        boolean[] searching = {false};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime() - begun;
            boolean nowSearching = on(digger, "is_searching");
            if (succeededAt[0] < 0 && on(digger, "tp:succeeded")) succeededAt[0] = now;
            if (succeededAt[0] >= 0 && againAt[0] < 0 && nowSearching && !searching[0]) againAt[0] = now;
            searching[0] = nowSearching;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(againAt[0] >= 0, DIGGER + " has not searched again.");
            helper.assertTrue(againAt[0] - succeededAt[0] >= THREE_SECONDS - SLACK,
                DIGGER + " waits three seconds after a dig and searched again after "
                    + (againAt[0] - succeededAt[0]) + " ticks.");
        });
    }

    private static final int THREE_SECONDS = 60;

    /**
     * A search whose ground stops being a target block raises `on_fail_during_searching`. The digger
     * cannot move, so it is still on its way when the gold goes.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void aSearchThatLosesItsGroundFails(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        BlockPos.betweenClosed(new BlockPos(4, FLOOR, 0), new BlockPos(4, FLOOR, 4))
            .forEach(at -> helper.setBlock(at, Blocks.GOLD_BLOCK));
        AddonEntity digger = digger(helper, "tp:digger_make_digging", new BlockPos(1, STAND_Y, 1));
        if (digger == null) {
            helper.succeed();
            return;
        }
        digger.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);

        clearGoldOnceSet(helper, digger, "tp:searched");
        helper.succeedWhen(() -> {
            helper.assertTrue(on(digger, "tp:search_failed"),
                DIGGER + " lost the gold it walked to and never raised on_fail_during_searching.");
            helper.assertFalse(on(digger, "tp:dug"), DIGGER + " dug where there was no gold.");
        });
    }

    /** A dig whose ground stops being a target block raises `on_fail_during_digging` and no success. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void aDigThatLosesItsGroundFails(GameTestHelper helper) {
        floor(helper, Blocks.GOLD_BLOCK);
        AddonEntity digger = digger(helper, "tp:digger_make_digging", new BlockPos(3, STAND_Y, 3));
        if (digger == null) {
            helper.succeed();
            return;
        }

        clearGoldOnceSet(helper, digger, "tp:dug");
        helper.succeedWhen(() -> {
            helper.assertTrue(on(digger, "tp:dig_failed"),
                DIGGER + " lost the gold it dug and never raised on_fail_during_digging.");
            helper.assertFalse(on(digger, "tp:succeeded"), DIGGER + " finished a dig with no gold.");
        });
    }

    private static void clearGoldOnceSet(GameTestHelper helper, AddonEntity digger, String property) {
        boolean[] cleared = {false};
        helper.onEachTick(() -> {
            if (cleared[0] || !on(digger, property)) return;

            cleared[0] = true;
            BlockPos.betweenClosed(new BlockPos(0, FLOOR, 0), new BlockPos(7, FLOOR, 7))
                .forEach(at -> helper.setBlock(at, Blocks.STONE));
        });
    }

    /** A raider standing at its crop waits its `initial_eat_delay` of one second before eating it. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void aRaiderWaitsItsInitialDelayBeforeEating(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        BlockPos carrot = crop(helper, 2, 2);
        AddonEntity raider = digger(helper, "tp:digger_make_raiding_slow", carrot);
        if (raider == null) {
            helper.succeed();
            return;
        }

        long begun = helper.getLevel().getGameTime();
        long[] eatenAt = {-1};
        helper.onEachTick(() -> {
            if (eatenAt[0] < 0 && !standing(helper, carrot)) eatenAt[0] = helper.getLevel().getGameTime() - begun;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(eatenAt[0] >= 0, DIGGER + " has not eaten the carrot it stands at.");
            helper.assertTrue(eatenAt[0] >= ONE_SECOND,
                DIGGER + " waits a second at a carrot and ate it at tick " + eatenAt[0] + ".");
        });
    }

    /** A raider eats no more than its `max_to_eat` of two before it is full, with four carrots in a row. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FULL_TICKS + 20)
    public void aRaiderEatsNoMoreThanItsMaximum(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        List<BlockPos> carrots = List.of(crop(helper, 1, 2), crop(helper, 2, 2), crop(helper, 3, 2),
            crop(helper, 4, 2));
        if (digger(helper, "tp:digger_make_raiding_few", carrots.get(0)) == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(FULL_TICKS, () -> {
            long eaten = carrots.stream().filter(at -> !standing(helper, at)).count();
            helper.assertTrue(eaten == 2, DIGGER + " eats two carrots before it is full and ate " + eaten + ".");
            helper.succeed();
        });
    }

    /** Long enough for a raider with no wait between crops to have eaten all four. */
    private static final int FULL_TICKS = 120;

    /** A raider full after one carrot waits its `full_delay` of two seconds before it eats the next. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aFullRaiderWaitsItsFullDelay(GameTestHelper helper) {
        betweenBites(helper, "tp:digger_make_raiding_full", 2 * ONE_SECOND,
            "is full after one carrot for two seconds");
    }

    /** A raider waits its `eat_delay` of five seconds after one carrot before it looks for the next. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 260)
    public void aRaiderWaitsItsEatDelayBetweenCrops(GameTestHelper helper) {
        betweenBites(helper, "tp:digger_make_raiding_paced", 5 * ONE_SECOND,
            "waits five seconds between carrots");
    }

    private static void betweenBites(GameTestHelper helper, String event, int wait, String failure) {
        floor(helper, Blocks.STONE);
        BlockPos first = crop(helper, 1, 2);
        BlockPos second = crop(helper, 3, 2);
        if (digger(helper, event, first) == null) {
            helper.succeed();
            return;
        }

        long begun = helper.getLevel().getGameTime();
        long[] eaten = {-1, -1};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime() - begun;
            if (eaten[0] < 0 && !standing(helper, first)) eaten[0] = now;
            if (eaten[1] < 0 && !standing(helper, second)) eaten[1] = now;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(eaten[0] >= 0 && eaten[1] >= 0, DIGGER + " has not eaten both carrots.");
            helper.assertTrue(eaten[1] - eaten[0] >= wait - SLACK,
                DIGGER + " " + failure + " and ate the second " + (eaten[1] - eaten[0]) + " ticks after the first.");
        });
    }

    /**
     * A walk to a block counts as there within the `goal_radius` its pack states. The far group says
     * three, so a raider held two blocks from a carrot eats it where it stands.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void aWalkToABlockArrivesWithinItsGoalRadius(GameTestHelper helper) {
        floor(helper, Blocks.STONE);
        BlockPos carrot = crop(helper, 3, 1);
        AddonEntity raider = digger(helper, "tp:digger_make_raiding_far", new BlockPos(1, STAND_Y, 1));
        if (raider == null) {
            helper.succeed();
            return;
        }
        raider.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);

        helper.succeedWhen(() -> helper.assertFalse(standing(helper, carrot),
            DIGGER + " counts three blocks as there and never ate the carrot two blocks off."));
    }

    private static void floor(GameTestHelper helper, Block block) {
        BlockPos.betweenClosed(new BlockPos(0, FLOOR, 0), new BlockPos(7, FLOOR, 7))
            .forEach(at -> helper.setBlock(at, block));
    }

    /** A ripe carrot on farmland set into the floor, at the height a raider stands. */
    private static BlockPos crop(GameTestHelper helper, int x, int z) {
        helper.setBlock(new BlockPos(x, FLOOR, z), Blocks.FARMLAND);
        BlockPos carrot = new BlockPos(x, STAND_Y, z);
        helper.setBlock(carrot, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));
        return carrot;
    }

    private static boolean standing(GameTestHelper helper, BlockPos carrot) {
        return helper.getBlockState(carrot).is(Blocks.CARROTS);
    }

    private static List<ItemEntity> emeralds(GameTestHelper helper) {
        AABB around = new AABB(Vec3.atCenterOf(helper.absolutePos(new BlockPos(-2, FLOOR, -2))),
            Vec3.atCenterOf(helper.absolutePos(new BlockPos(10, FLOOR + 4, 10))));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, around,
            item -> item.getItem().is(Items.EMERALD));
    }

    private static boolean on(AddonEntity digger, String name) {
        return Boolean.TRUE.equals(digger.value(name));
    }

    /** A digger with its group on and its goals running. */
    private static AddonEntity digger(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity digger = BehaviourGameTests.spawn(helper, DIGGER, where);
        if (digger == null) return null;

        EntityEvents.trigger(digger, event, null, ServerHooks.state());
        digger.setNoAi(false);
        return digger;
    }
}
