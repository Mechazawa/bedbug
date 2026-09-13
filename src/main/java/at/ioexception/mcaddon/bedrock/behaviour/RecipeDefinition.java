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
import java.util.Set;

/**
 * A crafting recipe a behaviour pack declares.
 *
 * Bedrock's shaped recipes use a key map and a pattern the same way Java's do, which is what makes
 * these two of the few formats that convert almost directly. `tags` names the block the recipe is
 * made at, which is what decides the Java recipe type a furnace or a stonecutter recipe becomes.
 */
public record RecipeDefinition(String identifier, Kind kind, List<String> pattern,
    Map<String, String> key, List<Ingredient> ingredients, String result, int resultCount,
    String group, List<String> tags, Smithing smithing) {

    public enum Kind { SHAPED, SHAPELESS, FURNACE, SMITHING_TRANSFORM, SMITHING_TRIM }

    public record Ingredient(String item, int count) {
    }

    /** A smithing table's three inputs: what shapes the result, what is changed, and what changes it. */
    public record Smithing(String template, String base, String addition) {
    }

    /** The recipe kinds this mod converts, against which a pack's own file is checked. */
    private static final Set<String> CONVERTED = Set.of(
        "minecraft:recipe_shaped", "minecraft:recipe_shapeless", "minecraft:recipe_furnace",
        "minecraft:recipe_smithing_transform", "minecraft:recipe_smithing_trim");

    /** Every field of a recipe body this reads, qualified by the kind carrying it. */
    private static final Set<String> HANDLES = Set.of(
        "shaped.description", "shaped.description.identifier", "shaped.pattern", "shaped.key",
        "shaped.key.item", "shaped.key.tag", "shaped.result", "shaped.result.item",
        "shaped.result.count", "shaped.group", "shaped.tags",
        "shapeless.description", "shapeless.description.identifier", "shapeless.ingredients",
        "shapeless.ingredients.item", "shapeless.ingredients.tag", "shapeless.ingredients.count",
        "shapeless.result", "shapeless.result.item", "shapeless.result.count", "shapeless.group",
        "shapeless.tags",
        "furnace.description", "furnace.description.identifier", "furnace.input",
        "furnace.input.item", "furnace.input.tag", "furnace.output", "furnace.tags",
        "smithing_transform.description", "smithing_transform.description.identifier",
        "smithing_transform.template", "smithing_transform.base", "smithing_transform.addition",
        "smithing_transform.result", "smithing_transform.tags",
        "smithing_trim.description", "smithing_trim.description.identifier",
        "smithing_trim.template", "smithing_trim.template.tag", "smithing_trim.base",
        "smithing_trim.base.tag", "smithing_trim.addition", "smithing_trim.addition.tag",
        "smithing_trim.tags");

    /** The fields Java's own recipe has no place for, each reported once a pack writes one. */
    private static final List<String> REPORTED = List.of("priority", "unlock", "assume_symmetry");

    private static final Map<Kind, String> SECTIONS = Map.of(
        Kind.SHAPED, "minecraft:recipe_shaped",
        Kind.SHAPELESS, "minecraft:recipe_shapeless",
        Kind.FURNACE, "minecraft:recipe_furnace",
        Kind.SMITHING_TRANSFORM, "minecraft:recipe_smithing_transform",
        Kind.SMITHING_TRIM, "minecraft:recipe_smithing_trim");

    public static List<RecipeDefinition> readAll(BedrockPack pack) {
        List<RecipeDefinition> recipes = new ArrayList<>();
        for (Path file : pack.list("recipes", ".json")) {
            try {
                RecipeDefinition recipe = read(Json.parseObject(file));
                if (recipe != null) recipes.add(recipe);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return recipes;
    }

    private static RecipeDefinition read(JsonObject root) {
        // A recipe file names its kind as its only top-level key, so a kind nothing reads is a
        // recipe that never reaches the game and is worth saying so. Java builds its brewing from
        // its own code rather than from a data pack, and no Java block reduces a material.
        for (String declared : root.keySet()) {
            if (declared.startsWith("minecraft:recipe_") && !CONVERTED.contains(declared)) {
                AddonEntities.unsupported(declared);
            }
        }

        for (Kind kind : Kind.values()) {
            String section = SECTIONS.get(kind);
            if (!root.has(section) || !root.get(section).isJsonObject()) continue;

            JsonObject body = root.getAsJsonObject(section);
            JsonObject description = body.getAsJsonObject("description");
            String identifier = description == null ? null : Json.text(description, "identifier", null);
            if (identifier == null) return null;

            // Java decides what a recipe unlocks from an advancement, offers every match rather than
            // the one with the highest priority, and mirrors a shaped pattern whatever it holds.
            String name = section.substring("minecraft:recipe_".length());
            REPORTED.stream().filter(body::has)
                .forEach(field -> AddonEntities.unsupported("recipe field " + name + "." + field));

            return build(kind, identifier, body);
        }
        return null;
    }

    private static RecipeDefinition build(Kind kind, String identifier, JsonObject body) {
        List<String> pattern = new ArrayList<>();
        for (JsonElement row : Json.list(body, "pattern")) {
            if (row.isJsonPrimitive()) pattern.add(row.getAsString());
        }

        Map<String, String> key = new LinkedHashMap<>();
        JsonObject keys = body.getAsJsonObject("key");
        if (keys != null) {
            for (Map.Entry<String, JsonElement> entry : keys.entrySet()) {
                String item = itemOf(entry.getValue());
                if (item != null) key.put(entry.getKey(), item);
            }
        }

        List<Ingredient> ingredients = new ArrayList<>();
        for (JsonElement element : Json.list(body, "ingredients")) {
            String item = itemOf(element);
            if (item != null) ingredients.add(new Ingredient(item, count(element)));
        }
        String input = itemOf(body.get("input"));
        if (input != null) ingredients.add(new Ingredient(input, 1));

        JsonElement output = body.has("result") ? body.get("result") : body.get("output");
        if (output != null && output.isJsonArray() && output.getAsJsonArray().size() > 1) {
            // A Java recipe hands back one stack, so the rest of a Bedrock result list is dropped.
            AddonEntities.unsupported("recipe result list");
        }

        boolean smiths = kind == Kind.SMITHING_TRANSFORM || kind == Kind.SMITHING_TRIM;
        Smithing smithing = smiths
            ? new Smithing(itemOf(body.get("template")), itemOf(body.get("base")),
                itemOf(body.get("addition")))
            : null;
        if (smiths && (smithing.base() == null || smithing.template() == null)) return null;

        String result = itemOf(output);
        if (result == null && kind != Kind.SMITHING_TRIM) return null;

        List<String> tags = new ArrayList<>();
        for (JsonElement tag : Json.list(body, "tags")) {
            if (tag.isJsonPrimitive()) tags.add(tag.getAsString());
        }

        return new RecipeDefinition(identifier, kind, pattern, key, ingredients, result,
            count(output), Json.text(body, "group", null), tags, smithing);
    }

    /**
     * An item reference is a bare id, an object with `item` or `tag`, or a one entry list of either.
     *
     * A tag comes back carrying the `#` Java marks one with, which is what tells the two apart
     * everywhere a converted recipe writes an ingredient.
     */
    private static String itemOf(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonArray()) {
            return element.getAsJsonArray().isEmpty() ? null : itemOf(element.getAsJsonArray().get(0));
        }
        if (!element.isJsonObject()) return null;

        JsonObject body = element.getAsJsonObject();
        // A data value picks one of the blocks or items an id used to stand for, which Java stopped
        // having when it gave each of them an id of its own.
        if (body.has("data")) AddonEntities.unsupported("recipe item data value");

        String tag = Json.text(body, "tag", null);
        return tag != null ? "#" + tag : Json.text(body, "item", null);
    }

    private static int count(JsonElement element) {
        if (element == null) return 1;
        if (element.isJsonArray()) {
            return element.getAsJsonArray().isEmpty() ? 1 : count(element.getAsJsonArray().get(0));
        }
        if (!element.isJsonObject()) return 1;

        JsonObject body = element.getAsJsonObject();
        return body.has("count") && body.get("count").isJsonPrimitive() ? body.get("count").getAsInt() : 1;
    }
}
