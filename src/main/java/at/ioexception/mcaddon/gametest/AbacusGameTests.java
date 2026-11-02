package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;

/** The Molang a behaviour pack writes into an entity's components and events, read through `tp:abacus`. */
public final class AbacusGameTests {
    private static final String ABACUS = "tp:abacus";

    /** More than the abacus's ten health, so one blow is the whole of it. */
    private static final float KILLING_BLOW = 40f;

    private static void raise(AddonEntity abacus, String event) {
        EntityEvents.trigger(abacus, event, null, ServerHooks.state());
    }

    /**
     * A heartbeat interval written as Molang in a group is answered for the entity at each beat,
     * the way the warden's heart quickens with its anger.
     */
    @GameTest(maxTicks = 80)
    public void aHeartbeatIntervalIsAnsweredForTheEntityAtEachBeat(GameTestHelper helper) {
        AddonEntity abacus = BehaviourGameTests.spawn(helper, ABACUS);
        if (abacus == null) {
            helper.succeed();
            return;
        }

        raise(abacus, "tp:abacus_make_beating");
        helper.startSequence()
            .thenExecuteAfter(2, () -> helper.assertTrue(abacus.answer("q.heartbeat_interval").asFloat() == 2,
                "The first beat was " + abacus.answer("q.heartbeat_interval").asFloat() + " seconds."))
            .thenExecute(() -> raise(abacus, "tp:abacus_count"))
            .thenExecuteAfter(45, () -> helper.assertTrue(abacus.answer("q.heartbeat_interval").asFloat() == 1,
                "A beat at a count of two was " + abacus.answer("q.heartbeat_interval").asFloat() + " seconds."))
            .thenSucceed();
    }

    /**
     * An experience reward is answered with the entity's own queries, so a reward naming the
     * entity's variant pays by it: a variant of three is worth four to a player's killing blow.
     */
    @GameTest()
    public void anExperienceRewardIsAnsweredWithTheEntitysOwnQueries(GameTestHelper helper) {
        assertKillPays(helper, "tp:abacus_make_rewarding", 4);
    }

    /** An experience reward written as an array pays the sum of its expressions. */
    @GameTest()
    public void anExperienceRewardArrayPaysTheSumOfItsExpressions(GameTestHelper helper) {
        assertKillPays(helper, "tp:abacus_make_summing", 5);
    }

    private static void assertKillPays(GameTestHelper helper, String event, int expected) {
        AddonEntity abacus = BehaviourGameTests.spawn(helper, ABACUS);
        if (abacus == null) {
            helper.succeed();
            return;
        }

        raise(abacus, event);
        Player killer = Platform.mockPlayer(helper);
        Platform.placeAt(killer, abacus.getX(), abacus.getY(), abacus.getZ(), 0f, 0f);
        Platform.hurt(abacus, helper.getLevel(),
            helper.getLevel().damageSources().playerAttack(killer), KILLING_BLOW);

        helper.assertTrue(!abacus.isAlive(), ABACUS + " survived a killing blow.");
        int awarded = helper.getLevel().getEntitiesOfClass(ExperienceOrb.class, abacus.getBoundingBox().inflate(1))
            .stream().mapToInt(ExperienceOrb::getValue).sum();
        helper.assertTrue(awarded == expected,
            ABACUS + " after " + event + " left " + awarded + " experience where " + expected + " was due.");
        helper.succeed();
    }
}
