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
import net.minecraft.world.item.Items;

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

    /** The items the tab lists, in catalog order, and the items each of the game's tabs is given. */
    private static List<Item> listed = List.of();
    private static Map<ResourceKey<CreativeModeTab>, List<Item>> joined = Map.of();

    /**
     * Registers the one tab, and the additions to the game's own tabs, each reading what the active
     * add-ons list when the game builds the tabs. A tab with nothing in it is not shown.
     */
    public static void register() {
        CreativeModeTab tab = Platform.creativeTab()
            .icon(() -> new ItemStack(listed.isEmpty() ? Items.BOOK : listed.get(0)))
            .title(Component.literal("Add-ons"))
            .displayItems((parameters, output) -> listed.forEach(output::accept))
            .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, McAddon.id("addons"), tab);
        TABS.values().forEach(vanilla -> Platform.addToCreativeTab(vanilla,
            () -> joined.getOrDefault(vanilla, List.of())));
    }

    /**
     * Lists `active` from now on, ordered and filed by the catalogs of `packs`.
     *
     * The game builds a tab's contents once and keeps them until something it watches changes, so
     * a client drops what it built when the set changes under it.
     */
    public static void show(PackSet packs, java.util.Collection<Item> active) {
        Map<String, Item> items = new LinkedHashMap<>();
        AddonItems.all().forEach((identifier, item) -> {
            if (active.contains(item)) items.put(identifier, item);
        });
        ItemCatalog catalog = catalogOf(packs);
        Map<String, Item> ordered = inCatalogOrder(items, catalog);
        listed = List.copyOf(ordered.values());
        joined = joinVanillaTabs(ordered, catalog);
    }

    /** Every behaviour pack's catalog, in pack order, the first to file an item deciding where it goes. */
    private static ItemCatalog catalogOf(PackSet packs) {
        Map<String, String> categoryOf = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;

            ItemCatalog one = ItemCatalog.readAll(pack);
            one.categoryOf().forEach(categoryOf::putIfAbsent);
            one.order().stream().filter(identifier -> !order.contains(identifier)).forEach(order::add);
        }
        return new ItemCatalog(Map.copyOf(categoryOf), List.copyOf(order));
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
    private static Map<ResourceKey<CreativeModeTab>, List<Item>> joinVanillaTabs(Map<String, Item> items,
        ItemCatalog catalog) {
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

        return byTab;
    }
}
