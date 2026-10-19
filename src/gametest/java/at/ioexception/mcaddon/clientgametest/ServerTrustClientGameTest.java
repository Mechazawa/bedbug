package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.TrustedServers;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerIdentity;
import at.ioexception.mcaddon.util.Json;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * A client keeps the identity a server presented and asks the player before it plays on a server at
 * the same address presenting another.
 *
 * Neither server has a saved key, so each generates its own as it starts: the first is trusted as
 * the client meets it, and the second, started on the first's port, is a known address with a new
 * identity. Accepting it puts the player in the world and keeps the new key.
 */
public final class ServerTrustClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        int port = Worlds.freePort();
        String first;
        try (TestDedicatedServerContext server = Worlds.server(context, port)) {
            server.runCommand("bedrock serveridentity delete");
            first = server.computeOnServer(minecraft -> ServerIdentity.running().publicText());
            try (var connection = server.connect()) {
                Worlds.waitForChunks(connection);
                Worlds.waitForClient(context, client -> first.equals(trusted(address(client))), PATIENCE,
                    () -> "The client did not keep the identity of a server it met for the first time.");
                Assert.that(context.computeOnClient(client -> !asking(Platform.currentScreen())),
                    "The client asked about a server it had never met.");
            }
        }

        try (TestDedicatedServerContext server = Worlds.server(context, port)) {
            String second = server.computeOnServer(minecraft -> ServerIdentity.running().publicText());
            Assert.that(!second.equals(first), "The second server started with the first one's identity.");
            try (var connection = server.connect()) {
                Worlds.waitForClient(context, client -> asking(Platform.currentScreen()), PATIENCE,
                    () -> "The client did not ask about a known server presenting a new identity; the screen is "
                        + Platform.currentScreen() + ".");
                String address = context.computeOnClient(ServerTrustClientGameTest::address);
                Assert.that(first.equals(trusted(address)),
                    "The client trusted the new identity before it was accepted.");

                context.clickScreenButton(TrustedServers.ACCEPT);
                Worlds.waitForClient(context, client -> Platform.currentScreen() == null && client.player != null,
                    PATIENCE, () -> "Accepting the new identity did not leave the player in the world.");
                Assert.that(second.equals(trusted(address)), "Accepting did not keep the new identity.");
                Assert.that(server.computeOnServer(minecraft -> minecraft.getPlayerCount()) == 1,
                    "The server does not hold the player after the new identity was accepted.");
            }
        }
    }

    private static boolean asking(Screen screen) {
        return screen instanceof ConfirmScreen
            && screen.getTitle().getString().equals(Component.translatable(TrustedServers.TITLE).getString());
    }

    private static String address(net.minecraft.client.Minecraft client) {
        return client.getCurrentServer() == null ? null : client.getCurrentServer().ip;
    }

    private static String trusted(String address) {
        if (address == null || !Files.isRegularFile(TrustedServers.file())) return null;
        try {
            return Json.text(Json.parseObject(TrustedServers.file()), address, null);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
