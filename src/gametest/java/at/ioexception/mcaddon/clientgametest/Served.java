package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.sync.ServedArchives;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Properties;

/**
 * The directories of archives the client game test task builds for a dedicated server to hand out
 * without having loaded them, and pointing a server at one of them.
 *
 * `addons-served` holds the `sv` add-on, which every test joining a dedicated server is handed;
 * `addons-reshaped` a later release of it whose block has one more state; `addons-other` the same
 * content as the add-on `sw`. A server reads the `mcaddon.served` property as it prepares its
 * manifest, so the property is changed only for that moment.
 */
final class Served {
    static final String RESHAPED = "addons-reshaped";
    static final String OTHER = "addons-other";
    static final int PATIENCE = 1200;

    private Served() {
    }

    /** A dedicated server handing out the archives in `directory` beside its own add-ons. */
    static TestDedicatedServerContext server(ClientGameTestContext context, String directory, Properties properties) {
        TestDedicatedServerContext server = Worlds.server(context, properties);
        handOut(server, directory);
        return server;
    }

    /** Has a running server hand out the archives in `directory` to every client that joins from now on. */
    static void handOut(TestServerContext server, String directory) {
        server.runOnServer(minecraft -> {
            String standard = System.getProperty("mcaddon.served");
            System.setProperty("mcaddon.served", Path.of(standard).resolveSibling(directory).toString());
            try {
                ServedArchives.forget();
                ServedArchives.manifest();
            } finally {
                System.setProperty("mcaddon.served", standard);
            }
        });
    }

    /** Whether the client offers the converted pack of the add-on with this namespace. */
    static boolean offered(Minecraft client, String namespace) {
        return client.getResourcePackRepository().getSelectedIds().contains(McAddon.MOD_ID + "/" + namespace);
    }

    /**
     * Waits for the client to be back on its installed add-ons, which a leave owed by an earlier
     * test reaches only once the client ticks.
     */
    static void waitForInstalled(ClientGameTestContext context) {
        Worlds.waitForClient(context, client -> ActiveAddons.active() == ActiveAddons.installed()
                && Worlds.overlay(client) == null, PATIENCE,
            () -> "The client never went back to its installed add-ons.");
    }
}
