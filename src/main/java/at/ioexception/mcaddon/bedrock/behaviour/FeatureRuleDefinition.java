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
import java.util.List;
import java.util.Map;

/**
 * Where a behaviour pack wants one of its features placed.
 *
 * A rule hangs exactly one feature on every biome whose tags its filter accepts, at a placement pass
 * and with a scatter of its own. Java writes the same three things as a placed feature plus a
 * decoration step, except for the direction of the attachment: a Java biome lists the features it
 * carries, so nothing a data pack writes reaches a biome from the other side. The list is turned
 * round instead, with the filter answered against each biome as the game builds it.
 */
public record FeatureRuleDefinition(String identifier, String placesFeature, String placementPass,
    BiomeMatch biomes, String world, JsonObject distribution) {

    /**
     * Which of Java's eleven decoration steps a Bedrock placement pass belongs in.
     *
     * Bedrock publishes no enum for the pass, so a name it does not carry is reported and the
     * feature is placed with the vegetation the surface pass is mostly used for.
     */
    private static final Map<String, String> PASSES = Map.ofEntries(
        Map.entry("pregeneration_pass", "RAW_GENERATION"),
        Map.entry("first_pass", "RAW_GENERATION"),
        Map.entry("before_surface_pass", "LOCAL_MODIFICATIONS"),
        Map.entry("surface_pass", "VEGETAL_DECORATION"),
        Map.entry("after_surface_pass", "TOP_LAYER_MODIFICATION"),
        Map.entry("before_underground_pass", "UNDERGROUND_STRUCTURES"),
        Map.entry("underground_pass", "UNDERGROUND_ORES"),
        Map.entry("after_underground_pass", "UNDERGROUND_DECORATION"),
        Map.entry("before_sky_pass", "FLUID_SPRINGS"),
        Map.entry("sky_pass", "VEGETAL_DECORATION"),
        Map.entry("after_sky_pass", "TOP_LAYER_MODIFICATION"),
        Map.entry("final_pass", "TOP_LAYER_MODIFICATION"));

    public static List<FeatureRuleDefinition> readAll(BedrockPack pack) {
        List<FeatureRuleDefinition> rules = new ArrayList<>();
        for (Path file : pack.list("feature_rules", ".json")) {
            try {
                FeatureRuleDefinition rule = read(Json.parseObject(file));
                if (rule != null) rules.add(rule);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return rules;
    }

    private static FeatureRuleDefinition read(JsonObject root) {
        JsonObject body = root.has("minecraft:feature_rules")
            && root.get("minecraft:feature_rules").isJsonObject()
            ? root.getAsJsonObject("minecraft:feature_rules") : null;
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        if (description == null) return null;

        String identifier = Json.text(description, "identifier", null);
        String places = Json.text(description, "places_feature", null);
        if (identifier == null || places == null) return null;

        JsonObject conditions = body.getAsJsonObject("conditions");
        JsonElement filter = conditions == null ? null : conditions.get("minecraft:biome_filter");
        String pass = conditions == null ? "surface_pass"
            : Json.text(conditions, "placement_pass", "surface_pass");

        return new FeatureRuleDefinition(identifier, places, pass, BiomeMatch.parse(filter),
            BiomeMatch.world(filter), body.getAsJsonObject("distribution"));
    }

    /** The name of the Java decoration step this rule's pass belongs in. */
    public String decorationStep() {
        String step = PASSES.get(placementPass);
        if (step != null) return step;

        AddonEntities.unsupported("feature rule placement_pass " + placementPass);
        return "VEGETAL_DECORATION";
    }

    /** How often and where inside a chunk the rule spreads what it places. */
    public Scatter scatter() {
        return Scatter.of(distribution);
    }
}
