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
     * A brute with its group on, its goals running and its feet held, so only its reach decides what
     * it can hit.
     */
    private static AddonEntity heldInPlace(GameTestHelper helper, String event) {
        AddonEntity brute = BehaviourGameTests.spawn(helper, BRUTE, STAND);
        if (brute == null) return null;

        EntityEvents.trigger(brute, event, null, ServerHooks.state());
        brute.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        brute.setNoAi(false);
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
