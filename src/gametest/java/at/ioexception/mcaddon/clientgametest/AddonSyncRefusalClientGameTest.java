package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Properties;

/**
 * A client refuses a server whose copy of an add-on it already registered is built differently.
 *
 * The client first joins a server handing out the `sv` add-on, which registers its crate with three
 * states. It then joins a server handing out a later release whose crate has four, which cannot be
 * registered over the first without a restart: the client has to stay out of the world, show a
 * message naming the add-on, and keep its own add-ons and the crate it has.
 */
public final class AddonSyncRefusalClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = Worlds.server(context)) {
            try (var connection = server.connect()) {
                Worlds.waitForChunks(connection);
            }
        }
        Served.waitForInstalled(context);

        try (TestDedicatedServerContext server = Served.server(context, Served.RESHAPED, new Properties())) {
            Worlds.startConnecting(context, server);
            context.waitForScreen(DisconnectedScreen.class);
            String shown = context.computeOnClient(client -> Worlds.screen(client).getNarrationMessage().getString());
            Assert.that(shown.contains("The server's copy of Served Add-On cannot replace yours"),
                "The refusal does not name the add-on. The screen says: " + shown);
            Assert.that(context.computeOnClient(client -> client.level == null),
                "The client entered the world of a server whose add-on it refused.");
            Assert.that(server.computeOnServer(minecraft -> minecraft.getPlayerList().getPlayerCount() == 0),
                "The refused client is on the server's player list.");
        }
        Assert.that(BuiltInRegistries.BLOCK.getValue(Platform.id("sv", "crate")).getStateDefinition()
                .getPossibleStates().size() == 3,
            "The refused copy changed the registered sv:crate.");
        Served.waitForInstalled(context);
        Assert.that(context.computeOnClient(client -> !Served.offered(client, "sv")),
            "The client still offers the refused add-on's pack.");
        Assert.that(ActiveAddons.active() == ActiveAddons.installed(), "The refusal left another add-on set active.");
        context.setScreen(() -> new TitleScreen());
    }
}
