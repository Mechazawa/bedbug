package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.bedrock.ClientBiomeDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.BiomeDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.DimensionDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.FeatureDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.FeatureRuleDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.Scatter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The worldgen half of a behaviour pack, as the data pack registries Java reads it from.
 *
 * Three directories become three registries. A `biomes/` file becomes a `worldgen/biome` entry, a
 * `features/` file becomes a `worldgen/configured_feature` entry with a bare `worldgen/placed_feature`
 * beside it so another feature can name it, and a `feature_rules/` file becomes a second placed
 * feature carrying the scatter the rule asked for. Which biomes that last one reaches is decided at
 * world load rather than in a file, because a Java biome lists its own features and nothing a data
 * pack writes reaches one from the other side.
 *
 * Where each biome generates is decided in `AddonWorldgen` rather than here, because a Java biome
 * entry says nothing about where the game looks for it.
 */
public final class Worldgen {
    /** Java's eleven decoration steps, which a biome lists one feature list per. */
    private static final int DECORATION_STEPS = 11;

    /** How deep a chain of features stating a position is followed before it is called a loop. */
    private static final int WRAPPER_LIMIT = 8;

    private Worldgen() {
    }

    /** Writes everything the pack's worldgen becomes. */
    public static void write(Path root, String namespace, List<BiomeDefinition> biomes,
        Map<String, ClientBiomeDefinition> colours, List<FeatureDefinition> features,
        List<FeatureRuleDefinition> rules, List<DimensionDefinition> dimensions) throws IOException {
        // No biome is converted on 1.20.1, so the colours and the fog a resource pack paints one
        // with have nothing to land on either.
        if (!Platform.convertsWorldgen()) {
            colours.keySet().forEach(painted -> AddonEntities
                .unsupported("resource pack biome colours (needs 1.21 or newer)"));
            dimensions.forEach(dimension -> AddonEntities
                .unsupported("behaviour pack dimensions (needs 1.21 or newer)"));
            return;
        }

        for (DimensionDefinition dimension : dimensions) {
            if (!dimension.convertible()) continue;
            if (!Platform.convertsDimensions()) {
                AddonEntities.unsupported("behaviour pack dimensions (needs 26 or newer)");
                continue;
            }

            Names.Identifier id = Names.split(dimension.identifier(), namespace);
            write(path(root, id, "dimension_type"),
                Platform.dimensionType(dimension.minimumY(), dimension.height()));
            write(path(root, id, "dimension"),
                voidDimension(id.namespace() + ":" + id.path(), dimension.biome()));
        }

        for (BiomeDefinition biome : biomes) {
            Names.Identifier id = Names.split(biome.identifier(), namespace);
            write(path(root, id, "worldgen/biome"),
                biome(biome, colours.getOrDefault(biome.identifier(), ClientBiomeDefinition.NONE)));
        }

        Map<String, FeatureDefinition> byIdentifier = new LinkedHashMap<>();
        features.forEach(feature -> byIdentifier.put(feature.identifier(), feature));

        Set<String> built = new LinkedHashSet<>();
        for (FeatureDefinition feature : features) {
            JsonObject configured = configured(feature, namespace);
            if (configured == null) continue;

            Names.Identifier id = Names.split(feature.identifier(), namespace);
            write(path(root, id, "worldgen/configured_feature"), configured);
            write(path(root, id, "worldgen/placed_feature"),
                placed(id.namespace() + ":" + id.path(), new JsonArray()));
            built.add(feature.identifier());
        }

        for (FeatureRuleDefinition rule : rules) {
            JsonObject placement = rulePlacement(rule, byIdentifier, built, namespace);
            if (placement == null) continue;

            write(path(root, Names.split(rule.identifier(), namespace), "worldgen/placed_feature"),
                placement);
        }
    }

