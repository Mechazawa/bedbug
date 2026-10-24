package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.screen.AddonsScreen;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.resource.AddonSet;
import at.ioexception.mcaddon.state.WorldAddonList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Path;
import java.util.List;

/**
 * Edit World's Add-Ons screen switches the add-ons in the world's own list and activates nothing.
 *
 * An installed add-on the list does not name reads Off. The test add-on ships a dimension, so its
 * row warns that the world may not open without it while it is off, and does not while it is on.
 * Each switch is written into the world's file at once.
 */
public final class EditWorldAddonsClientGameTest implements FabricClientGameTest {
    private static final String NAMESPACE = "tp";

    @Override
    public void runTest(ClientGameTestContext context) {
        Served.waitForInstalled(context);
        Path root;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            root = world.getWorldSave().getSaveDirectory();
        }
        Served.waitForInstalled(context);
        new WorldAddonList(List.of()).write(root);
        AddonSet active = ActiveAddons.active();

        openEditWorld(context, root);
        context.clickScreenButton("mcaddon.addons.button");
        context.waitForScreen(AddonsScreen.class);
        Assert.that("Off".equals(AddonRows.state(context, NAMESPACE)),
            "Edit World reads an installed add-on the world's list does not name as On.");
        Assert.that(!AddonRows.warning(context, NAMESPACE).isEmpty(),
            "The test add-on's row gives no warning about its dimension while it is off.");

        AddonRows.turn(context, NAMESPACE);
        Assert.that("On".equals(AddonRows.state(context, NAMESPACE)), "The switch did not turn the test add-on On.");
        Assert.that(AddonRows.warning(context, NAMESPACE).isEmpty(),
            "The test add-on's row warns about its dimension while it is on.");
        Assert.that(list(root).plays(AddonRows.uuid(NAMESPACE)), "Switching the test add-on on did not write it on.");

        AddonRows.turn(context, NAMESPACE);
        Assert.that("Off".equals(AddonRows.state(context, NAMESPACE)), "The switch did not turn the test add-on Off.");
        Assert.that(!AddonRows.warning(context, NAMESPACE).isEmpty(),
            "The test add-on's row gives no warning about its dimension once it is turned off.");
        Assert.that(!list(root).plays(AddonRows.uuid(NAMESPACE)),
            "Switching the test add-on off did not write it off.");
        Assert.that(ActiveAddons.active() == active, "Switching an add-on in Edit World activated a set.");

        context.clickScreenButton("gui.done");
        context.waitForScreen(EditWorldScreen.class);
        context.clickScreenButton("gui.cancel");
        context.waitFor(client -> !(Platform.currentScreen() instanceof EditWorldScreen));
        Assert.that(ActiveAddons.active() == active, "Leaving Edit World activated a set.");
    }

    private static WorldAddonList list(Path root) {
        return WorldAddonList.read(root).orElseThrow(() -> new AssertionError("The world has no add-on list."));
    }

    private static void openEditWorld(ClientGameTestContext context, Path root) {
        context.runOnClient(client -> {
            try {
                LevelStorageSource.LevelStorageAccess access =
                    client.getLevelSource().validateAndCreateAccess(root.getFileName().toString());
                Platform.setScreen(EditWorldScreen.create(client, access, changed -> {
                    access.safeClose();
                    Platform.setScreen(null);
                }));
            } catch (Exception unopened) {
                throw new AssertionError("The test world could not be opened for editing.", unopened);
            }
        });
        context.waitForScreen(EditWorldScreen.class);
    }
}
