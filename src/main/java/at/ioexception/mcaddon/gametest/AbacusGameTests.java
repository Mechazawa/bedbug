package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The Molang a behaviour pack writes into an entity's components and events, read through `tp:abacus`. */
public final class AbacusGameTests {
    private static final String ABACUS = "tp:abacus";

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
}