    /**
     * One biome, with everything Java requires and a Bedrock biome never states left empty.
     *
     * The carvers, the features and the spawners are the three of those. A pack fills the second by
     * writing feature rules, the third by writing spawn rules, and Java's own carvers belong to a
     * dimension rather than to anything a pack can add to.
     */
    private static JsonObject biome(BiomeDefinition biome, ClientBiomeDefinition colours) {
        JsonObject json = new JsonObject();
        json.addProperty("has_precipitation", biome.hasPrecipitation());
        json.addProperty("temperature", biome.temperature());
        if (biome.partiallyFrozen()) json.addProperty("temperature_modifier", "frozen");
        json.addProperty("downfall", biome.downfall());

        Platform.biomeEffects(json, colours.tinted(biome.grassColour(), biome.foliageColour()));

        Platform.emptyCarvers(json);
        JsonArray steps = new JsonArray();
        for (int step = 0; step < DECORATION_STEPS; step++) steps.add(new JsonArray());
        json.add("features", steps);
        json.add("spawners", new JsonObject());
        json.add("spawn_costs", new JsonObject());
        json.addProperty("creature_spawn_probability", biome.creatureSpawnProbability());
        return json;
    }

    /**
     * The sky colour Java paints a biome of this temperature with.
     *
     * A Bedrock behaviour pack states no sky colour at all and only some of its resource packs do,
     * so a converted biome without one takes the colour the game computes for every biome it ships
     * rather than a flat blue that would stand out beside them.
     */
    public static int skyColour(float temperature) {
        float scaled = Math.clamp(temperature / 3f, -1f, 1f);
        return hsvToRgb(0.62222224f - scaled * 0.05f, 0.5f + scaled * 0.1f);
    }

    private static int hsvToRgb(float hue, float saturation) {
        int sextant = (int) (hue * 6f) % 6;
        float fraction = hue * 6f - (int) (hue * 6f);
        float low = 1f - saturation;
        float falling = 1f - fraction * saturation;
        float rising = 1f - (1f - fraction) * saturation;

        float red = switch (sextant) {
            case 0, 5 -> 1f;
            case 1 -> falling;
            case 2, 3 -> low;
            default -> rising;
        };
        float green = switch (sextant) {
            case 0 -> rising;
            case 1, 2 -> 1f;
            case 3 -> falling;
            default -> low;
        };
        float blue = switch (sextant) {
            case 0, 1 -> low;
            case 2 -> rising;
            case 3, 4 -> 1f;
            default -> falling;
        };
        return (int) (red * 255f) << 16 | (int) (green * 255f) << 8 | (int) (blue * 255f);
    }

    /**
     * One feature as the Java configured feature it builds, or nothing where Java has no such form.
     *
     * Three of Bedrock's thirteen building types have a Java feature that takes the same fields and
     * builds the same thing. The rest name a Java feature whose configuration is a different model
     * of the same idea, and a converted entry would be a guess at what the pack meant rather than
     * what it wrote, so each is named against the pack instead.
     */
    private static JsonObject configured(FeatureDefinition feature, String namespace) {
        return switch (feature.type()) {
            case "minecraft:ore_feature" -> typed("minecraft:ore", ore(feature.body()));
            case "minecraft:single_block_feature" ->
                typed("minecraft:simple_block", singleBlock(feature.body()));
            case "minecraft:weighted_random_feature" ->
                typed("minecraft:random_selector", weighted(feature.body(), namespace));
            default -> null;
        };
    }

    private static JsonObject typed(String type, JsonObject config) {
        if (config == null) return null;

        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.add("config", config);
        return json;
    }

    /**
     * A vein of blocks, which both editions state as a size and a list of what may be replaced.
     *
     * A Bedrock rule names one block it places and the blocks that block may replace; Java names one
     * pair per replacement, so a rule listing three replaceable blocks becomes three targets sharing
     * one state. A rule naming nothing to replace may replace anything.
     */
    private static JsonObject ore(JsonObject body) {
        JsonArray targets = new JsonArray();
        JsonElement rules = body.get("replace_rules");
        if (rules != null && rules.isJsonArray()) {
            rules.getAsJsonArray().forEach(one -> {
                if (one.isJsonObject()) addTargets(targets, one.getAsJsonObject());
            });
        } else {
            addTargets(targets, body);
        }
        if (targets.isEmpty()) return null;

        JsonObject config = new JsonObject();
        config.add("targets", targets);
        config.addProperty("size", (int) Json.number(body, "count", 8));
        config.addProperty("discard_chance_on_air_exposure",
            Json.number(body, "discard_chance_on_air_exposure", 0));
        return config;
    }

