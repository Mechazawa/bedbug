package at.ioexception.mcaddon.bedrock.behaviour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a trade table is flattened into, for the two things a merchant screen cannot show.
 *
 * A tier a trader has not earned yet and an alternative payment a Java offer has no room for both
 * end as offers that are simply absent, so the count and the tier each offer carries are what say
 * whether the table was read whole.
 */
class TradeTableTest {
    @TempDir
    Path root;

    @Test
    void makesOneOfferPerItemAChoiceAcceptsAsPayment() throws IOException {
        TradeTable table = read("""
            {"tiers": [{"total_exp_required": 0, "groups": [{"num_to_select": 1, "trades": [
              {"wants": [{"choice": [{"item": "minecraft:emerald"}, {"item": "minecraft:diamond"}],
                          "quantity": 2}],
               "gives": [{"item": "probe:token"}]}]}]}]}
            """);

        assertEquals(2, table.offers().size());
        assertEquals("minecraft:emerald", table.offers().get(0).cost().item());
        assertEquals("minecraft:diamond", table.offers().get(1).cost().item());
        assertEquals(2, table.offers().get(0).cost().count());
    }

    @Test
    void marksEachOfferWithTheTierItCameFrom() throws IOException {
        TradeTable table = read("""
            {"tiers": [
              {"total_exp_required": 0, "trades": [{"wants": [{"item": "minecraft:emerald"}],
                                                    "gives": [{"item": "probe:token"}]}]},
              {"total_exp_required": 10, "trades": [{"wants": [{"item": "minecraft:emerald"}],
                                                     "gives": [{"item": "probe:crown"}]}]}]}
            """);

        assertEquals(2, table.tiers());
        assertEquals(0, table.offers().get(0).tier());
        assertEquals(1, table.offers().get(1).tier());
    }

    @Test
    void opensATierOnlyOnceTheTraderHasEarnedWhatItAsksFor() throws IOException {
        TradeTable table = read("""
            {"tiers": [{"total_exp_required": 0, "trades": []},
                       {"total_exp_required": 10, "trades": []},
                       {"total_exp_required": 40, "trades": []}]}
            """);

        assertEquals(1, table.reached(0));
        assertEquals(2, table.reached(10));
        assertEquals(2, table.reached(39));
        assertEquals(3, table.reached(40));
    }

    @Test
    void readsWhatATradeGivesAndHowOftenItMayBeUsed() throws IOException {
        TradeTable.Offer offer = read("""
            {"tiers": [{"total_exp_required": 0, "trades": [
              {"wants": [{"item": "minecraft:emerald", "quantity": 3, "price_multiplier": 0.2}],
               "gives": [{"item": "probe:token", "quantity": 1}],
               "max_uses": 5, "trader_exp": 7, "reward_exp": false}]}]}
            """).offers().get(0);

        assertEquals(5, offer.maxUses());
        assertEquals(7, offer.experience());
        assertTrue(offer.priceMultiplier() > 0.19f);
        assertEquals(false, offer.rewardExperience());
    }

    private TradeTable read(String table) throws IOException {
        return TradeTable.read(
            BehaviourPackFixture.holding(root, "trading/probe.json", table), "trading/probe.json");
    }
}
