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
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;

/**
 * The fields inside a pack's behaviours that decide how a goal plays out, read back off the world.
 *
 * `tp:brute` is a plain mob that hits for one point and is not immune to fire. Each of its groups
 * carries one behaviour with the fields a test here asks about, and the brute is held where it
 * stands, so whatever reaches a pig standing apart from it was reached by the goal and not by a walk.
 */
public final class GoalGameTests {
    private static final String BRUTE = "tp:brute";

    /** On the empty structure's floor, where a mob with its goals off stays as well as one with them on. */
    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    /** How long a test waiting on a goal may run, with room for Java's second between goal checks. */
    private static final int GOAL_TICKS = 120;

    /**
     * A melee blow waits out the `cooldown_time` its pack states, in seconds, before the next.
     *
     * The boxing group waits a second and a half; Java's own goal swings once a second whatever it
     * is told, so the second blow lands ten ticks early there.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aMeleeBlowWaitsTheCooldownItsPackStates(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_box");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(1), BLOWS_TO_KILL);
        brute.setTarget(pig);
        List<Long> blows = new ArrayList<>();
        float[] health = {pig.getHealth()};
        helper.onEachTick(() -> {
            if (pig.getHealth() < health[0]) blows.add(helper.getLevel().getGameTime());
            health[0] = pig.getHealth();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(blows.size() >= 2, BRUTE + " struck the pig " + blows.size() + " times.");
            long gap = blows.get(1) - blows.get(0);
            helper.assertTrue(Math.abs(gap - BOXING_COOLDOWN) <= 1,
                BRUTE + " waits a second and a half between blows and struck again after " + gap + " ticks.");
        });
    }

    /** The second and a half the boxing group waits between blows, in Java's ticks. */
    private static final int BOXING_COOLDOWN = 30;

    /** Enough health that the pig outlives every blow a test waits for. */
    private static final float BLOWS_TO_KILL = 20f;

    /**
     * A box attack reaches as far as `horizontal_reach` grows the attacker's box, and the blow and
     * the kill raise the events `on_attack` and `on_kill` name.
     *
     * The pig stands two blocks off, past the reach Java gives every mob, and a single blow kills it.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aBoxAttackReachesItsStatedReachAndRaisesItsEvents(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_box");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(2), 1f);
        brute.setTarget(pig);
        helper.succeedWhen(() -> {
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:struck")),
                BRUTE + " reaches a block and a half past its box and never struck the pig two blocks off.");
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:felled")),
                BRUTE + " killed the pig and never raised the event on_kill names.");
        });
    }

    /**
     * A melee attack reaches `reach_multiplier` times the attacker's own width.
     *
     * The reaching group says six, which puts a pig three blocks off in reach; Java's own reach falls
     * short of it by more than a block.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aMeleeAttackReachesItsMultipleOfItsWidth(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_reach");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(3), BLOWS_TO_KILL);
        brute.setTarget(pig);
        helper.succeedWhen(() -> helper.assertTrue(pig.getHealth() < BLOWS_TO_KILL,
            BRUTE + " reaches six times its width and never struck the pig three blocks off."));
    }

    /**
     * A burning attacker with `can_spread_on_fire` sets what it hits alight.
     *
     * Java writes the same into its own zombie alone, so a plain mob passes on nothing.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aBurningAttackerSetsItsTargetAlight(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_burn");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(1), BLOWS_TO_KILL);
        brute.setRemainingFireTicks(BURNING);
        brute.setTarget(pig);
        helper.succeedWhen(() -> {
            helper.assertTrue(pig.getHealth() < BLOWS_TO_KILL, BRUTE + " never struck the pig beside it.");
            helper.assertTrue(pig.getRemainingFireTicks() > 0,
                BRUTE + " struck the pig while burning and the pig is not alight.");
        });
    }

    /** Long enough that the brute is still alight when its first blow lands. */
    private static final int BURNING = 200;

    /**
     * A panic starts at a cause `damage_sources` lists and not at a blow `ignore_mob_damage` leaves
     * out, even one whose cause is on the list.
     *
     * The skittish group lists falling, which no panic of Java's starts at, and an attack, while
     * ignoring every mob; Java panics at a mob's blow and never at a fall. The brute has no other
     * goal that moves it, so a path in progress is the panic.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aPanicStartsAtTheCausesItsPackLists(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_skit");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(3), BLOWS_TO_KILL);
        Platform.hurt(brute, helper.getLevel(), brute.damageSources().mobAttack(pig), 1f);
        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS, () -> {
                helper.assertFalse(brute.getNavigation().isInProgress(),
                    BRUTE + " ignores every mob's blow and ran from the pig's.");
                brute.invulnerableTime = 0;
                Platform.hurt(brute, helper.getLevel(), brute.damageSources().fall(), 1f);
            })
            .thenWaitUntil(() -> helper.assertTrue(brute.getNavigation().isInProgress(),
                BRUTE + " panics at a fall and stood where it fell."))
            .thenSucceed();
    }

    /** Long enough for a goal checked every other tick to have started had it been going to. */
    private static final int SETTLE_TICKS = 10;

