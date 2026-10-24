package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.client.WorldAddons;
import at.ioexception.mcaddon.client.screen.AddonsScreen;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.state.WorldAddonList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.impl.client.gametest.context.TestSingleplayerContextImpl;
import net.fabricmc.fabric.impl.client.gametest.util.ClientGameTestImpl;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * Create World's More tab opens the Add-Ons screen, where each add-on is switched for the new world.
 *
 * With the test add-on switched off and Done pressed, the screen's data packs are loaded again
 * without it, so the world made from them has none of its recipes, and the world's list has it off.
 * Leaving the world puts the installed set back. Re-Create of that world opens Create World with
 * the world's list chosen and its add-ons active, the resources reloaded without the test add-on.
 * Cancelling Create World puts the installed set back and leaves no choice for the next world.
 */
public final class CreateWorldAddonsClientGameTest implements FabricClientGameTest {
    private static final int MORE_TAB = 2;
    private static final String NAMESPACE = "tp";
    private static final String PACK = McAddon.MOD_ID + "/" + NAMESPACE;

    @Override
    public void runTest(ClientGameTestContext context) {
        Served.waitForInstalled(context);
        Path save = create(context);
        Served.waitForInstalled(context);
        recreate(context, save);
        Served.waitForInstalled(context);
        cancel(context);
    }

    private static Path create(ClientGameTestContext context) {
        openCreateWorld(context);
        Assert.that(context.computeOnClient(client -> recipes(createScreen(client).getUiState()
                .getSettings().dataPackResources().getRecipeManager()) > 0),
            "Create World loaded none of the test add-on's recipes to begin with.");
        switchOff(context);

        Path save = context.computeOnClient(client -> {
            WorldCreationUiState state = createScreen(client).getUiState();
            state.setWorldType(new WorldCreationUiState.WorldTypeEntry(state.getSettings().worldgenLoadContext()
                .lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)));
            state.setSeed("1");
            return client.getLevelSource().getBaseDir().resolve(state.getTargetFolder());
        });
        context.clickScreenButton("selectWorld.create");
        ClientGameTestImpl.waitForWorldLoad(context);
        MinecraftServer server = context.computeOnClient(Minecraft::getSingleplayerServer);
        try (TestSingleplayerContext world = new TestSingleplayerContextImpl(context,
            new TestWorldSaveImpl(context, save), server)) {
            Worlds.waitForChunks(world);
            Assert.that(world.getServer().computeOnServer(minecraft -> recipes(minecraft.getRecipeManager())) == 0,
                "The new world's server loaded the test add-on's recipes.");
            Assert.that(!ActiveAddons.isActive(NAMESPACE), "The test add-on is active in a world made without it.");
        }

        WorldAddonList list = WorldAddonList.read(save)
            .orElseThrow(() -> new AssertionError("The new world was given no add-on list."));
        Assert.that(!list.plays(AddonRows.uuid(NAMESPACE)), "The new world's list has the test add-on on: " + list);
        return save;
    }

    private static void recreate(ClientGameTestContext context, Path save) {
        String levelId = save.getFileName().toString();
        context.runOnClient(client -> Platform.setScreen(new SelectWorldScreen(null)));
        context.waitFor(client -> entry(levelId) != null);
        context.runOnClient(client -> entry(levelId).recreateWorld());
        Worlds.waitForClient(context, client -> Worlds.overlay(client) == null
                && Worlds.screen(client) instanceof CreateWorldScreen, Served.PATIENCE,
            () -> "Re-Create never opened Create World.");
        Assert.that(!ActiveAddons.isActive(NAMESPACE), "Re-Create of a world without the test add-on made it active.");
        Assert.that(!context.computeOnClient(client -> Served.offered(client, NAMESPACE)),
            "Re-Create did not reload the resources without the test add-on.");
        Assert.that(WorldAddons.chosen().map(chosen -> !chosen.plays(AddonRows.uuid(NAMESPACE))).orElse(false),
            "Re-Create did not choose the source world's list for the new world.");
        context.clickScreenButton("gui.cancel");
    }

    private static WorldSelectionList.WorldListEntry entry(String levelId) {
        WorldSelectionList list = (WorldSelectionList) field(SelectWorldScreen.class, "list", Platform.currentScreen());
        return list.children().stream()
            .filter(WorldSelectionList.WorldListEntry.class::isInstance)
            .map(WorldSelectionList.WorldListEntry.class::cast)
            .filter(entry -> entry.getLevelSummary().getLevelId().equals(levelId))
            .findFirst()
            .orElse(null);
    }

    private static void cancel(ClientGameTestContext context) {
        openCreateWorld(context);
        switchOff(context);
        context.clickScreenButton("gui.cancel");
        Served.waitForInstalled(context);
        Assert.that(WorldAddons.chosen().isEmpty(), "Cancelling Create World left its add-on choice in place.");
        Assert.that(context.computeOnClient(client -> Served.offered(client, NAMESPACE)),
            "Cancelling Create World did not reload the resources with the test add-on.");
        context.runOnClient(client -> Platform.setScreen(null));
    }

    private static void openCreateWorld(ClientGameTestContext context) {
        context.runOnClient(client -> CreateWorldScreen.openFresh(client, () -> Platform.setScreen(null)));
        context.waitForScreen(CreateWorldScreen.class);
        context.runOnClient(client -> ((TabNavigationBar)
            field(CreateWorldScreen.class, "tabNavigationBar", createScreen(client))).selectTab(MORE_TAB, true));
        context.waitTick();
    }

    /**
     * Opens Add-Ons from the More tab, switches the test add-on off, presses Done and waits for
     * Create World's data packs to load without it.
     */
    private static void switchOff(ClientGameTestContext context) {
        context.clickScreenButton("mcaddon.addons.button");
        context.waitForScreen(AddonsScreen.class);
        Assert.that("On".equals(AddonRows.state(context, NAMESPACE)),
            "Create World does not start the test add-on On.");
        AddonRows.turn(context, NAMESPACE);
        Assert.that("Off".equals(AddonRows.state(context, NAMESPACE)), "The switch did not turn the test add-on Off.");
        context.clickScreenButton("gui.done");
        Worlds.waitForClient(context, client -> Worlds.overlay(client) == null
                && Worlds.screen(client) instanceof CreateWorldScreen screen
                && !screen.getUiState().getSettings().dataConfiguration().dataPacks().getEnabled().contains(PACK)
                && recipes(screen.getUiState().getSettings().dataPackResources().getRecipeManager()) == 0,
            Served.PATIENCE, () -> "Create World never loaded its data packs again without the test add-on.");
        Assert.that(!ActiveAddons.isActive(NAMESPACE), "Switching the test add-on off left it active.");
    }

    private static CreateWorldScreen createScreen(Minecraft client) {
        return (CreateWorldScreen) Worlds.screen(client);
    }

    private static long recipes(RecipeManager manager) {
        return manager.getRecipes().stream()
            .filter(holder -> Platform.idOf(holder.id()).getNamespace().equals(NAMESPACE))
            .count();
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
