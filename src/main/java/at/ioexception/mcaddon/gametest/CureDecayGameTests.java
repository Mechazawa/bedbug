package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The cure gossip `tp:haggler` keeps for its curer, aged by Java's daily gossip decay. */
public final class CureDecayGameTests {
    private static final long DAY = 24000;
    private static final int DAYS = 30;

    /**
     * A day of game time takes one point off the minor gossip of a cure and none off the major, and a
     * minor gossip under two is forgotten. Each tick the stored decay time is put a day back and read
     * in again, as a reloaded chunk reads it, so thirty ticks are thirty days: twenty five minor
     * gossip are gone, twenty major stay, and bread the curer bought for two emeralds costs three.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void aCuresMinorGossipDecaysDayByDay(GameTestHelper helper) {
        AddonEntity trader = HagglerGameTests.haggler(helper, "tp:haggler_name");
        if (trader == null) {
            helper.succeed();
            return;
        }

        HagglerGameTests.Customer curer = HagglerGameTests.Customer.join(helper);
        AddonEntity cured = HagglerGameTests.transformed(helper, trader, "tp:haggler_cure", curer.player());
        int fresh = HagglerGameTests.breadPrice(cured, curer);
        int[] days = {0};
        helper.onEachTick(() -> {
            if (days[0]++ >= DAYS) return;
            AddonState state = ServerHooks.state();
            JsonObject saved = state.trading(cured.getUUID());
            saved.addProperty("cure_decayed", helper.getLevel().getGameTime() - DAY);
            state.setTrading(cured.getUUID(), saved);
            cured.restoreGroups();
        });
        helper.runAfterDelay(DAYS + 5, () -> {
            int aged = HagglerGameTests.breadPrice(cured, curer);
            curer.leave();
            helper.assertTrue(fresh == 2,
                "tp:haggler charges the player who cured it " + fresh + " emeralds for bread.");
            helper.assertTrue(aged == 3,
                "tp:haggler charges its curer " + aged + " emeralds for bread a month after the cure.");
            helper.succeed();
        });
    }
}