    /**
     * A panic with `force` set starts with nothing having hurt the entity, and keeps the entity
     * running for as long as it lasts.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aForcedPanicNeedsNoHurt(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_bolt");
        if (brute == null) {
            helper.succeed();
            return;
        }

        int[] running = {0};
        int[] since = {-1};
        helper.onEachTick(() -> {
            boolean moving = brute.getNavigation().isInProgress();
            if (moving && since[0] < 0) since[0] = 0;
            if (since[0] < 0) return;
            since[0]++;
            if (moving) running[0]++;
        });
        helper.runAfterDelay(GOAL_TICKS - 20, () -> {
            helper.assertTrue(since[0] > 0, BRUTE + " panics by force and never ran.");
            helper.assertTrue(running[0] * 4 >= since[0] * 3, BRUTE + " panics by force and ran for "
                + running[0] + " of the " + since[0] + " ticks since it set off.");
            helper.succeed();
        });
    }

    /**
     * A panic with `prefer_water` heads for water within reach whatever hurt the entity.
     *
     * Java's own goal heads for water only while the entity burns, and otherwise runs anywhere.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aPanicThatPrefersWaterHeadsForIt(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_wade");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(STAND.east(3), net.minecraft.world.level.block.Blocks.WATER);
        Mob pig = pig(helper, STAND.south(3), BLOWS_TO_KILL);
        Platform.hurt(brute, helper.getLevel(), brute.damageSources().mobAttack(pig), 1f);
        helper.succeedWhen(() -> {
            BlockPos heading = brute.getNavigation().getTargetPos();
            helper.assertTrue(heading != null
                    && helper.getLevel().getFluidState(heading).is(net.minecraft.tags.FluidTags.WATER),
                BRUTE + " prefers water when it panics and ran for " + heading + ".");
        });
    }

    /**
     * An entity fleeing a block raises `on_escape` once the block is out of the box it searches.
     *
     * The shy group flees gold within three blocks, and the gold stands beside the brute in the
     * middle of the floor, which leaves room to run whichever way it picks.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = ESCAPE_TICKS)
    public void anEntityFleeingABlockRaisesItsEscapeOnceClear(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_shy_away", MIDDLE);
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(MIDDLE.east(1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK);
        helper.succeedWhen(() -> helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:escaped")),
            BRUTE + " fled the gold beside it and never raised the event on_escape names."));
    }

    /** Room for a few runs away, in case the first one stops short of the edge of the search. */
    private static final int ESCAPE_TICKS = 240;

    private static final BlockPos MIDDLE = new BlockPos(4, 0, 4);

    /**
     * A teleporting entity with a target further off than `target_distance` jumps that far towards
     * it, and a group bringing the component in is enough for that.
     *
     * The blinking group jumps three blocks; the brute's feet are held, so only a jump closes on the
     * pig six blocks off.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aTeleportClosesOnAFarTarget(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_blink");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(6), BLOWS_TO_KILL);
        brute.setTarget(pig);
        helper.succeedWhen(() -> helper.assertTrue(brute.distanceTo(pig) < 4,
            BRUTE + " teleports towards a far target and is still " + brute.distanceTo(pig) + " blocks off."));
    }

    /**
     * An entity with `teleports_on_projectile_hit` blinks away from a projectile, which then does it
     * no harm.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aTeleportDodgesAProjectile(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_blink");
        if (brute == null) {
            helper.succeed();
            return;
        }

        float health = brute.getHealth();
        Platform.hurt(brute, helper.getLevel(), brute.damageSources().thrown(null, null), 1f);
        helper.assertTrue(brute.getHealth() == health,
            BRUTE + " blinks away from projectiles and a thrown one took it from " + health + " to "
                + brute.getHealth() + ".");
        helper.succeed();
    }

    /** A brute with its group on and its goals running. */
    private static AddonEntity withGroup(GameTestHelper helper, String event) {
        return withGroup(helper, event, STAND);
    }

    private static AddonEntity withGroup(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity brute = BehaviourGameTests.spawn(helper, BRUTE, where);
        if (brute == null) return null;

        EntityEvents.trigger(brute, event, null, ServerHooks.state());
        brute.setNoAi(false);
        return brute;
    }

    /**
     * A brute with its group on, its goals running and its feet held, so only its reach decides what
     * it can hit.
     */
    private static AddonEntity heldInPlace(GameTestHelper helper, String event) {
        AddonEntity brute = withGroup(helper, event);
        if (brute != null) brute.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        return brute;
    }

    /** A pig that stays where it is put, blows included, with the health the test asks for. */
    private static Mob pig(GameTestHelper helper, BlockPos where, float health) {
        ServerLevel level = helper.getLevel();
        Entity created = Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(), level);
        helper.assertTrue(created instanceof Mob, "A pig could not be made.");

        Mob pig = (Mob) created;
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        pig.setNoAi(true);
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        pig.setHealth(health);
        // A blow would otherwise knock it out of the reach a test is measuring something else at.
        pig.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        level.addFreshEntity(pig);
        return pig;
    }
}
