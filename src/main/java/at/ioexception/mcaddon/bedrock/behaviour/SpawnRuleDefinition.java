package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * How a behaviour pack says its entity spawns naturally.
 *
 * Bedrock describes a spawn as a list of condition blocks, each holding components for weight, herd
 * size, brightness and biome. Java takes a weight and a group size per biome, so what carries across
 * is the numbers and which of the three worlds the biome filter points at.
 *
 * @param world one of `overworld`, `nether` or `end`, from the biome filter
 */
public record SpawnRuleDefinition(String identifier, String category, String world,
    int weight, int minimumHerd, int maximumHerd) {

    public static List<SpawnRuleDefinition> readAll(BedrockPack pack) {
        List<SpawnRuleDefinition> rules = new ArrayList<>();
        for (Path file : pack.list("spawn_rules", ".json")) {
            try {
                SpawnRuleDefinition rule = read(Json.parseObject(file));
                if (rule != null) rules.add(rule);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return rules;
    }

    private static SpawnRuleDefinition read(JsonObject root) {
        JsonObject body = root.has("minecraft:spawn_rules") && root.get("minecraft:spawn_rules").isJsonObject()
            ? root.getAsJsonObject("minecraft:spawn_rules") : null;
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        int weight = 10;
        int minimum = 1;
        int maximum = 4;
        String world = "overworld";

        for (JsonElement element : Json.list(body, "conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = element.getAsJsonObject();

            JsonObject herd = condition.getAsJsonObject("minecraft:herd");
            if (herd != null) {
                minimum = herd.has("min_size") ? herd.get("min_size").getAsInt() : minimum;
                maximum = herd.has("max_size") ? herd.get("max_size").getAsInt() : maximum;
            }

            JsonElement weightValue = condition.get("minecraft:weight");
            if (weightValue != null && weightValue.isJsonObject()) {
                JsonObject wrapped = weightValue.getAsJsonObject();
                if (wrapped.has("default")) weight = wrapped.get("default").getAsInt();
            }

            if (condition.has("minecraft:spawns_underground")) world = "overworld";
            if (condition.has("minecraft:spawns_lava")) world = "nether";

            JsonObject biome = condition.getAsJsonObject("minecraft:biome_filter");
            String tag = biome == null ? null : Json.text(biome, "value", null);
            if (tag != null && tag.contains("nether")) world = "nether";
            if (tag != null && tag.contains("end")) world = "end";
        }

        return new SpawnRuleDefinition(identifier,
            Json.text(description, "population_control", "animal"),
            world, weight, Math.max(1, minimum), Math.max(1, maximum));
    }
}
