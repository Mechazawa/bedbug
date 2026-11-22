package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/** The trade components' fields, read back off `tp:haggler`, a trader whose groups carry one each. */
public final class HagglerGameTests {
    private static final String HAGGLER = "tp:haggler";
    private static final BlockPos STAND = new BlockPos(2, 0, 2);

    /** A trade table's `display_name` is the title of the screen a customer opens, as a key of the pack's text. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aTradeTablesDisplayNameTitlesTheTradingScreen(GameTestHelper helper) {
        AddonEntity haggler = haggler(helper, "tp:haggler_name");
        if (haggler == null) {
            helper.succeed();
            return;
        }

        Customer customer = Customer.join(helper);
        haggler.mobInteract(customer.player(), InteractionHand.MAIN_HAND);
        String title = customer.screenTitleKey();
        customer.leave();
        helper.assertTrue("tp.haggler.counter".equals(title),
            HAGGLER + " opened a trading screen titled by " + title + " and not by its display name.");
        helper.succeed();
    }

    /**
     * `minecraft:behavior.trade_interest` holds up what the trader sells for the item the customer
     * holds, turning to the next such ware every `carried_item_switch_time`: two trades take an
     * emerald, for iron and for gold, and a third takes a diamond for coal, which is never shown.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void anInterestedTraderTurnsThroughWhatItSellsForTheHeldItem(GameTestHelper helper) {
        AddonEntity haggler = haggler(helper, "tp:haggler_interest");
        if (haggler == null) {
            helper.succeed();
            return;
        }

        haggler.setNoAi(false);
        Customer customer = Customer.join(helper);
        customer.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD));
        java.util.Set<Item> shown = new java.util.HashSet<>();
        helper.onEachTick(() -> shown.add(haggler.getMainHandItem().getItem()));
        helper.succeedWhen(() -> {
            helper.assertFalse(shown.contains(Items.COAL), HAGGLER + " held up coal, which it sells for a diamond.");
            helper.assertTrue(shown.contains(Items.IRON_INGOT) && shown.contains(Items.GOLD_INGOT),
                HAGGLER + " showed " + shown + " and not both wares it sells for an emerald.");
            customer.leave();
        });
    }

    /**
     * `remove_item_time` is how long the trader keeps its ware up once the customer holds nothing it
     * buys: a second here, so it is still up half a second after the emerald is gone and put away
     * a second and a half after.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void anInterestedTraderWaitsBeforePuttingItsWareAway(GameTestHelper helper) {
        AddonEntity haggler = haggler(helper, "tp:haggler_interest");
        if (haggler == null) {
            helper.succeed();
            return;
        }

        haggler.setNoAi(false);
        Customer customer = Customer.join(helper);
        customer.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertFalse(haggler.getMainHandItem().isEmpty(),
                HAGGLER + " holds nothing up to a customer holding an emerald."))
            .thenExecute(() -> customer.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
            .thenExecuteAfter(STILL_SHOWN, () -> helper.assertFalse(haggler.getMainHandItem().isEmpty(),
                HAGGLER + " put its ware away at once when the customer let go of the emerald."))
            .thenExecuteAfter(PUT_AWAY - STILL_SHOWN, () -> helper.assertTrue(haggler.getMainHandItem().isEmpty(),
                HAGGLER + " still holds " + haggler.getMainHandItem() + " long after the emerald was gone."))
            .thenExecute(customer::leave)
            .thenSucceed();
    }

    private static final int STILL_SHOWN = 10;
    private static final int PUT_AWAY = 30;

    /**
     * `persist_trades` keeps a trader's offers through a transformation into an entity with no table
     * and back into one with a table of its own: the trade used once before is still used once.
     * A table without it starts afresh.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void persistedTradesSurviveATransformationAndBack(GameTestHelper helper) {
        helper.assertTrue(usesAfterTurningBack(helper, "tp:haggler_persist") == 1,
            HAGGLER + " persists its trades and came back without the use it had made of one.");
        helper.assertTrue(usesAfterTurningBack(helper, "tp:haggler_forget") == 0,
            HAGGLER + " does not persist its trades and came back with an old use on one.");
        helper.succeed();
    }

    /** Uses the first trade once, turns the trader into a husk and back, and counts its first trade's uses. */
    private static int usesAfterTurningBack(GameTestHelper helper, String table) {
        AddonEntity trader = haggler(helper, table);
        if (trader == null) return -1;

        trader.notifyTrade(trader.getOffers().get(0));
        AddonEntity husk = transformed(helper, trader, "tp:haggler_turn", null);
        helper.assertTrue(husk != null && husk.trades() == null, HAGGLER + " did not turn into a husk with no table.");
        AddonEntity back = transformed(helper, husk, "tp:haggler_return", null);
        int uses = back.getOffers().get(0).getUses();
        back.discard();
        return uses;
    }

