package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonItems;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The creative inventory a player opens holds a pack's items: every one of them in the add-ons' own
 * tab, and the ones whose `menu_category` names a category in the Java tab that category stands for.
 * `tp:lamp` and `tp:totem` file themselves under construction, which is the building blocks tab, and
 * `tp:wand` under equipment, which is the combat tab. An add-on a server handed over earlier in the
 * run stays registered and dormant, and no tab offers its items.
 */
public final class CreativeTabClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(ui -> ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
            Worlds.waitForChunks(world);
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitForScreen(CreativeModeInventoryScreen.class);

            Set<String> declared = AddonItems.all().keySet().stream()
                .filter(id -> !ActiveAddons.isDormant(Identifier.parse(id)))
                .collect(Collectors.toCollection(TreeSet::new));
            Assert.that(!declared.isEmpty(), "The test add-on registered no items to look for.");
            Set<String> own = shown(context, BuiltInRegistries.CREATIVE_MODE_TAB.getValue(McAddon.id("addons")));
            Set<String> missing = new TreeSet<>(declared);
            missing.removeAll(own);
            Assert.that(missing.isEmpty(), "Expected the add-ons tab to show every item the packs declare and it is"
                + " missing " + missing + "; it shows " + own + ".");

            expectIn(context, CreativeModeTabs.BUILDING_BLOCKS, List.of("tp:lamp", "tp:totem"));
            expectIn(context, CreativeModeTabs.COMBAT, List.of("tp:wand"));
        }
    }

    private static void expectIn(ClientGameTestContext context, ResourceKey<CreativeModeTab> key,
        List<String> wanted) {
        Set<String> shown = shown(context, BuiltInRegistries.CREATIVE_MODE_TAB.getValue(key));
        List<String> missing = wanted.stream().filter(item -> !shown.contains(item)).toList();
        Assert.that(missing.isEmpty(), "Expected the " + key.identifier() + " tab to show " + wanted
            + ", which name its category in their menu_category, and it is missing " + missing + ".");
    }

    /** Selects the tab on the open screen and reads back the items the screen then holds. */
    private static Set<String> shown(ClientGameTestContext context, CreativeModeTab tab) {
        context.runOnClient(client -> ((FabricCreativeModeInventoryScreen) Platform.currentScreen())
            .setSelectedTab(tab));
        context.waitTick();
        return context.computeOnClient(client -> ((CreativeModeInventoryScreen) Platform.currentScreen())
            .getMenu().items.stream()
            .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
            .collect(Collectors.toCollection(TreeSet::new)));
    }
}