    private static void addTargets(JsonArray targets, JsonObject rule) {
        JsonObject state = blockState(rule.get("places_block"));
        if (state == null) return;

        JsonElement replaceable = rule.get("may_replace");
        List<JsonElement> named = replaceable != null && replaceable.isJsonArray()
            ? replaceable.getAsJsonArray().asList() : List.of();

        if (named.isEmpty()) {
            targets.add(target(alwaysTrue(), state));
            return;
        }
        named.forEach(one -> targets.add(target(blockMatch(blockName(one)), state)));
    }

    private static JsonObject target(JsonObject predicate, JsonObject state) {
        JsonObject entry = new JsonObject();
        entry.add("target", predicate);
        entry.add("state", state);
        return entry;
    }

    private static JsonObject alwaysTrue() {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("predicate_type", "minecraft:always_true");
        return predicate;
    }

    private static JsonObject blockMatch(String block) {
        JsonObject predicate = new JsonObject();
        predicate.addProperty("predicate_type", "minecraft:block_match");
        predicate.addProperty("block", block);
        return predicate;
    }

    /**
     * A single block, which Java places through a provider rather than by naming the block.
     *
     * Bedrock writes one block or a list of weighted ones, and Java has a provider for each of those
     * two shapes.
     */
    private static JsonObject singleBlock(JsonObject body) {
        AddonEntities.unsupportedFields("feature minecraft:single_block_feature", body,
            List.of("may_attach_to", "may_not_attach_to", "randomize_rotation",
                "enforce_placement_rules", "enforce_survivability_rules"));

        JsonElement places = body.get("places_block");
        if (places == null) return null;

        JsonObject provider;
        if (places.isJsonArray()) {
            JsonArray entries = new JsonArray();
            places.getAsJsonArray().forEach(one -> {
                if (!one.isJsonObject()) return;
                JsonObject weighted = one.getAsJsonObject();
                JsonObject state = blockState(weighted.get("block"));
                if (state == null) return;

                JsonObject entry = new JsonObject();
                entry.add("data", state);
                entry.addProperty("weight", Math.max(1, (int) Json.number(weighted, "weight", 1)));
                entries.add(entry);
            });
            if (entries.isEmpty()) return null;

            provider = new JsonObject();
            provider.addProperty("type", "minecraft:weighted_state_provider");
            provider.add("entries", entries);
        } else {
            JsonObject state = blockState(places);
            if (state == null) return null;

            provider = new JsonObject();
            provider.addProperty("type", "minecraft:simple_state_provider");
            provider.add("state", state);
        }

        JsonObject config = new JsonObject();
        config.add("to_place", provider);
        return config;
    }

    /**
     * One of several features picked by weight.
     *
     * Java's random selector walks its list and takes the first entry whose own chance comes up,
     * falling through to a default, so a list of weights becomes a list of chances each measured
     * against what is left after the entries before it. The last weight is the default, which is
     * what makes the whole list add up to one.
     */
    private static JsonObject weighted(JsonObject body, String namespace) {
        JsonElement declared = body.get("features");
        if (declared == null || !declared.isJsonArray()) return null;

        List<JsonElement> entries = declared.getAsJsonArray().asList();
        double remaining = 0;
        for (JsonElement entry : entries) remaining += weightOf(entry);
        if (remaining <= 0) return null;

        JsonArray chosen = new JsonArray();
        String fallback = null;
        for (int index = 0; index < entries.size(); index++) {
            String named = featureOf(entries.get(index), namespace);
            if (named == null) continue;

            if (index == entries.size() - 1) {
                fallback = named;
                break;
            }

            double weight = weightOf(entries.get(index));
            JsonObject one = new JsonObject();
            one.addProperty("feature", named);
            one.addProperty("chance", (float) (weight / remaining));
            chosen.add(one);
            remaining -= weight;
        }
        if (fallback == null) return null;

        JsonObject config = new JsonObject();
        config.add("features", chosen);
        config.addProperty("default", fallback);
        return config;
    }

    private static double weightOf(JsonElement entry) {
        return entry.isJsonArray() && entry.getAsJsonArray().size() == 2
            ? Math.max(0, entry.getAsJsonArray().get(1).getAsDouble()) : 1;
    }

