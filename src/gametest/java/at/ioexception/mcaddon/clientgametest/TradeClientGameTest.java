package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Trading with a pack's trader, from the use key to the items changing hands.
 *
 * `tp:ember` sells from `trading/ember_trades.json`. Its first tier always offers one lime wool,
 * which the table writes as `minecraft:wool:5`, for two emeralds, and a dyed leather helmet for
 * three while it is day; its second tier waits for ten experience and sells a golden sword, so the
 * sword is not on the screen of a trader nobody has traded with. Buying the wool earns the trader
 * the one experience the trade states.
 */
public final class TradeClientGameTest implements FabricClientGameTest {
    private static final Vec3 TRADER = new Vec3(0.5, Stage.FLOOR + 1, 2.5);
    private static final int EMERALDS = 10;

    /** The payment and result slots come first in a merchant's menu. */
    private static final int RESULT_SLOT = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "survival");
            world.getServer().runCommand("summon tp:ember " + TRADER.x + " " + TRADER.y + " " + TRADER.z
                + " {NoAI:1b}");
            Entities.waitForClient(context, "tp:ember");
            world.getServer().runCommand("item replace entity @a inventory.0 with minecraft:emerald " + EMERALDS);
            context.waitFor(client -> client.player.getInventory().countItem(Items.EMERALD)
                == EMERALDS);

            Stage.lookAt(context, TRADER.add(0, 0.2, 0));
            context.waitFor(client -> client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof AddonEntity);
            context.getInput().pressKey(options -> options.keyUse);
            context.waitForScreen(MerchantScreen.class);
            context.waitFor(client -> !offers(client).isEmpty());

            List<String> shown = context.computeOnClient(client -> offers(client).stream()
                .map(TradeClientGameTest::describe).toList());
            Assert.that(shown.contains("2 minecraft:emerald -> 1 minecraft:lime_wool"), "Expected tp:ember's screen"
                + " to offer its pack's lime wool for two emeralds and it offers " + shown + ".");
            Assert.that(shown.contains("3 minecraft:emerald -> 1 minecraft:leather_helmet"), "Expected tp:ember's"
                + " screen to offer its pack's daytime leather helmet for three emeralds and it offers " + shown + ".");
            Assert.that(shown.stream().noneMatch(offer -> offer.endsWith("minecraft:golden_sword")), "Expected"
                + " tp:ember's second tier, which waits for ten experience, to be off the screen and it offers "
                + shown + ".");

            int wool = shown.indexOf("2 minecraft:emerald -> 1 minecraft:lime_wool");
            // Pressed the way Fabric's own clickScreenButton presses a button, which an offer's has no
            // label for: what a click on it would run, without depending on where the pointer is.
            context.runOnClient(client -> tradeButtons(client).get(wool).onPress(new MouseButtonInfo(0, 0)));
            context.waitFor(client -> id(client.player.containerMenu.getSlot(RESULT_SLOT).getItem())
                .equals("minecraft:lime_wool"));
            // Picked up rather than shift-clicked, which would buy for as long as the payment lasts.
            context.runOnClient(client -> client.gameMode.handleContainerInput(
                client.player.containerMenu.containerId, RESULT_SLOT, 0, ContainerInput.PICKUP, client.player));
            context.waitTick();
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitForScreen(null);

            Worlds.waitForServer(context, world, server -> carried(server).containsKey("minecraft:lime_wool"), 40,
                "The lime wool was taken out of tp:ember's result slot and never reached the player.");
            Map<String, Integer> carried = world.getServer().computeOnServer(TradeClientGameTest::carried);
            Assert.that(carried.equals(Map.of("minecraft:emerald", EMERALDS - 2, "minecraft:lime_wool", 1)),
                "Expected buying the wool to leave " + (EMERALDS - 2) + " emeralds and one lime wool, and the player"
                    + " carries " + carried + ".");
            int earned = world.getServer().computeOnServer(server -> trader(server).getVillagerXp());
            Assert.that(earned == 1, "Expected tp:ember to earn the one trader_exp its wool trade states and it holds "
                + earned + ".");
        }
    }

    private static List<MerchantOffer> offers(Minecraft client) {
        return Platform.currentScreen() instanceof MerchantScreen screen ? screen.getMenu().getOffers() : List.of();
    }

    /** The screen's trade buttons, top to bottom, which is the order of the offers they stand for. */
    private static List<Button> tradeButtons(Minecraft client) {
        return Platform.currentScreen().children().stream()
            .filter(Button.class::isInstance).map(Button.class::cast).toList();
    }

    private static String describe(MerchantOffer offer) {
        return offer.getCostA().getCount() + " " + id(offer.getCostA()) + " -> " + offer.getResult().getCount()
            + " " + id(offer.getResult());
    }

    private static AddonEntity trader(MinecraftServer server) {
        return Stage.player(server).level().getEntitiesOfClass(AddonEntity.class,
            Stage.player(server).getBoundingBox().inflate(16)).get(0);
    }

    private static Map<String, Integer> carried(MinecraftServer server) {
        Map<String, Integer> counts = new TreeMap<>();
        Inventory inventory = Stage.player(server).getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) counts.merge(id(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
