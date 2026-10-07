package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a pack wants its items laid out in the creative menu, from `item_catalog/`.
 *
 * Bedrock's catalog states a category, then the groups inside it, then the items inside each group.
 * Java's creative menu has the categories as tabs and nothing below them, so the category decides
 * which tab an item joins and the order the catalog writes is the order the items appear in.
 *
 * An item or a block may instead name its category in its own `description.menu_category`, which is
 * how most packs file what they add. The catalog wins where both name one.
 *
 * @param categoryOf the Bedrock category each item was filed under
 * @param order      every item the catalog names, in the order it names them
 */
public record ItemCatalog(Map<String, String> categoryOf, List<String> order) {
    /** Every catalog the pack ships, folded into one. */
    public static ItemCatalog readAll(BedrockPack pack) {
        Map<String, String> categoryOf = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();

        for (Path file : pack.list("item_catalog", ".json")) {
            try {
                read(Json.parseObject(file), categoryOf, order);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        for (String directory : List.of("items", "blocks")) {
            for (Path file : pack.list(directory, ".json")) {
                try {
                    readMenuCategory(Json.parseObject(file), categoryOf);
                } catch (IOException | RuntimeException error) {
                    McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
                }
            }
        }
        return new ItemCatalog(Map.copyOf(categoryOf), List.copyOf(order));
    }

    /**
     * The category one item or block files itself under, unless a catalog already filed it.
     *
     * `none` keeps it out of every category, which is where an item no category names already is.
     * The group a category is divided into has no Java form, and neither has hiding a name from the
     * command suggestions, so both are reported.
     */
    private static void readMenuCategory(JsonObject root, Map<String, String> categoryOf) {
        JsonElement body = root.has("minecraft:item") ? root.get("minecraft:item") : root.get("minecraft:block");
        if (body == null || !body.isJsonObject()) return;

        JsonElement description = body.getAsJsonObject().get("description");
        if (description == null || !description.isJsonObject()) return;
        String identifier = Json.text(description.getAsJsonObject(), "identifier", null);
        JsonElement menu = description.getAsJsonObject().get("menu_category");
        if (identifier == null || menu == null || !menu.isJsonObject()) return;

        JsonObject category = menu.getAsJsonObject();
        if (category.has("group")) AddonEntities.unsupported("menu_category group");
        if (Json.bool(category, "is_hidden_in_commands", false)) {
            AddonEntities.unsupported("menu_category is_hidden_in_commands");
        }
        String named = Json.text(category, "category", null);
        if (named != null && !named.equals("none")) categoryOf.putIfAbsent(identifier, named);
    }

    private static void read(JsonObject root, Map<String, String> categoryOf, List<String> order) {
        JsonElement catalog = root.get("minecraft:crafting_items_catalog");
        if (catalog == null || !catalog.isJsonObject()) return;

        for (JsonElement element : Json.list(catalog.getAsJsonObject(), "categories")) {
            if (!element.isJsonObject()) continue;

            JsonObject category = element.getAsJsonObject();
            String name = Json.text(category, "category_name", null);
            for (JsonElement one : Json.list(category, "groups")) {
                if (one.isJsonObject()) readGroup(one.getAsJsonObject(), name, categoryOf, order);
            }
        }
    }

    /**
     * One group of a category.
     *
     * A group folds several items into a single square that opens when it is clicked, which Java's
     * menu has no form of, so the items it holds are laid out one after another and the group's own
     * name and icon are reported.
     */
    private static void readGroup(JsonObject group, String category, Map<String, String> categoryOf,
        List<String> order) {
        if (group.has("group_identifier")) {
            AddonEntities.unsupported("item_catalog group_identifier, several items in one square");
        }

        for (JsonElement item : Json.list(group, "items")) {
            String identifier = item.isJsonPrimitive() ? item.getAsString()
                : item.isJsonObject() ? Json.text(item.getAsJsonObject(), "name", null) : null;
            if (identifier == null || categoryOf.containsKey(identifier)) continue;

            categoryOf.put(identifier, category);
            order.add(identifier);
        }
    }
}
