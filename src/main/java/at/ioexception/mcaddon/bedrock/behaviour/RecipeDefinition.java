package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
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
 * A crafting recipe a behaviour pack declares.
 *
 * Bedrock's shaped recipes use a key map and a pattern the same way Java's do, which is what makes
 * these two of the few formats that convert almost directly.
 */
public record RecipeDefinition(String identifier, Kind kind, List<String> pattern,
    Map<String, String> key, List<Ingredient> ingredients, String result, int resultCount) {

    public enum Kind { SHAPED, SHAPELESS, FURNACE }

    public record Ingredient(String item, int count) {
    }

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
        for (Kind kind : Kind.values()) {
            String section = switch (kind) {
                case SHAPED -> "minecraft:recipe_shaped";
                case SHAPELESS -> "minecraft:recipe_shapeless";
                case FURNACE -> "minecraft:recipe_furnace";
            };
            if (!root.has(section) || !root.get(section).isJsonObject()) continue;

            JsonObject body = root.getAsJsonObject(section);
            JsonObject description = body.getAsJsonObject("description");
            String identifier = description == null ? null : Json.text(description, "identifier", null);
            if (identifier == null) return null;

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
            String result = itemOf(output);
            if (result == null) return null;

            return new RecipeDefinition(identifier, kind, pattern, key, ingredients, result, count(output));
        }
        return null;
    }

    /** An item reference is a bare id, an object with `item`, or a one entry list of either. */
    private static String itemOf(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonArray()) {
            return element.getAsJsonArray().isEmpty() ? null : itemOf(element.getAsJsonArray().get(0));
        }
        return element.isJsonObject() ? Json.text(element.getAsJsonObject(), "item", null) : null;
    }

    private static int count(JsonElement element) {
        if (element == null || !element.isJsonObject()) return 1;
        JsonObject body = element.getAsJsonObject();
        return body.has("count") && body.get("count").isJsonPrimitive() ? body.get("count").getAsInt() : 1;
    }
}
