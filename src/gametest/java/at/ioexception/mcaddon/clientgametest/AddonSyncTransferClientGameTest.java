package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.resource.AddonSet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * A client a server transfers to a server with another add-on set leaves the first visit, then
 * plays with the second set.
 *
 * A game test client can be joined to one dedicated server at a time, so the transfer goes back to
 * the same server after it was pointed at the `sw` add-on instead of `sv`: to the client that is a
 * second server with another set. Every set the client makes active is written down, so the
 * transfer has to show the installed set put back once and then the second server's set, in that
 * order, and the client has to stay on the second set.
 */
public final class AddonSyncTransferClientGameTest implements FabricClientGameTest {
    private static final List<String> ACTIVATED = new CopyOnWriteArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> ActiveAddons.onActivate(set -> ACTIVATED.add(named(set))));
        Properties properties = new Properties();
        properties.setProperty("accepts-transfers", "true");
        properties.setProperty("max-players", "2");
        try (TestDedicatedServerContext server = Worlds.server(context, properties)) {
            try (var connection = server.connect()) {
                Worlds.waitForChunks(connection);
                Assert.that(context.computeOnClient(client -> Served.offered(client, "sv")
                        && !Served.offered(client, "sw")),
                    "The first visit does not offer the sv add-on alone.");

                Served.handOut(server, Served.OTHER);
                int port = server.computeOnServer(MinecraftServer::getPort);
                Object before = context.computeOnClient(client -> client.level);
                ACTIVATED.clear();
                server.runCommand("transfer 127.0.0.1 " + port + " @a");
                Worlds.waitForClient(context, client -> client.level != null && client.level != before
                        && client.player != null && Served.offered(client, "sw"),
                    Served.PATIENCE, () -> "The transferred client never reached a world offering the sw add-on. "
                        + "It shows " + Worlds.screen(Minecraft.getInstance()).getNarrationMessage().getString() + ".");
                Worlds.waitForChunks(connection);
                // Long enough for a leave owed by the first visit to have run, had it waited.
                context.waitTicks(100);
                Assert.that(ACTIVATED.equals(List.of("installed", "sw tp")),
                    () -> "The transfer did not put the installed set back once and then activate the second "
                        + "server's set. It activated: " + ACTIVATED);
                Assert.that(context.computeOnClient(client -> Worlds.overlay(client) == null
                        && Served.offered(client, "sw") && Served.offered(client, "tp")
                        && !Served.offered(client, "sv")),
                    "After the transfer the client does not play with the second server's set of tp and sw.");

                server.runCommand("give @a sw:pellet 1");
                Item pellet = BuiltInRegistries.ITEM.getValue(Platform.id("sw", "pellet"));
                Worlds.waitForClient(context, client -> client.player.getInventory().countItem(pellet) == 1,
                    Served.PATIENCE, () -> "sw:pellet never reached the transferred client.");
            }
        }
    }

    /** `installed`, or the namespaces of the set's packs. */
    private static String named(AddonSet set) {
        return set == ActiveAddons.installed() ? "installed" : set.converted().stream()
            .map(ConvertedPack::namespace).distinct().sorted().collect(Collectors.joining(" "));
    }
}