    private static String featureOf(JsonElement entry, String namespace) {
        JsonElement named = entry.isJsonArray() && !entry.getAsJsonArray().isEmpty()
            ? entry.getAsJsonArray().get(0) : entry;
        if (!named.isJsonPrimitive()) return null;

        Names.Identifier id = Names.split(named.getAsString(), namespace);
        return id.namespace() + ":" + id.path();
    }

    /**
     * Where a rule puts its feature, as the placed feature Java reads it from.
     *
     * The rule's own scatter is the outer part of the placement. What it names may be a feature that
     * states a position of its own rather than a thing to build, and each of those adds a modifier
     * and hands on the feature it wraps, so a scatter around a search around an ore comes out as one
     * placed feature naming the ore.
     */
    private static JsonObject rulePlacement(FeatureRuleDefinition rule,
        Map<String, FeatureDefinition> features, Set<String> built, String namespace) {
        rule.scatter().report();

        JsonArray placement = scatterPlacement(rule.scatter(), rule.decorationStep(), true);
        String named = rule.placesFeature();

        for (int step = 0; step < WRAPPER_LIMIT && !built.contains(named); step++) {
            FeatureDefinition feature = features.get(named);
            if (feature == null || !FeatureDefinition.PLACEMENT.contains(feature.type())) break;

            wrapperPlacement(feature, placement);
            String inner = feature.wraps();
            if (inner == null) return null;
            named = inner;
        }
        if (!built.contains(named)) return null;

        placement.add(marker("minecraft:biome"));
        Names.Identifier id = Names.split(named, namespace);
        return placed(id.namespace() + ":" + id.path(), placement);
    }

    /**
     * The modifiers one feature that states a position adds to the placement around it.
     *
     * Three of the six have a Java placement modifier that asks the same question. The other three
     * search for a spot where the feature they wrap would succeed, which Java decides inside the
     * feature rather than before it, so each is named against the pack and the rest of the chain
     * still places.
     */
    private static void wrapperPlacement(FeatureDefinition feature, JsonArray placement) {
        switch (feature.type()) {
            case "minecraft:scatter_feature" -> {
                Scatter scatter = feature.scatter();
                scatter.report();
                AddonEntities.unsupported(
                    "feature minecraft:scatter_feature spread inside another scatter");
                scatterPlacement(scatter, null, false).forEach(placement::add);
            }
            case "minecraft:surface_relative_threshold_feature" -> {
                JsonObject filter = new JsonObject();
                filter.addProperty("type", "minecraft:surface_relative_threshold_filter");
                filter.addProperty("heightmap", "OCEAN_FLOOR_WG");
                filter.addProperty("max_inclusive",
                    -(int) Json.number(feature.body(), "minimum_distance_below_surface", 0));
                placement.add(filter);
            }
            case "minecraft:snap_to_surface_feature" -> {
                String surface = Json.text(feature.body(), "surface", "floor");
                // Java drops a feature onto the highest block under it and has no modifier that
                // raises one to the ceiling or picks a wall.
                if (!surface.equals("floor")) {
                    AddonEntities.unsupported(
                        "feature minecraft:snap_to_surface_feature surface " + surface);
                }
                JsonObject heightmap = new JsonObject();
                heightmap.addProperty("type", "minecraft:heightmap");
                heightmap.addProperty("heightmap", "OCEAN_FLOOR_WG");
                placement.add(heightmap);
            }
            // The three left state a position Java decides inside the feature rather than before
            // it. Each is named where every other feature type is, and the rest of the chain still
            // places what it wraps.
            default -> { }
        }
    }

