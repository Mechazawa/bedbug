package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The resting and idling goals, read through the pose and the head a goal leaves on the entity.
 *
 * `tp:idler` is a plain mob whose groups each carry one idle behaviour with every field a test here
 * asks about. Times are counted from the tick the group is switched on, and each assertion allows a
 * few ticks for the two ticks between Java's goal checks.
 */
public final class IdleGoalGameTests {
    private static final String IDLER = "tp:idler";

    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    /** Past Java's check cadence, which looks at a goal that is not running on every second tick. */
    private static final int SLACK = 6;

    /** The two seconds the timing group runs for, in ticks. */
    private static final int TIMER_DURATION = 40;

    /** The three seconds the timing group waits before its first run, in ticks. */
    private static final int TIMER_COOLDOWN = 60;

    /**
     * A timer flag waits `cooldown_range` before its first run, runs for `duration_range`, raises
     * `on_start` and `on_end` and holds only the controls `control_flags` names.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aTimerFlagRunsForItsDurationAfterItsCooldown(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_timing");
        if (idler == null) {
            helper.succeed();
            return;
        }

        long begun = helper.getLevel().getGameTime();
        long[] window = {-1, -1};
        Set<Goal.Flag>[] held = new Set[] {Set.of()};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime() - begun;
            if (!Boolean.TRUE.equals(idler.value("timer_flag_1"))) return;

            if (window[0] < 0) {
                window[0] = now;
                held[0] = idler.runningControls();
            }
            window[1] = now;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(Boolean.TRUE.equals(idler.value("tp:ended")), IDLER + " has not finished a timer.");
            helper.assertTrue(Boolean.TRUE.equals(idler.value("tp:started")), IDLER + " never raised on_start.");
            helper.assertTrue(Math.abs(window[0] - TIMER_COOLDOWN) <= SLACK,
                IDLER + " waits three seconds before its timer and began it at tick " + window[0] + ".");
            helper.assertTrue(Math.abs(window[1] - window[0] - TIMER_DURATION) <= SLACK,
                IDLER + " runs its timer for two seconds and ran it for " + (window[1] - window[0]) + " ticks.");
            helper.assertTrue(held[0].equals(EnumSet.of(Goal.Flag.LOOK)),
                IDLER + " names only `look` as the control its timer holds and held " + held[0] + ".");
        });
    }

    /**
     * Random sitting sits for at least `min_sit_time`, stands on `stop_chance`, and waits `cooldown`
     * before it sits again.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void aSittingMobSitsItsMinimumAndWaitsItsCooldown(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_sitting");
        if (idler == null) {
            helper.succeed();
            return;
        }

        long[] marks = timeline(helper, idler, pose -> pose == Pose.SITTING, 3);
        helper.succeedWhen(() -> {
            helper.assertTrue(marks[2] >= 0, IDLER + " did not sit down a second time.");
            helper.assertTrue(Math.abs(marks[1] - marks[0] - 40) <= SLACK,
                IDLER + " sits for at least two seconds and stood after " + (marks[1] - marks[0]) + " ticks.");
            helper.assertTrue(Math.abs(marks[2] - marks[1] - 60) <= SLACK,
                IDLER + " waits three seconds between sittings and waited " + (marks[2] - marks[1]) + " ticks.");
        });
    }

    /**
     * Lying down starts on a one in `interval` chance at each check, so an interval of one lies down
     * at once, and stops on a one in `random_stop_interval` chance, so a huge one stays down.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 160)
    public void aMobLyingDownStartsAtOnceAndStaysDownOnAHugeStopInterval(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_bedridden");
        if (idler == null) {
            helper.succeed();
            return;
        }

        long[] marks = timeline(helper, idler, pose -> pose == Pose.SLEEPING, 2);
        helper.runAtTickTime(150, () -> {
            helper.assertTrue(marks[0] >= 0 && marks[0] <= SLACK + 4,
                IDLER + " lies down on a one in one chance and did so at tick " + marks[0] + ".");
            helper.assertTrue(marks[1] < 0, IDLER + " got up though its stop interval is a million.");
            helper.succeed();
        });
    }

    /** A one in one stop interval ends each lying down at the next tick, with the next start a few checks away. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 160)
    public void aMobLyingDownGetsUpOnAShortStopInterval(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_lying");
        if (idler == null) {
            helper.succeed();
            return;
        }

        long[] marks = timeline(helper, idler, pose -> pose == Pose.SLEEPING, 2);
        helper.succeedWhen(() -> {
            helper.assertTrue(marks[0] >= 0, IDLER + " never lay down.");
            helper.assertTrue(marks[1] >= 0 && marks[1] - marks[0] <= 40,
                IDLER + " was still down " + (marks[1] - marks[0]) + " ticks after a stop interval of one.");
        });
    }

    /**
     * Looking around and sitting sits for the looks it makes, each held for its look time, turning
     * the head only inside the angles the pack gives and waiting its cooldown before it starts again.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void aMobLookingAroundSitsAndTurnsInsideItsAngles(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_looking");
        if (idler == null) {
            helper.succeed();
            return;
        }

        float[] swing = {0f};
        helper.onEachTick(() -> {
            if (idler.getPose() != Pose.SITTING) return;

            swing[0] = Math.max(swing[0], Math.abs(Mth.wrapDegrees(idler.getYHeadRot())));
        });
        long[] marks = timeline(helper, idler, pose -> pose == Pose.SITTING, 3);
        helper.succeedWhen(() -> {
            helper.assertTrue(marks[2] >= 0, IDLER + " did not look around a second time.");
            helper.assertTrue(Math.abs(marks[1] - marks[0] - 40) <= 2 * SLACK,
                IDLER + " makes four looks of half a second and looked for " + (marks[1] - marks[0]) + " ticks.");
            helper.assertTrue(marks[2] - marks[1] >= 40 - SLACK,
                IDLER + " waits two seconds between rounds and waited " + (marks[2] - marks[1]) + " ticks.");
            helper.assertTrue(swing[0] <= 31f, IDLER + " turned its head " + swing[0] + " degrees past a window of 30.");
            helper.assertTrue(swing[0] >= 3f, IDLER + " never turned its head, which four looks in the window should.");
        });
    }

    /** Croaking lasts `duration`, starts again `interval` after it ends and holds the croaking pose. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aCroakingMobCroaksForItsDurationAndWaitsItsInterval(GameTestHelper helper) {
        cycle(helper, "tp:idler_make_croaking", Pose.CROAKING, 0, 20, 60);
    }

    /** Sniffing lasts `duration` and waits `cooldown_range` between sniffs. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aSniffingMobSniffsForItsDurationAndWaitsItsCooldown(GameTestHelper helper) {
        cycle(helper, "tp:idler_make_sniffing", Pose.SNIFFING, 0, 30, 40);
    }

    /** Emerging lasts `duration` and waits `cooldown_time` before it can start again. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void anEmergingMobEmergesForItsDurationAndWaitsItsCooldown(GameTestHelper helper) {
        cycle(helper, "tp:idler_make_emerging", Pose.EMERGING, 0, 40, 20);
    }

    /** Digging waits `idle_time` before its first dig, digs for `duration` and waits `idle_time` again. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aDiggingMobWaitsItsIdleTimeAndDigsForItsDuration(GameTestHelper helper) {
        cycle(helper, "tp:idler_make_digging", Pose.DIGGING, 20, 40, 20);
    }

    /** Switches a group on and checks when the pose begins, how long it lasts and how long until it begins again. */
    private static void cycle(GameTestHelper helper, String event, Pose pose, int first, int length, int gap) {
        AddonEntity idler = idler(helper, event);
        if (idler == null) {
            helper.succeed();
            return;
        }

        long[] marks = timeline(helper, idler, held -> held == pose, 3);
        helper.succeedWhen(() -> {
            helper.assertTrue(marks[2] >= 0, IDLER + " held " + pose + " only once.");
            helper.assertTrue(Math.abs(marks[0] - first) <= SLACK,
                IDLER + " first held " + pose + " at tick " + marks[0] + " and should at " + first + ".");
            helper.assertTrue(Math.abs(marks[1] - marks[0] - length) <= SLACK,
                IDLER + " held " + pose + " for " + (marks[1] - marks[0]) + " ticks and should for " + length + ".");
            helper.assertTrue(Math.abs(marks[2] - marks[1] - gap) <= SLACK,
                IDLER + " waited " + (marks[2] - marks[1]) + " ticks between holds and should wait " + gap + ".");
        });
    }

