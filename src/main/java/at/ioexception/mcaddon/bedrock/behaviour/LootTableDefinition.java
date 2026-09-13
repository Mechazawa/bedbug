package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Bedrock loot table, rewritten as a Java one.
 *
 * The two formats share their shape: pools with rolls and weighted entries. What differs is naming,
 * so the conversion is a rename of a handful of fields rather than a translation.
 *
 * @param path the pack-relative path, which is how an entity's `minecraft:loot` names it
 */
public record LootTableDefinition(String path, JsonObject java) {

    /** The Java structure tag each Bedrock explorer map destination points at, named without a `#`. */
    private static final Map<String, String> MAP_DESTINATIONS = Map.ofEntries(
        Map.entry("buriedtreasure", "minecraft:on_treasure_maps"),
        Map.entry("mansion", "minecraft:on_woodland_explorer_maps"),
        Map.entry("monument", "minecraft:on_ocean_explorer_maps"),
        Map.entry("jungle_temple", "minecraft:on_jungle_explorer_maps"),
        Map.entry("swamp_hut", "minecraft:on_swamp_explorer_maps"),
        Map.entry("trial_chambers", "minecraft:on_trial_chambers_maps"),
        Map.entry("village_desert", "minecraft:on_desert_village_maps"),
        Map.entry("village_plains", "minecraft:on_plains_village_maps"),
        Map.entry("village_savanna", "minecraft:on_savanna_village_maps"),
        Map.entry("village_snowy", "minecraft:on_snowy_village_maps"),
        Map.entry("village_taiga", "minecraft:on_taiga_village_maps"));

    /** The three destinations every supported version ships a structure tag for. */
    private static final List<String> ALWAYS_MAPPED = List.of("buriedtreasure", "mansion", "monument");

    /**
     * The path inside a data pack that a Bedrock table path is written to.
     *
     * An entity's `minecraft:loot` names the file by the same pack-relative string the table itself
     * came from, so the writer and the entity reading it back have to agree on one rule.
     */
    public static String javaPath(String table) {
        String inside = table.startsWith("loot_tables/")
            ? table.substring("loot_tables/".length()) : table;
        int dot = inside.lastIndexOf('.');
        return at.ioexception.mcaddon.convert.Names.sanitise(dot < 0 ? inside : inside.substring(0, dot));
    }

    public static List<LootTableDefinition> readAll(BedrockPack pack) {
        List<LootTableDefinition> tables = new ArrayList<>();
        for (Path file : pack.list("loot_tables", ".json")) {
            try {
                String relative = pack.root().relativize(file).toString().replace('\\', '/');
                tables.add(new LootTableDefinition(relative, convert(Json.parseObject(file))));
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return tables;
    }

    private static JsonObject convert(JsonObject bedrock) {
        JsonObject java = new JsonObject();
        java.addProperty("type", "minecraft:entity");

        JsonArray pools = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "pools")) {
            if (element.isJsonObject()) pools.add(pool(element.getAsJsonObject()));
        }
        java.add("pools", pools);
        return java;
    }