    /**
     * A scatter as the placement modifiers Java spreads a feature with.
     *
     * A step is given only for the outermost scatter, because it decides the heightmap a feature
     * with no height of its own lands on: what a pack scatters in the vegetation pass belongs on top
     * of whatever is already there, and everything else belongs on the terrain.
     *
     * `spreads` is true for that outermost scatter alone. `in_square` moves a position by up to
     * fifteen blocks on each axis, so a second one can carry the feature into a neighbouring chunk,
     * and `biome_filter` then asks `WorldGenRegion` for a chunk it refuses to answer for and takes
     * the whole generating chunk with it. It would also throw away the column the heightmap
     * between the two scatters had just settled on.
     */
    private static JsonArray scatterPlacement(Scatter scatter, String step, boolean spreads) {
        JsonArray placement = new JsonArray();

        int rarity = scatter.rarity();
        if (rarity > 1) {
            JsonObject filter = new JsonObject();
            filter.addProperty("type", "minecraft:rarity_filter");
            filter.addProperty("chance", rarity);
            placement.add(filter);
        }

        int iterations = scatter.iterations();
        if (iterations > 1) {
            JsonObject count = new JsonObject();
            count.addProperty("type", "minecraft:count");
            count.addProperty("count", iterations);
            placement.add(count);
        }
        if (spreads) placement.add(marker("minecraft:in_square"));

        int[] heights = scatter.heights();
        if (heights != null) {
            JsonObject range = new JsonObject();
            range.addProperty("type", "minecraft:height_range");
            range.add("height", uniform(heights[0], heights[1], scatter.trapezoid()));
            placement.add(range);
        } else if (step != null) {
            JsonObject heightmap = new JsonObject();
            heightmap.addProperty("type", "minecraft:heightmap");
            heightmap.addProperty("heightmap",
                step.equals("VEGETAL_DECORATION") ? "MOTION_BLOCKING" : "WORLD_SURFACE_WG");
            placement.add(heightmap);
        }
        return placement;
    }

    private static JsonObject uniform(int low, int high, boolean trapezoid) {
        JsonObject minimum = new JsonObject();
        minimum.addProperty("absolute", low);
        JsonObject maximum = new JsonObject();
        maximum.addProperty("absolute", high);

        JsonObject height = new JsonObject();
        height.addProperty("type", trapezoid ? "minecraft:trapezoid" : "minecraft:uniform");
        height.add("min_inclusive", minimum);
        height.add("max_inclusive", maximum);
        return height;
    }

    private static JsonObject marker(String type) {
        JsonObject modifier = new JsonObject();
        modifier.addProperty("type", type);
        return modifier;
    }

    /**
     * The world a pack's own dimension generates, which is empty space.
     *
     * Bedrock accepts `void` and nothing else as a dimension's generator, and Java's flat generator
     * with one layer of air is the same world: no terrain, no lakes and no features, so what stands
     * in it is what the pack builds there.
     */
    public static JsonObject voidDimension(String type, String biome) {
        JsonArray layers = new JsonArray();
        JsonObject air = new JsonObject();
        air.addProperty("block", "minecraft:air");
        air.addProperty("height", 1);
        layers.add(air);

        JsonObject settings = new JsonObject();
        settings.add("layers", layers);
        settings.addProperty("biome", biome);
        settings.addProperty("lakes", false);
        settings.addProperty("features", false);
        settings.add("structure_overrides", new JsonArray());

        JsonObject generator = new JsonObject();
        generator.addProperty("type", "minecraft:flat");
        generator.add("settings", settings);

        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.add("generator", generator);
        return json;
    }

    private static JsonObject placed(String feature, JsonArray placement) {
        JsonObject json = new JsonObject();
        json.addProperty("feature", feature);
        json.add("placement", placement);
        return json;
    }

    private static Path path(Path root, Names.Identifier id, String directory) {
        return root.resolve("data").resolve(id.namespace()).resolve(directory)
            .resolve(id.path() + ".json");
    }

    private static void write(Path file, JsonObject content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
    }

    /** A Bedrock block reference as the state Java writes one with. */
    private static JsonObject blockState(JsonElement declared) {
        String name = blockName(declared);
        if (name == null) return null;

        JsonObject state = new JsonObject();
        state.addProperty("Name", name);
        return state;
    }

    /**
     * The name out of a Bedrock block reference, which is a string or an object naming one.
     *
     * The states such an object may carry beside the name are Bedrock's own property vocabulary,
     * which shares neither its names nor its values with Java's, so a reference carrying them is
     * named against the pack and read as the bare block.
     */
    private static String blockName(JsonElement declared) {
        if (declared == null) return null;
        if (declared.isJsonPrimitive()) return qualified(declared.getAsString());

        if (!declared.isJsonObject()) return null;
        JsonObject body = declared.getAsJsonObject();
        if (body.has("states")) AddonEntities.unsupported("feature block states");

        String name = Json.text(body, "name", null);
        return name == null ? null : qualified(name);
    }

    private static String qualified(String name) {
        return name.indexOf(':') > 0 ? name : "minecraft:" + name;
    }
}
