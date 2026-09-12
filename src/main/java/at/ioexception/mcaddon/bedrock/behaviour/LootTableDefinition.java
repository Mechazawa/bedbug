package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A Bedrock loot table, rewritten as a Java one.
 *
 * The two formats share their shape: pools with rolls and weighted entries. What differs is naming,
 * so the conversion is a rename of a handful of fields rather than a translation.
 *
 * @param path the pack-relative path, which is how an entity's `minecraft:loot` names it
 */
public record LootTableDefinition(String path, JsonObject java) {

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

        JsonArray entries = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "entries")) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = entry(element.getAsJsonObject());
            if (entry != null) entries.add(entry);
        }
        pool.add("entries", entries);
        return pool;
    }

    private static JsonObject entry(JsonObject bedrock) {
        String type = Json.text(bedrock, "type", "item");
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
            default -> entry.addProperty("type", "minecraft:empty");
        }

        if (bedrock.has("weight")) entry.add("weight", bedrock.get("weight"));

        JsonArray functions = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "functions")) {
            if (!element.isJsonObject()) continue;
            JsonObject function = function(element.getAsJsonObject());
            if (function != null) functions.add(function);
        }
        if (!functions.isEmpty()) entry.add("functions", functions);
        return entry;
    }

    /** Only the functions whose Java equivalent takes the same arguments are carried across. */
    private static JsonObject function(JsonObject bedrock) {
        String name = Json.text(bedrock, "function", "");
        JsonObject function = new JsonObject();

        switch (name) {
            case "set_count" -> {
                function.addProperty("function", "minecraft:set_count");
                function.add("count", range(bedrock.get("count")));
            }
            case "set_damage" -> {
                function.addProperty("function", "minecraft:set_damage");
                function.add("damage", range(bedrock.get("damage")));
            }
            case "enchant_randomly" -> function.addProperty("function", "minecraft:enchant_randomly");
            case "furnace_smelt" -> function.addProperty("function", "minecraft:furnace_smelt");
            default -> {
                return null;
            }
        }
        return function;
    }

    /** A count is a number or a `{min, max}` pair, which Java writes as a uniform distribution. */
    private static JsonElement range(JsonElement bedrock) {
        if (bedrock == null) return new com.google.gson.JsonPrimitive(1);
        if (bedrock.isJsonPrimitive()) return bedrock;
        if (!bedrock.isJsonObject()) return new com.google.gson.JsonPrimitive(1);

        JsonObject body = bedrock.getAsJsonObject();
        JsonObject uniform = new JsonObject();
        uniform.addProperty("type", "minecraft:uniform");
        uniform.add("min", body.has("min") ? body.get("min") : new com.google.gson.JsonPrimitive(1));
        uniform.add("max", body.has("max") ? body.get("max") : new com.google.gson.JsonPrimitive(1));
        return uniform;
    }
}
