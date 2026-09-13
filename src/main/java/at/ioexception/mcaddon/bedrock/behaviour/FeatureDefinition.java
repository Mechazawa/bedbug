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
import java.util.Set;

/**
 * A feature a behaviour pack declares: what gets placed, with no word about where.
 *
 * Bedrock splits worldgen the same way Java does, under other names. A feature says what to build
 * and a feature rule says where, which is Java's configured feature and its placed feature. The
 * split is not in the same place, though: six of Bedrock's twenty-eight feature types state a
 * position rather than a thing, and Java states each of those on the placed feature instead. Such a
 * type is read here as a wrapper, so `minecraft:scatter_feature` around an ore becomes one Java
 * configured feature with the scatter written into the placement beside it.
 *
 * Three more are carvers. Java keeps carvers in a registry of their own, outside the eleven
 * decoration steps a feature is placed in, and a data pack cannot add one to a dimension that is
 * already generating, so each is named against the pack. Six have nothing on any of the three.
 */
public record FeatureDefinition(String identifier, String type, JsonObject body) {

    /** The types that state a thing to build, which is what a Java configured feature holds. */
    public static final Set<String> CONFIGURED = Set.of(
        "minecraft:sequence_feature", "minecraft:fossil_feature", "minecraft:geode_feature",
        "minecraft:growing_plant_feature", "minecraft:multiface_feature", "minecraft:ore_feature",
        "minecraft:partially_exposed_blob_feature", "minecraft:sculk_patch_feature",
        "minecraft:single_block_feature", "minecraft:structure_template_feature",
        "minecraft:tree_feature", "minecraft:vegetation_patch_feature",
        "minecraft:weighted_random_feature");

    /** The types that state where, which Java states on a placed feature. */
    public static final Set<String> PLACEMENT = Set.of(
        "minecraft:scatter_feature", "minecraft:search_feature",
        "minecraft:snap_to_surface_feature", "minecraft:surface_relative_threshold_feature",
        "minecraft:height_difference_filter_feature", "minecraft:scan_surface");

    /** The types that carve rather than build. */
    public static final Set<String> CARVING = Set.of(
        "minecraft:cave_carver_feature", "minecraft:nether_cave_carver_feature",
        "minecraft:underwater_cave_carver_feature");

    /** The types with no Java configured feature, no placement and no carver behind them. */
    public static final Set<String> UNANSWERED = Set.of(
        "minecraft:aggregate_feature", "minecraft:conditional_list",
        "minecraft:beards_and_shavers", "minecraft:rect_layout", "minecraft:multi_block_feature",
        "minecraft:horizontal_tree_decoration_feature");

    /** Which field of a placement type names the feature it wraps. */
    private static final Map<String, String> WRAPPED = Map.of(
        "minecraft:scatter_feature", "places_feature",
        "minecraft:search_feature", "places_feature",
        "minecraft:snap_to_surface_feature", "feature_to_snap",
        "minecraft:surface_relative_threshold_feature", "feature_to_place",
        "minecraft:height_difference_filter_feature", "places_feature",
        "minecraft:scan_surface", "scan_surface_feature");

    public static List<FeatureDefinition> readAll(BedrockPack pack) {
        List<FeatureDefinition> features = new ArrayList<>();
        for (Path file : pack.list("features", ".json")) {
            try {
                FeatureDefinition feature = read(Json.parseObject(file));
                if (feature != null) features.add(feature);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return features;
    }

    /**
     * The one feature in a file.
     *
     * Bedrock allows exactly one feature per file and names the type with the only key beside
     * `format_version`, so the type is read off that key rather than out of a field.
     */
    private static FeatureDefinition read(JsonObject root) {
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!entry.getKey().startsWith("minecraft:") || !entry.getValue().isJsonObject()) continue;

            JsonObject body = entry.getValue().getAsJsonObject();
            JsonObject description = body.getAsJsonObject("description");
            String identifier = description == null ? null : Json.text(description, "identifier", null);
            if (identifier != null) return new FeatureDefinition(identifier, entry.getKey(), body);
        }
        return null;
    }

    /** How often and where inside a chunk this feature spreads what it places. */
    public Scatter scatter() {
        JsonElement declared = body.get("distribution");
        return Scatter.of(declared != null && declared.isJsonObject()
            ? declared.getAsJsonObject() : null);
    }

    /** The feature this one wraps, for a type that states a position around another feature. */
    public String wraps() {
        String field = WRAPPED.get(type);
        return field == null ? null : Json.text(body, field, null);
    }

    /** Whether anything in Java can be built out of this type. */
    public boolean answerable() {
        return CONFIGURED.contains(type) || PLACEMENT.contains(type);
    }

    /** Names the type against the pack when Java has nothing to build it out of. */
    public void report() {
        if (CARVING.contains(type)) {
            // A Java carver belongs to a registry of its own rather than to any of the eleven
            // decoration steps, and the biomes it runs in are fixed when the dimension is made.
            AddonEntities.unsupported("feature " + type + " (a Java carver, not a feature)");
        } else if (!answerable()) {
            AddonEntities.unsupported("feature " + type);
        }
    }
}
