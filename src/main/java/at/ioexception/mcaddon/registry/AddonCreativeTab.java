package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * One creative tab holding everything the loaded add-ons added.
 *
 * Bedrock groups items with `item_catalog`, which has no Java equivalent worth building; a single tab
 * is what makes an add-on's items reachable without one.
 */
public final class AddonCreativeTab {
    private AddonCreativeTab() {
    }

    public static void register() {
        Map<String, Item> items = AddonItems.all();
        if (items.isEmpty()) return;

        Item icon = items.values().iterator().next();
        CreativeModeTab tab = at.ioexception.mcaddon.platform.Platform.creativeTab()
            .icon(() -> new ItemStack(icon))
            .title(Component.literal("Add-ons"))
            .displayItems((parameters, output) -> items.values().forEach(output::accept))
            .build();

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, McAddon.id("addons"), tab);
    }
}