    private static JsonObject pool(JsonObject bedrock) {
        JsonObject pool = new JsonObject();
        pool.add("rolls", range(bedrock.get("rolls")));
        if (bedrock.has("bonus_rolls")) pool.add("bonus_rolls", range(bedrock.get("bonus_rolls")));

        JsonArray entries = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "entries")) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = entry(element.getAsJsonObject());
            if (entry != null) entries.add(entry);
        }
        pool.add("entries", entries);

        JsonArray conditions = conditions(bedrock);
        if (!conditions.isEmpty()) pool.add("conditions", conditions);

        // A tier picks entries by an item's own rarity, which a Java pool has no field for.
        if (bedrock.has("tiers")) AddonEntities.unsupported("loot pool tiers");
        return pool;
    }

    private static JsonObject entry(JsonObject bedrock) {
        String type = bare(Json.text(bedrock, "type", "item"));
        String name = Json.text(bedrock, "name", null);

        JsonObject entry = new JsonObject();
        switch (type) {
            case "item" -> {
                if (name == null) return null;
                entry.addProperty("type", "minecraft:item");
                entry.addProperty("name", name);
            }
            case "loot_table" -> {
                if (name == null) return null;
                entry.addProperty("type", "minecraft:loot_table");
                entry.addProperty("value", name);
            }
            case "empty" -> entry.addProperty("type", "minecraft:empty");
            default -> {
                AddonEntities.unsupported("loot entry " + type);
                entry.addProperty("type", "minecraft:empty");
            }
        }

        if (bedrock.has("weight")) entry.add("weight", bedrock.get("weight"));
        if (bedrock.has("quality")) entry.add("quality", bedrock.get("quality"));

        JsonArray functions = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "functions")) {
            if (!element.isJsonObject()) continue;
            JsonObject function = function(element.getAsJsonObject());
            if (function != null) functions.add(function);
        }
        if (!functions.isEmpty()) entry.add("functions", functions);

        JsonArray conditions = conditions(bedrock);
        if (!conditions.isEmpty()) entry.add("conditions", conditions);
        return entry;
    }

    /** Only the functions whose Java equivalent takes the same arguments are carried across. */
    private static JsonObject function(JsonObject bedrock) {
        String name = bare(Json.text(bedrock, "function", ""));
        JsonObject function = new JsonObject();

        switch (name) {
            case "set_count" -> {
                function.addProperty("function", "minecraft:set_count");
                function.add("count", range(bedrock.get("count")));
                if (bedrock.has("add")) function.add("add", bedrock.get("add"));
            }
            case "set_damage" -> {
                function.addProperty("function", "minecraft:set_damage");
                function.add("damage", range(bedrock.get("damage")));
                if (bedrock.has("add")) function.add("add", bedrock.get("add"));
            }
            case "enchant_randomly" -> function.addProperty("function", "minecraft:enchant_randomly");
            case "furnace_smelt" -> function.addProperty("function", "minecraft:furnace_smelt");
            case "looting_enchant" ->
                Platform.lootingCount(function, range(bedrock.get("count")), bedrock.get("limit"));
            case "enchant_with_levels" -> {
                function.addProperty("function", "minecraft:enchant_with_levels");
                function.add("levels", range(bedrock.get("levels")));
                Platform.enchantmentChoice(function, Json.bool(bedrock, "treasure", false));
            }
            case "set_potion" -> {
                function.addProperty("function", "minecraft:set_potion");
                function.addProperty("id", namespaced(Json.text(bedrock, "id", "water")));
            }
            case "specific_enchants" -> {
                function.addProperty("function", "minecraft:set_enchantments");
                function.add("enchantments", enchantments(bedrock));
            }
            case "exploration_map" -> {
                String destination = Json.text(bedrock, "destination", "buriedtreasure");
                String tag = MAP_DESTINATIONS.get(destination);
                if (tag == null || (!ALWAYS_MAPPED.contains(destination) && !Platform.everyExplorerMapTag())) {
                    AddonEntities.unsupported("loot function exploration_map " + destination);
                    return null;
                }
                function.addProperty("function", "minecraft:exploration_map");
                function.addProperty("destination", tag);
            }
            case "set_ominous_bottle_amplifier" -> {
                if (!Platform.hasOminousBottle()) {
                    AddonEntities.unsupported("loot function " + name);
                    return null;
                }
                function.addProperty("function", "minecraft:set_ominous_bottle_amplifier");
                function.add("amplifier", range(bedrock.get("amplifier")));
            }
            // set_data, random_aux_value, set_data_from_color_index and random_block_state each pick
            // an item's data value, which Java has not had since blocks and items were flattened.
            // enchant_random_gear enchants the mob's own equipment as it spawns, which a table
            // describing its drops cannot reach, and enchant_book_for_trading prices a book against
            // a villager's level, which no Java loot function does. set_armor_trim, set_banner_details
            // and set_stew_effect name their result the Bedrock way and have no reader here yet.
            default -> {
                AddonEntities.unsupported("loot function " + name);
                return null;
            }
        }
        return function;
    }

    private static JsonArray conditions(JsonObject bedrock) {
        JsonArray conditions = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = condition(element.getAsJsonObject());
            if (condition != null) conditions.add(condition);
        }
        return conditions;
    }

    /** Only the conditions a Java predicate can ask the same question of are carried across. */
    private static JsonObject condition(JsonObject bedrock) {
        String name = bare(Json.text(bedrock, "condition", ""));
        JsonObject condition = new JsonObject();

        switch (name) {
            case "random_chance" -> {
                condition.addProperty("condition", "minecraft:random_chance");
                condition.add("chance", bedrock.has("chance")
                    ? bedrock.get("chance") : new JsonPrimitive(1));
            }
            case "killed_by_player" -> condition.addProperty("condition", "minecraft:killed_by_player");
            case "random_chance_with_looting" ->
                Platform.lootingChance(condition, Json.number(bedrock, "chance", 0),
                    Json.number(bedrock, "looting_multiplier", 0));
            case "is_baby" -> {
                JsonObject flags = new JsonObject();
                flags.addProperty("is_baby", true);
                JsonObject predicate = new JsonObject();
                predicate.add(Platform.entityPredicateKey("flags"), flags);
                thisEntity(condition, predicate);
            }
            case "passenger_of_entity" -> {
                JsonObject predicate = new JsonObject();
                predicate.add(Platform.entityPredicateKey("vehicle"),
                    entityType(Json.text(bedrock, "entity_type", null)));
                thisEntity(condition, predicate);
            }
            case "killed_by_entity", "damaged_by_entity" -> {
                JsonObject predicate = new JsonObject();
                predicate.add("source_entity", entityType(Json.text(bedrock, "entity_type", null)));
                condition.addProperty("condition", "minecraft:damage_source_properties");
                condition.add("predicate", predicate);
            }
            // entity_properties carries a Bedrock filter, whose vocabulary a Java entity predicate
            // does not share. has_variant, has_mark_variant and bool_property read Bedrock entity
            // data and pack-declared properties, which no Java predicate names. entity_killed asks
            // what the dropping mob itself killed, and a Java loot context knows only the victim and
            // the killer. killed_by_player_or_pets widens that killer to anything it tamed, which
            // Java's predicate cannot say. The two difficulty chances read the world difficulty,
            // which no Java loot condition can. biome_has_tag names a Bedrock biome tag.
            default -> {
                AddonEntities.unsupported("loot condition " + name);
                return null;
            }
        }
        return condition;
    }

    private static void thisEntity(JsonObject condition, JsonObject predicate) {
        condition.addProperty("condition", "minecraft:entity_properties");
        condition.addProperty("entity", "this");
        condition.add("predicate", predicate);
    }

    private static JsonObject entityType(String type) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty(Platform.entityPredicateKey("entity_type"),
            type == null ? "minecraft:pig" : type);
        return predicate;
    }

    /** `specific_enchants` names each enchantment and the level, or the level range, it is given. */
    private static JsonObject enchantments(JsonObject bedrock) {
        JsonObject enchantments = new JsonObject();
        for (JsonElement element : Json.list(bedrock, "enchants")) {
            if (!element.isJsonObject()) continue;
            JsonObject enchant = element.getAsJsonObject();
            String id = Json.text(enchant, "id", null);
            if (id != null) enchantments.add(namespaced(id), range(enchant.get("level")));
        }
        return enchantments;
    }

    private static String namespaced(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** A name a pack may write with or without the vanilla namespace, which means the same thing. */
    private static String bare(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /**
     * A count is a number, a `{min, max}` pair or a `[min, max]` pair.
     *
     * Java writes the two pair forms as a uniform distribution between the ends.
     */
    private static JsonElement range(JsonElement bedrock) {
        if (bedrock == null) return new JsonPrimitive(1);
        if (bedrock.isJsonPrimitive()) return bedrock;

        JsonElement low = new JsonPrimitive(1);
        JsonElement high = new JsonPrimitive(1);
        if (bedrock.isJsonArray() && bedrock.getAsJsonArray().size() == 2) {
            low = bedrock.getAsJsonArray().get(0);
            high = bedrock.getAsJsonArray().get(1);
        } else if (bedrock.isJsonObject()) {
            JsonObject body = bedrock.getAsJsonObject();
            if (body.has("min")) low = body.get("min");
            if (body.has("max")) high = body.get("max");
        } else {
            return new JsonPrimitive(1);
        }

        JsonObject uniform = new JsonObject();
        uniform.addProperty("type", "minecraft:uniform");
        uniform.add("min", low);
        uniform.add("max", high);
        return uniform;
    }
}