    /**
     * `convert_trades_economy` hands an `economy_trade_table` the old trade's result for the same
     * items: an iron ingot enchanted on the old counter is enchanted on the new one.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void convertedTradesKeepTheirResultsComponents(GameTestHelper helper) {
        AddonEntity trader = haggler(helper, "tp:haggler_convert");
        if (trader == null) {
            helper.succeed();
            return;
        }

        net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> mending =
            helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING);
        trader.getOffers().get(0).getResult().enchant(mending, 1);
        AddonEntity converted = transformed(helper, trader, "tp:haggler_return", null);
        helper.assertTrue(converted.getOffers().get(0).getResult().getEnchantments().getLevel(mending) == 1,
            HAGGLER + " converted its trades and lost the enchantment on the iron it sold.");
        helper.succeed();
    }

    /**
     * A player who raised the event that transformed a trader cured it, and `cured_discount` prices
     * that player's trades as Java prices a cured villager's: twenty five minor and twenty major
     * gossip make a reputation of 125, and bread at eight emeralds with a multiplier of 0.05 costs two.
     * Another customer pays the full eight.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aCuredTraderPricesItsCurerDown(GameTestHelper helper) {
        AddonEntity trader = haggler(helper, "tp:haggler_name");
        if (trader == null) {
            helper.succeed();
            return;
        }

        Customer curer = Customer.join(helper);
        Customer stranger = Customer.join(helper);
        AddonEntity cured = transformed(helper, trader, "tp:haggler_cure", curer.player());
        int toCurer = breadPrice(cured, curer);
        int toStranger = breadPrice(cured, stranger);
        curer.leave();
        stranger.leave();
        helper.assertTrue(toCurer == CURED_BREAD,
            HAGGLER + " charges the player who cured it " + toCurer + " emeralds for bread.");
        helper.assertTrue(toStranger == FULL_BREAD,
            HAGGLER + " charges a stranger " + toStranger + " emeralds for bread.");
        helper.succeed();
    }

    static int breadPrice(AddonEntity trader, Customer customer) {
        trader.mobInteract(customer.player(), InteractionHand.MAIN_HAND);
        int price = trader.getOffers().stream()
            .filter(offer -> offer.getResult().is(Items.BREAD))
            .findFirst().orElseThrow().getCostA().getCount();
        customer.player().closeContainer();
        trader.setTradingPlayer(null);
        return price;
    }

    private static final int CURED_BREAD = 2;
    private static final int FULL_BREAD = 8;

    /** Raises the event whose group transforms the trader and finds what it became. */
    static AddonEntity transformed(GameTestHelper helper, AddonEntity trader, String event,
        net.minecraft.world.entity.Entity cause) {
        EntityEvents.trigger(trader, event, cause, ServerHooks.state());
        return helper.getLevel().getEntitiesOfClass(AddonEntity.class, trader.getBoundingBox().inflate(1),
            found -> found != trader && found.isAlive()).stream().findFirst().orElse(null);
    }

    static AddonEntity haggler(GameTestHelper helper, String event) {
        AddonEntity haggler = BehaviourGameTests.spawn(helper, HAGGLER, STAND);
        if (haggler == null) return null;

        EntityEvents.trigger(haggler, event, null, ServerHooks.state());
        return haggler;
    }

    /** A player on a connection whose far end keeps what the server sent. */
    record Customer(ServerPlayer player, EmbeddedChannel channel) {
        static Customer join(GameTestHelper helper) {
            MinecraftServer server = helper.getLevel().getServer();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "Customer");
            ServerPlayer player =
                new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            server.getPlayerList()
                .placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
            BlockPos spot = helper.absolutePos(STAND.west());
            Platform.placeAt(player, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0f, 0f);
            return new Customer(player, channel);
        }

        /** The translation key of the last screen the server opened for this player, or null. */
        String screenTitleKey() {
            String key = null;
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                if (packet instanceof ClientboundOpenScreenPacket opened) {
                    key = opened.getTitle().getContents() instanceof TranslatableContents translated
                        ? translated.getKey() : opened.getTitle().getString();
                }
            }
            return key;
        }

        void leave() {
            player.level().getServer().getPlayerList().remove(player);
        }
    }
}
