package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ItemCatalog;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One creative tab holding everything the loaded add-ons added, plus the tabs their catalog asks for.
 *
 * An add-on's own tab is what makes its items reachable whether or not it says where they belong.
 * `item_catalog/` says where they belong: a Bedrock category is a Java tab, so an item the catalog
 * files under one joins that tab as well, and the order the catalog writes is the order both tabs
 * lay the items out in.
 */
public final class AddonCreativeTab {
    private AddonCreativeTab() {
    }

    /** The Java tab each Bedrock creative category names. */
    private static final Map<String, ResourceKey<CreativeModeTab>> TABS = Map.of(
        "construction", CreativeModeTabs.BUILDING_BLOCKS,
        "nature", CreativeModeTabs.NATURAL_BLOCKS,
        "equipment", CreativeModeTabs.COMBAT,
        "items", CreativeModeTabs.INGREDIENTS);

    public static void register(PackSet packs) {
        Map<String, Item> items = AddonItems.all();
        if (items.isEmpty()) return;

        ItemCatalog catalog = catalogOf(packs);
        Map<String, Item> ordered = inCatalogOrder(items, catalog);

        Item icon = ordered.values().iterator().next();
        CreativeModeTab tab = Platform.creativeTab()
            .icon(() -> new ItemStack(icon))
            .title(Component.literal("Add-ons"))
            .displayItems((parameters, output) -> ordered.values().forEach(output::accept))
            .build();

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, McAddon.id("addons"), tab);
        joinVanillaTabs(ordered, catalog);
    }

    private static ItemCatalog catalogOf(PackSet packs) {
        ItemCatalog found = ItemCatalog.EMPTY;
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;

            ItemCatalog one = ItemCatalog.readAll(pack);
            if (!one.order().isEmpty()) found = one;
        }
        return found;
    }

    /**
     * The add-on's items, the ones the catalog places first and in its order.
     *
     * An item the catalog says nothing about keeps the order it was registered in and follows the
     * placed ones, so a pack that catalogues half its items still has all of them in the tab.
     */
    private static Map<String, Item> inCatalogOrder(Map<String, Item> items, ItemCatalog catalog) {
        Map<String, Item> ordered = new LinkedHashMap<>();
        for (String identifier : catalog.order()) {
            Item item = items.get(identifier);
            if (item != null) ordered.put(identifier, item);
        }
        ordered.putAll(items);
        return ordered;
    }

    /** Files each catalogued item into the Java tab its category names, and names a category with none. */
    private static void joinVanillaTabs(Map<String, Item> items, ItemCatalog catalog) {
        Map<ResourceKey<CreativeModeTab>, List<Item>> byTab = new LinkedHashMap<>();
        catalog.categoryOf().forEach((identifier, category) -> {
            Item item = items.get(identifier);
            if (item == null) return;

            ResourceKey<CreativeModeTab> tab = TABS.get(category);
            if (tab == null) {
                AddonEntities.unsupported("item_catalog category " + category);
                return;
            }
            byTab.computeIfAbsent(tab, key -> new ArrayList<>()).add(item);
        });

        byTab.forEach((tab, held) -> Platform.addToCreativeTab(tab, List.copyOf(held)));
    }
}
