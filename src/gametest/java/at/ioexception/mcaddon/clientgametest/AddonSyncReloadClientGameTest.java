package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.registry.ActiveAddons;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A connection the server closes leaves the client on its installed add-ons, whether it closes
 * while the client reloads its resources for the server's add-ons or once the client plays.
 *
 * The client starts joining, and the moment the reload the server's add-ons asked for is on the
 * screen the server closes the connection. Once the reload is over the client has to have put its
 * installed set back and stopped offering the server's pack. A second join has to work, and when
 * the server closes that one with the client in its world, the client has to go back again: the
 * client drops the tasks queued for it as such a connection ends, so a leave queued from the end of
 * the connection is lost.
 */
public final class AddonSyncReloadClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Served.waitForInstalled(context);
        try (TestDedicatedServerContext server = Worlds.server(context)) {
            Worlds.startConnecting(context, server);
            Worlds.waitForClient(context, client -> Worlds.overlay(client) != null, Served.PATIENCE,
                () -> "The join never reloaded the client's resources.");
            Assert.that(ActiveAddons.active() != ActiveAddons.installed(),
                "The client reloads with its installed add-ons active, so the join did not change them.");
            closeAll(server);

            context.waitForScreen(DisconnectedScreen.class);
            Served.waitForInstalled(context);
            Worlds.waitForClient(context, client -> !Served.offered(client, "sv"), Served.PATIENCE,
                () -> "After the connection closed during the reload, the client still offers the server's pack.");

            Worlds.waitForChunks(server.connect());
            Assert.that(context.computeOnClient(client -> Served.offered(client, "sv")),
                "The join after the closed one does not offer the server's pack.");
            closeAll(server);
            context.waitForScreen(DisconnectedScreen.class);
            Served.waitForInstalled(context);
            Assert.that(context.computeOnClient(client -> !Served.offered(client, "sv")),
                "After the server closed the connection of a client in its world, the client still offers its pack.");
        }
        context.setScreen(() -> new TitleScreen());
    }

    /** Closes every connection from the server's end, in whatever phase it is. */
    private static void closeAll(TestDedicatedServerContext server) {
        server.runOnServer(minecraft -> List.copyOf(minecraft.getConnection().getConnections())
            .forEach(connection -> connection.disconnect(Component.literal("Closed by the test."))));
    }
}
