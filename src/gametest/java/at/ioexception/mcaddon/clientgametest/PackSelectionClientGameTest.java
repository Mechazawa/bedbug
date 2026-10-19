package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.packs.repository.PackRepository;

import java.lang.reflect.Field;
import java.util.stream.Stream;

/**
 * Add-on packs are offered to the game as required packs, and none of them is listed in the Resource
 * Packs screen or in Create World's Data Packs screen. Pressing Done leaves them selected.
 */
public final class PackSelectionClientGameTest implements FabricClientGameTest {
    private static final int MORE_TAB = 2;
    private static final String PACK = McAddon.MOD_ID + "/tp";

    @Override
    public void runTest(ClientGameTestContext context) {
        resourcePacks(context);
        dataPacks(context);
    }

    private static void resourcePacks(ClientGameTestContext context) {
        context.runOnClient(client -> Platform.setScreen(options(client)));
        context.waitForScreen(OptionsScreen.class);
        context.clickScreenButton("options.resourcepack");
        context.waitForScreen(PackSelectionScreen.class);

        Assert.that(context.computeOnClient(client -> client.getResourcePackRepository().getSelectedIds())
            .contains(PACK), "The client offers no resource pack for the test add-on.");
        assertNothingListed(context, "Resource Packs");
        context.clickScreenButton("gui.done");
        Assert.that(context.computeOnClient(client -> client.getResourcePackRepository().getSelectedIds())
            .contains(PACK), "Pressing Done in Resource Packs deselected the add-on's pack.");
        context.runOnClient(client -> Platform.setScreen(null));
    }

    private static OptionsScreen options(Minecraft client) {
        //? if >=26.3 {
        return new OptionsScreen(null, client.options);
        //?} else {
        /*return new OptionsScreen(null, client.options, false);
        *///?}
    }

    private static void dataPacks(ClientGameTestContext context) {
        context.runOnClient(client -> CreateWorldScreen.openFresh(client, () -> { }));
        context.waitForScreen(CreateWorldScreen.class);
        context.runOnClient(client -> ((TabNavigationBar) field(CreateWorldScreen.class, "tabNavigationBar", Platform.currentScreen()))
            .selectTab(MORE_TAB, true));
        context.waitTick();
        context.clickScreenButton("selectWorld.dataPacks");
        context.waitForScreen(PackSelectionScreen.class);

        PackRepository repository = context.computeOnClient(client -> {
            PackSelectionModel model = (PackSelectionModel) field(PackSelectionScreen.class, "model", Platform.currentScreen());
            return (PackRepository) field(PackSelectionModel.class, "repository", model);
        });
        Assert.that(repository.getSelectedIds().contains(PACK), "The data pack repository offers no pack for the test add-on.");
        assertNothingListed(context, "Data Packs");
        context.clickScreenButton("gui.done");
        context.waitForScreen(CreateWorldScreen.class);
        Assert.that(repository.getSelectedIds().contains(PACK), "Pressing Done in Data Packs deselected the add-on's pack.");
        context.runOnClient(client -> Platform.setScreen(null));
    }

    private static void assertNothingListed(ClientGameTestContext context, String screen) {
        long listed = context.computeOnClient(client -> {
            PackSelectionModel model = (PackSelectionModel) field(PackSelectionScreen.class, "model", Platform.currentScreen());
            return Stream.concat(model.getSelected(), model.getUnselected())
                .filter(entry -> entry.getId().startsWith(McAddon.MOD_ID + "/")).count();
        });
        Assert.that(listed == 0, "The " + screen + " screen lists " + listed + " add-on packs.");
    }

    private static Object field(Class<?> owner, String name, Object instance) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
