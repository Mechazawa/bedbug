package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.client.screen.AddonRow;
import at.ioexception.mcaddon.client.screen.AddonsScreen;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.state.WorldAddonList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * A single player world plays the add-ons its own list turns on.
 *
 * A new world is given a list with every installed add-on on. With the test add-on written off,
 * the world opens with it inactive on both sides: its item is dormant, neither the server's data
 * packs nor the client's resource packs select it, its script does not run (the script counts each
 * first spawn in the player's own store, so the count stands still), and the pause menu's Add-Ons
 * screen reads it Off. Written on again, it is all back.
 */
public final class WorldAddonsClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;
    private static final String NAMESPACE = "tp";
    private static final String ADDON = "Test Add-On";

    @Override
    public void runTest(ClientGameTestContext context) {
        Served.waitForInstalled(context);
        TestWorldSave save;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            save = world.getWorldSave();
            Worlds.waitForChunks(world);
            waitForVisits(context, world, 1.0);
        }

        Path root = save.getSaveDirectory();
        WorldAddonList written = WorldAddonList.read(root)
            .orElseThrow(() -> new AssertionError("A new world was given no add-on list."));
        Assert.that(written.equals(WorldAddonList.allOn(ActiveAddons.installed())),
            "A new world's list does not have every installed add-on on: " + written);

        written.with(uuid(), false).write(root);
        try (TestSingleplayerContext world = save.open()) {
            Worlds.waitForChunks(world);
            Assert.that(!ActiveAddons.isActive(NAMESPACE), "The test add-on is active in a world that turns it off.");
            Assert.that(ActiveAddons.isDormant(Platform.id(NAMESPACE, "dust")),
                "tp:dust is not dormant in a world that turns its add-on off.");
            Assert.that(!world.getServer().computeOnServer(server -> selected(server)),
                "The server selected the test add-on's data pack in a world that turns it off.");
            Assert.that(!context.computeOnClient(client -> Served.offered(client, NAMESPACE)),
                "The client's resources were not reloaded without the test add-on before the world loaded.");
            context.waitTicks(40);
            Object visits = world.getServer().computeOnServer(WorldAddonsClientGameTest::visits);
            Assert.that(Double.valueOf(1.0).equals(visits),
                "The test add-on's script ran in a world that turns it off.");
            Assert.that("Off".equals(rowState(context)), "The Add-Ons screen does not read the test add-on Off.");
        }

        Served.waitForInstalled(context);
        written.with(uuid(), true).write(root);
        try (TestSingleplayerContext world = save.open()) {
            Worlds.waitForChunks(world);
            Assert.that(ActiveAddons.isActive(NAMESPACE)
                    && world.getServer().computeOnServer(server -> selected(server))
                    && context.computeOnClient(client -> Served.offered(client, NAMESPACE)),
                "The test add-on did not come back on with its world's list.");
            waitForVisits(context, world, 2.0);
            Assert.that("On".equals(rowState(context)), "The Add-Ons screen does not read the test add-on On.");
        }
        Served.waitForInstalled(context);
    }

    private static UUID uuid() {
        return ActiveAddons.installed().converted().stream()
            .filter(one -> one.namespace().equals(NAMESPACE))
            .map(ConvertedPack::pack)
            .map(pack -> pack.manifest().uuid())
            .findFirst()
            .orElseThrow(() -> new AssertionError("The test add-on is not installed."));
    }

    private static boolean selected(MinecraftServer server) {
        return server.getPackRepository().getSelectedIds().contains(McAddon.MOD_ID + "/" + NAMESPACE);
    }

    /** The state the pause menu's Add-Ons screen gives the test add-on's row. */
    private static String rowState(ClientGameTestContext context) {
        context.runOnClient(client -> Platform.setScreen(new AddonsScreen(null)));
        context.waitForScreen(AddonsScreen.class);
        String state = context.computeOnClient(client -> Platform.currentScreen().children().stream()
            .filter(AddonRow.class::isInstance)
            .map(AddonRow.class::cast)
            .filter(row -> row.title().startsWith(ADDON + " "))
            .map(AddonRow::state)
            .findFirst()
            .orElse("missing"));
        context.runOnClient(client -> Platform.setScreen(null));
        return state;
    }

    private static void waitForVisits(ClientGameTestContext context, TestSingleplayerContext world, double count) {
        Worlds.waitForServer(context, world.getServer(), server -> Double.valueOf(count).equals(visits(server)),
            PATIENCE, server -> "Expected the player's own store to count " + count + " visits, and found "
                + visits(server));
    }

    private static Object visits(MinecraftServer server) {
        return ServerHooks.state().dynamicProperty(server.getPlayerList().getPlayers().get(0).getUUID(), "tp:visits");
    }
}
