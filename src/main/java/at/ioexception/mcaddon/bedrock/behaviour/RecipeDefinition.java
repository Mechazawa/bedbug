package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.convert.BedrockItemTags;
import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.BedrockItemNames;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
    String group, List<String> tags, Smithing smithing, List<String> unlock, boolean alwaysUnlocked,
    int priority, Brewing brewing) {

    public enum Kind {
        SHAPED, SHAPELESS, FURNACE, SMITHING_TRANSFORM, SMITHING_TRIM,
        BREWING_MIX, BREWING_CONTAINER;

        public boolean brews() {
            return this == BREWING_MIX || this == BREWING_CONTAINER;
        }
    }

    public record Ingredient(String item, int count) {
    }

    /** A smithing table's three inputs: what shapes the result, what is changed, and what changes it. */
    public record Smithing(String template, String base, String addition) {
    }

    /**
     * A brewing stand's three parts: what stands in the bottles, what goes on top, and what comes out.
     *
     * A mix names a potion on each side, written `minecraft:potion_type:<name>`, and a container
     * names the bottle itself, so `minecraft:potion` with gunpowder becomes `minecraft:splash_potion`.
     */
    public record Brewing(String input, String reagent, String output) {
    }

    /** The recipe kinds this mod converts, against which a pack's own file is checked. */
    private static final Set<String> CONVERTED = Set.of(
        "minecraft:recipe_shaped", "minecraft:recipe_shapeless", "minecraft:recipe_furnace",
        "minecraft:recipe_smithing_transform", "minecraft:recipe_smithing_trim",
        "minecraft:recipe_brewing_mix", "minecraft:recipe_brewing_container");

    /**
     * Every field of a recipe body this reads, qualified by the kind carrying it.
     *
     * A field Java has no place for counts here once it is read, because reading it is what lets
     * `/mcaddon entities` name it: `assume_symmetry`, an unlock stated as a context other than
     * `AlwaysUnlocked` and every data value beside an item are each reported rather than converted.
     */
    private static final Set<String> HANDLES = Set.of(
        "shaped.description", "shaped.description.identifier", "shaped.pattern", "shaped.key",
        "shaped.key.item", "shaped.key.tag", "shaped.key.data", "shaped.result",
        "shaped.result.item", "shaped.result.count", "shaped.result.data", "shaped.group",
        "shaped.tags", "shaped.priority", "shaped.assume_symmetry", "shaped.unlock",
        "shaped.unlock.item", "shaped.unlock.tag", "shaped.unlock.data", "shaped.unlock.context",
        "shapeless.description", "shapeless.description.identifier", "shapeless.ingredients",
        "shapeless.ingredients.item", "shapeless.ingredients.tag", "shapeless.ingredients.count",
        "shapeless.ingredients.data", "shapeless.result", "shapeless.result.item",
        "shapeless.result.count", "shapeless.result.data", "shapeless.group", "shapeless.tags",
        "shapeless.priority", "shapeless.unlock", "shapeless.unlock.item", "shapeless.unlock.tag",
        "shapeless.unlock.data", "shapeless.unlock.context",
        "furnace.description", "furnace.description.identifier", "furnace.input",
        "furnace.input.item", "furnace.input.tag", "furnace.input.data", "furnace.output",
        "furnace.tags", "furnace.priority", "furnace.unlock", "furnace.unlock.item",
        "furnace.unlock.tag", "furnace.unlock.data",
        "smithing_transform.description", "smithing_transform.description.identifier",
        "smithing_transform.template", "smithing_transform.base", "smithing_transform.addition",
        "smithing_transform.result", "smithing_transform.tags",
        "smithing_trim.description", "smithing_trim.description.identifier",
        "smithing_trim.template", "smithing_trim.template.tag", "smithing_trim.base",
        "smithing_trim.base.tag", "smithing_trim.addition", "smithing_trim.addition.tag",
        "smithing_trim.tags",
        "brewing_mix.description", "brewing_mix.description.identifier", "brewing_mix.input",
        "brewing_mix.reagent", "brewing_mix.output", "brewing_mix.tags",
        "brewing_container.description", "brewing_container.description.identifier",
        "brewing_container.input", "brewing_container.reagent", "brewing_container.output",
        "brewing_container.tags");

    /** The fields Java's own recipe has no place for, each reported once a pack writes one. */
    private static final List<String> REPORTED = List.of("assume_symmetry");

    private static final String ALWAYS_UNLOCKED = "AlwaysUnlocked";

    private static final Map<Kind, String> SECTIONS = Map.of(
        Kind.SHAPED, "minecraft:recipe_shaped",
        Kind.SHAPELESS, "minecraft:recipe_shapeless",
        Kind.FURNACE, "minecraft:recipe_furnace",
        Kind.SMITHING_TRANSFORM, "minecraft:recipe_smithing_transform",
        Kind.SMITHING_TRIM, "minecraft:recipe_smithing_trim",
        Kind.BREWING_MIX, "minecraft:recipe_brewing_mix",
        Kind.BREWING_CONTAINER, "minecraft:recipe_brewing_container");

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
        // recipe that never reaches the game and is worth saying so. No Java block reduces a
        // material, which is what `minecraft:recipe_material_reduction` describes.
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

            // Java mirrors a shaped pattern whatever the recipe says about its symmetry.
            String name = section.substring("minecraft:recipe_".length());
            REPORTED.stream().filter(body::has)
                .forEach(field -> AddonEntities.unsupported("recipe field " + name + "." + field));

            return build(kind, identifier, body);
        }
        return null;
    }

    private static RecipeDefinition build(Kind kind, String identifier, JsonObject body) {
        List<String> tags = new ArrayList<>();
        for (JsonElement tag : Json.list(body, "tags")) {
            if (tag.isJsonPrimitive()) tags.add(tag.getAsString());
        }
        if (kind.brews()) {
            return new RecipeDefinition(identifier, kind, List.of(), Map.of(), List.of(), null, 1,
                null, tags, null, List.of(), false, 0, new Brewing(Json.text(body, "input", null),
                    Json.text(body, "reagent", null), Json.text(body, "output", null)));
        }

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

        RecipeDefinition recipe = new RecipeDefinition(identifier, kind, pattern, key, ingredients, result,
            count(output), Json.text(body, "group", null), tags, smithing, unlock(body),
            alwaysUnlocked(body), (int) Json.number(body, "priority", 0), null);
        return recipe.complete() ? recipe : null;
    }

    /**
     * Whether every item the recipe names has a Java item behind it.
     *
     * Java's recipe parser refuses a recipe naming an item it does not have and logs it as an
     * error, so a recipe with one such reference is left out once the reference is reported.
     */
    private boolean complete() {
        List<String> named = new ArrayList<>(key.values());
        ingredients.forEach(ingredient -> named.add(ingredient.item()));
        named.add(result);
        if (smithing != null) {
            named.addAll(Arrays.asList(smithing.template(), smithing.base(), smithing.addition()));
        }
        return !named.contains(MISSING);
    }

    /**
     * What a player has to have held before the recipe is theirs, as item and tag references.
     *
     * Java keeps the same idea in an advancement rewarding the recipe, which is what a converted
     * one is written alongside. A context names a Bedrock game event instead of a thing to hold, and
     * an advancement trigger waits on none of them, so a context is reported and the list is empty,
     * except `AlwaysUnlocked`, which {@link #alwaysUnlocked} answers.
     */
    private static List<String> unlock(JsonObject body) {
        List<String> items = new ArrayList<>();
        for (JsonElement element : Json.list(body, "unlock")) {
            if (!element.isJsonObject()) continue;

            JsonObject entry = element.getAsJsonObject();
            String context = Json.text(entry, "context", null);
            if (context != null) {
                if (context.equals(ALWAYS_UNLOCKED)) continue;

                AddonEntities.unsupported("recipe unlock context " + context);
                continue;
            }

            String item = itemOf(entry);
            if (item != null && !item.equals(MISSING)) items.add(item);
        }
        return items;
    }

    /** Whether an `unlock` entry names the context that gives the recipe to every player. */
    private static boolean alwaysUnlocked(JsonObject body) {
        return Json.list(body, "unlock").stream().anyMatch(element -> element.isJsonObject()
            && ALWAYS_UNLOCKED.equals(Json.text(element.getAsJsonObject(), "context", null)));
    }

    /**
     * An item reference is a bare id, an object with `item` or `tag`, or a one entry list of either.
     *
     * A tag comes back carrying the `#` Java marks one with, which is what tells the two apart
     * everywhere a converted recipe writes an ingredient.
     */
    private static String itemOf(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) return javaItem(element.getAsString(), 0);
        if (element.isJsonArray()) {
            return element.getAsJsonArray().isEmpty() ? null : itemOf(element.getAsJsonArray().get(0));
        }
        if (!element.isJsonObject()) return null;

        JsonObject body = element.getAsJsonObject();
        String tag = Json.text(body, "tag", null);
        if (tag != null) {
            String java = BedrockItemTags.javaName(tag);
            if (java == null) AddonEntities.unsupported("recipe item tag " + tag);
            return java == null ? MISSING : "#" + java;
        }

        String item = Json.text(body, "item", null);
        return item == null ? null : javaItem(item, (int) Json.number(body, "data", 0));
    }

    /** What an item reference that names an item Java has nothing for reads as. */
    private static final String MISSING = "";

    /**
     * The Java item a recipe names, with the data value beside it picking a family member.
     *
     * A data value picks one of the blocks or items an id used to stand for, which Java stopped
     * having when it gave each of them an id of its own, so `wool` beside a fourteen is red wool.
     * Bedrock's own wildcard, and a value on an id Java never split, pick a variant Java keeps
     * inside the stack or not at all, which a recipe cannot ask for, so each is reported.
     */
    private static String javaItem(String bedrock, int data) {
        boolean any = data == WILDCARD || data < 0;
        if (data != 0 && (any || !DataValues.split(bedrock))) {
            AddonEntities.unsupported("recipe item data value");
        }

        String java = BedrockItemNames.java(bedrock, any && !DataValues.split(bedrock) ? 0 : data);
        if (java != null) return java;

        AddonEntities.unsupported("recipe item " + bedrock + (data == 0 ? "" : " " + data));
        return MISSING;
    }

    /** The data value Bedrock's older recipes wrote to mean any of them. */
    private static final int WILDCARD = Short.MAX_VALUE;

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
