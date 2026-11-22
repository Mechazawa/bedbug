package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.entity.StockMenu;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;

/**
 * The inventory key of a player riding `tp:saddler` opens the saddler's slots while its live
 * inventory is a horse's, and the player's own inventory with no inventory or a chest's.
 */
public final class RiderInventoryClientGameTest implements FabricClientGameTest {
    private static final int SETTLE = 5;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            TestServerContext server = world.getServer();
            server.runCommand("summon tp:saddler ~ ~ ~3");
            AddonEntity saddler = Entities.waitForClient(context, "tp:saddler");
            server.runCommand("ride @p mount @e[type=tp:saddler,limit=1]");
            context.waitFor(client -> client.player != null && client.player.getVehicle() == saddler);

            expectOwnInventory(context, server, "no inventory");

            server.runCommand("bedrock event entity @e[type=tp:saddler] tp:saddler_saddlebag");
            context.waitFor(client -> saddler.opensToRider());
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitFor(client -> Worlds.screen(client) instanceof ContainerScreen
                || Worlds.screen(client) instanceof InventoryScreen);
            Assert.that(context.computeOnClient(client -> Worlds.screen(client) instanceof ContainerScreen),
                () -> "The inventory key of a player riding tp:saddler with a horse inventory opened "
                    + context.computeOnClient(client -> Worlds.screen(client)) + " in place of the saddler's slots.");
            Assert.that(server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayers().getFirst()
                .containerMenu instanceof StockMenu), "The server opened no StockMenu for the rider.");
            close(context, server);

            server.runCommand("bedrock event entity @e[type=tp:saddler] tp:saddler_crate");
            context.waitFor(client -> !saddler.opensToRider());
            expectOwnInventory(context, server, "a chest inventory");
        }
    }

    private static void expectOwnInventory(ClientGameTestContext context, TestServerContext server, String state) {
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitFor(client -> Worlds.screen(client) != null);
        Assert.that(context.computeOnClient(client -> Worlds.screen(client) instanceof InventoryScreen),
            () -> "The inventory key of a player riding tp:saddler with " + state + " opened "
                + context.computeOnClient(client -> Worlds.screen(client)) + " in place of the player's own.");
        close(context, server);
    }

    private static void close(ClientGameTestContext context, TestServerContext server) {
        context.runOnClient(client -> client.player.closeContainer());
        context.waitFor(client -> Worlds.screen(client) == null);
        context.waitTicks(SETTLE);
        Assert.that(server.computeOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            return player.containerMenu == player.inventoryMenu;
        }), "The server kept a menu open after the rider closed its screen.");
    }
}
