package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.client.render.AddonClientAssets;
import at.ioexception.mcaddon.client.render.AddonEntityRenderer;
import at.ioexception.mcaddon.pack.McAddonPaths;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.sync.AddonManifest;
import at.ioexception.mcaddon.sync.ServedArchives;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * A client joining a server is handed the add-on it lacks, plays with it, and goes back to its own
 * add-ons when it leaves.
 *
 * The server and the client share one process and one `addons/`, so the add-on handed over is one
 * the server serves from `addons-served/` without ever having loaded it: before the join nothing
 * of it is registered anywhere. After the join it has to be in the client's registries, drawn,
 * listed and offered as a pack; after the leave its entries stay registered and nothing offers it;
 * and a second join finds it in the cache and downloads nothing.
 */
public final class AddonSyncClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 1200;
    private static final String PACK = McAddon.MOD_ID + "/sv";

    @Override
    public void runTest(ClientGameTestContext context) {
        Assert.that(!BuiltInRegistries.ITEM.containsKey(Platform.id("sv", "pellet")),
            "sv:pellet was registered before any client joined, so the join would prove nothing.");

        try (TestDedicatedServerContext server = Worlds.server(context)) {
            Path archive = served(server);
            try (var connection = server.connect()) {
                Worlds.waitForChunks(connection);
                Assert.that(Files.isRegularFile(archive), "The served add-on is not in the client's cache at "
                    + archive + ".");
                joined(context, server);
            }
            Worlds.waitForClient(context, client -> !offered(client) && !listed(client), PATIENCE,
                () -> "After leaving, the client still offers " + PACK + " or lists sv:pellet in its tab.");
            Assert.that(BuiltInRegistries.ITEM.containsKey(Platform.id("sv", "pellet"))
                && BuiltInRegistries.ENTITY_TYPE.containsKey(Platform.id("sv", "drifter")),
                "Leaving took the served add-on's entries out of the registries.");

            FileTime fetched = modified(archive);
            try (var again = server.connect()) {
                Worlds.waitForChunks(again);
                Assert.that(modified(archive).equals(fetched), "The second join downloaded the add-on again.");
                Worlds.waitForClient(context, AddonSyncClientGameTest::offered, PATIENCE,
                    () -> "The second join did not offer " + PACK + " again.");
            }
        }
    }

    /** Everything the served add-on adds, read back on the client while it is joined. */
    private static void joined(ClientGameTestContext context, TestDedicatedServerContext server) {
        Assert.that(BuiltInRegistries.BLOCK.containsKey(Platform.id("sv", "crate"))
            && BuiltInRegistries.BLOCK.getValue(Platform.id("sv", "crate")).getStateDefinition()
                .getPossibleStates().size() == 3,
            "sv:crate did not arrive with its three states.");
        Assert.that(context.computeOnClient(client -> AddonClientAssets.of("sv:drifter") != null),
            "sv:drifter has no client definition after the join.");
        Assert.that(context.computeOnClient(AddonSyncClientGameTest::offered),
            "The client's resource packs do not include " + PACK + ".");
        Assert.that(context.computeOnClient(AddonSyncClientGameTest::listed),
            "The add-ons' creative tab does not list sv:pellet.");

        server.runCommand("give @a sv:pellet 2");
        Item pellet = BuiltInRegistries.ITEM.getValue(Platform.id("sv", "pellet"));
        Worlds.waitForClient(context, client -> client.player.getInventory().countItem(pellet) == 2, PATIENCE,
            () -> "Expected two sv:pellet in the client's inventory and found "
                + Minecraft.getInstance().player.getInventory().countItem(pellet) + ".");

        server.runCommand("execute at @p run summon sv:drifter ~ ~ ~2 {NoAI:1b,Tags:[\"served\"]}");
        int id = server.computeOnServer(minecraft -> tagged(minecraft).getId());
        Worlds.waitForClient(context, client -> client.level.getEntity(id) != null, PATIENCE,
            () -> "The served sv:drifter never reached the client.");
        Assert.that(context.computeOnClient(client -> (Object) client.getEntityRenderDispatcher()
                .getRenderer(client.level.getEntity(id)) instanceof AddonEntityRenderer),
            "sv:drifter is not drawn by the add-on renderer.");
    }

    /** Where the client keeps the served archive, by the digest the server's manifest names it under. */
    private static Path served(TestDedicatedServerContext server) {
        AddonManifest manifest = server.computeOnServer(minecraft -> ServedArchives.manifest());
        String sha256 = manifest.entries().stream()
            .filter(entry -> entry.packs().stream().anyMatch(pack -> pack.name().equals("Served Add-On")))
            .findFirst().orElseThrow(() -> new AssertionError("The server does not serve the served add-on."))
            .sha256();
        return McAddonPaths.cache().resolve("received").resolve(sha256 + ".mcaddon");
    }

    private static boolean offered(Minecraft client) {
        return client.getResourcePackRepository().getSelectedIds().contains(PACK);
    }

    /** Whether the add-ons' tab, built now, lists the served item. It reads the built-in registries alone. */
    private static boolean listed(Minecraft client) {
        CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(McAddon.id("addons"));
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(FeatureFlags.REGISTRY.allFlags(), true,
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
        Item pellet = BuiltInRegistries.ITEM.getValue(Platform.id("sv", "pellet"));
        return tab.getDisplayItems().stream().anyMatch(stack -> stack.is(pellet));
    }

    private static Entity tagged(MinecraftServer server) {
        for (Entity entity : server.overworld().getAllEntities()) {
            if (entity.entityTags().contains("served")) return entity;
        }
        throw new AssertionError("sv:drifter never reached the server's world.");
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