    /** What the pig standing beside a napper is, which only its detection box and its exceptions decide. */
    private static Mob pigAt(GameTestHelper helper, BlockPos where) {
        Entity created = Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(),
            helper.getLevel());
        Mob pig = (Mob) created;
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        pig.setNoAi(true);
        pig.setNoGravity(true);
        helper.getLevel().addFreshEntity(pig);
        return pig;
    }

    /**
     * A napper lies down while `can_nap_filters` holds and wakes for a mob inside
     * `mob_detect_dist` and `mob_detect_height`, and `cooldown_min` to `cooldown_max` passes before
     * it naps again.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void aNapperWakesForANearbyMobAndWaitsItsCooldown(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_napping");
        if (idler == null) {
            helper.succeed();
            return;
        }

        Mob[] pig = {null};
        long begun = helper.getLevel().getGameTime();
        long[] woke = {-1};
        long[] napped = {-1};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime() - begun;
            boolean asleep = idler.getPose() == Pose.SLEEPING;
            if (pig[0] == null && asleep && now >= 12) pig[0] = pigAt(helper, STAND.east(3).above(1));
            if (pig[0] != null && woke[0] < 0 && !asleep) {
                woke[0] = now;
                pig[0].discard();
            }
            if (woke[0] >= 0 && napped[0] < 0 && asleep) napped[0] = now;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(woke[0] >= 0, IDLER + " slept on with a pig three blocks off.");
            helper.assertTrue(napped[0] >= 0, IDLER + " never napped again.");
            helper.assertTrue(Math.abs(napped[0] - woke[0] - 40) <= SLACK,
                IDLER + " waits two seconds between naps and waited " + (napped[0] - woke[0]) + " ticks.");
        });
    }

    /** A mob past the detection distance, and one above the detection height, leave a napper asleep. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aNapperIgnoresMobsPastItsDetectionBox(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_napping");
        if (idler == null) {
            helper.succeed();
            return;
        }

        boolean[] placed = {false};
        helper.onEachTick(() -> {
            if (placed[0] || idler.getPose() != Pose.SLEEPING) return;

            placed[0] = true;
            pigAt(helper, STAND.east(6));
            pigAt(helper, STAND.above(4));
        });
        helper.runAtTickTime(80, () -> {
            helper.assertTrue(placed[0], IDLER + " never lay down.");
            helper.assertTrue(idler.getPose() == Pose.SLEEPING,
                IDLER + " woke for a pig past six and above its detection box.");
            helper.succeed();
        });
    }

    /** A napper whose filters fail never lies down. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 140)
    public void aNapperWhoseFiltersFailNeverNaps(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_insomniac");
        if (idler == null) {
            helper.succeed();
            return;
        }

        boolean[] slept = {false};
        helper.onEachTick(() -> slept[0] |= idler.getPose() == Pose.SLEEPING);
        helper.runAtTickTime(120, () -> {
            helper.assertFalse(slept[0], IDLER + " napped though its filter asks for a pig.");
            helper.succeed();
        });
    }

    /** A mob `wake_mob_exceptions` accepts leaves a napper asleep however near it stands. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aNapperIsNotWokenByAMobItsExceptionsAccept(GameTestHelper helper) {
        AddonEntity idler = idler(helper, "tp:idler_make_unbothered");
        if (idler == null) {
            helper.succeed();
            return;
        }

        boolean[] placed = {false};
        helper.onEachTick(() -> {
            if (placed[0] || idler.getPose() != Pose.SLEEPING) return;

            placed[0] = true;
            pigAt(helper, STAND.east(2));
        });
        helper.runAtTickTime(80, () -> {
            helper.assertTrue(placed[0], IDLER + " never lay down.");
            helper.assertTrue(idler.getPose() == Pose.SLEEPING, IDLER + " woke for a pig its exceptions accept.");
            helper.succeed();
        });
    }

    /**
     * The ticks, counted from the group going on, at which a pose predicate first turns true, then
     * false, then true again, up to the count asked for; -1 where it has not happened yet.
     */
    private static long[] timeline(GameTestHelper helper, AddonEntity idler, Predicate<Pose> on, int count) {
        long begun = helper.getLevel().getGameTime();
        long[] marks = new long[count];
        java.util.Arrays.fill(marks, -1);
        helper.onEachTick(() -> {
            int next = 0;
            while (next < count && marks[next] >= 0) next++;
            if (next == count) return;

            boolean matches = on.test(idler.getPose());
            if (matches == (next % 2 == 0)) marks[next] = helper.getLevel().getGameTime() - begun;
        });
        return marks;
    }

    private static AddonEntity idler(GameTestHelper helper, String event) {
        AddonEntity idler = BehaviourGameTests.spawn(helper, IDLER, STAND);
        if (idler == null) return null;

        idler.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        EntityEvents.trigger(idler, event, null, ServerHooks.state());
        idler.setNoAi(false);
        return idler;
    }
}
