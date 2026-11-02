package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
            .thenExecute(() -> {
                raise(abacus, "tp:abacus_count");
                raise(abacus, "tp:abacus_count");
            })
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

    /**
     * A property default written as Molang is answered as the entity first spawns, and a default
     * naming one of an enum's values stays that value.
     */
    @GameTest()
    public void aPropertyDefaultWrittenAsMolangIsAnsweredAtFirstSpawn(GameTestHelper helper) {
        AddonEntity abacus = BehaviourGameTests.spawn(helper, ABACUS);
        if (abacus == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenExecuteAfter(1, () -> {
                assertProperty(helper, abacus, "tp:unmet", false);
                assertProperty(helper, abacus, "tp:sum", 3.0);
                assertProperty(helper, abacus, "tp:shade", "bright");
            })
            .thenSucceed();
    }

    /**
     * A `set_property` value written as Molang is answered as the event runs, reading the value it
     * replaces: counting twice reaches two, and a toggle turns a flag on and off again.
     */
    @GameTest()
    public void aSetPropertyValueWrittenAsMolangReadsTheValueItReplaces(GameTestHelper helper) {
        AddonEntity abacus = BehaviourGameTests.spawn(helper, ABACUS);
        if (abacus == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenExecuteAfter(1, () -> {
                raise(abacus, "tp:abacus_count");
                raise(abacus, "tp:abacus_count");
                assertProperty(helper, abacus, "tp:count", 2.0);
                raise(abacus, "tp:abacus_toggle");
                assertProperty(helper, abacus, "tp:flag", true);
                raise(abacus, "tp:abacus_toggle");
                assertProperty(helper, abacus, "tp:flag", false);
            })
            .thenSucceed();
    }

    /**
     * An item descriptor testing tags in Molang excludes the items in those tags: a mob picking up
     * anything but swords walks past the sword lying nearer and takes the apple.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 140)
    public void anExcludedItemsTagTestLeavesASwordOnTheFloor(GameTestHelper helper) {
        AddonEntity abacus = BehaviourGameTests.spawn(helper, ABACUS);
        if (abacus == null) {
            helper.succeed();
            return;
        }

        raise(abacus, "tp:abacus_make_picky");
        abacus.setNoAi(false);
        ItemEntity[] sword = new ItemEntity[1];
        helper.startSequence()
            .thenExecuteAfter(20, () -> {
                sword[0] = drop(helper, abacus, 1, new ItemStack(Items.IRON_SWORD));
                drop(helper, abacus, 3, new ItemStack(Items.APPLE));
            })
            .thenWaitUntil(() -> helper.assertTrue(abacus.getMainHandItem().is(Items.APPLE),
                ABACUS + " never took the apple and holds " + abacus.getMainHandItem() + "."))
            .thenExecute(() -> helper.assertTrue(sword[0].isAlive(), ABACUS + " took the excluded sword."))
            .thenSucceed();
    }

    private static ItemEntity drop(GameTestHelper helper, AddonEntity abacus, int away, ItemStack stack) {
        ItemEntity dropped =
            new ItemEntity(helper.getLevel(), abacus.getX() + away, abacus.getY(), abacus.getZ(), stack);
        dropped.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(dropped);
        return dropped;
    }

    private static void assertProperty(GameTestHelper helper, AddonEntity abacus, String name, Object expected) {
        Object held = ServerHooks.state().property(abacus.getUUID(), name);
        helper.assertTrue(expected.equals(held), name + " holds " + held + " where " + expected + " was due.");
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
