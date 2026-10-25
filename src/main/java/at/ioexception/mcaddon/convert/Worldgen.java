package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.bedrock.ClientBiomeDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.BiomeDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.DataValues;
import at.ioexception.mcaddon.bedrock.behaviour.DimensionDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.FeatureDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.FeatureRuleDefinition;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import at.ioexception.mcaddon.worldgen.BedrockFeatures;
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
 * `features/` file becomes a configured feature entry with a bare `worldgen/placed_feature`
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

    /**
     * Builds everything the pack's worldgen becomes, and writes it when the conversion is fresh.
     *
     * A cached conversion has the files already. What it still needs is what building them names
     * against the pack, so the entries are built either way.
     */
    public static void write(Path root, boolean fresh, String namespace, List<BiomeDefinition> biomes,
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
            write(fresh, path(root, id, "dimension_type"),
                Platform.dimensionType(dimension.minimumY(), dimension.height()));
            write(fresh, path(root, id, "dimension"),
                voidDimension(id.namespace() + ":" + id.path(), dimension.biome()));
        }

        for (BiomeDefinition biome : biomes) {
            Names.Identifier id = Names.split(biome.identifier(), namespace);
            write(fresh, path(root, id, "worldgen/biome"),
                biome(biome, colours.getOrDefault(biome.identifier(), ClientBiomeDefinition.NONE)));
        }

        Map<String, FeatureDefinition> byIdentifier = new LinkedHashMap<>();
        features.forEach(feature -> byIdentifier.put(featureId(feature.identifier(), namespace), feature));

        Map<String, JsonObject> leaves = new LinkedHashMap<>();
        byIdentifier.forEach((id, feature) -> {
            JsonObject leaf = leaf(feature);
            if (leaf != null) leaves.put(id, leaf);
        });
        Set<String> placeable = placeable(byIdentifier, leaves.keySet(), namespace);

        for (String id : placeable) {
            FeatureDefinition feature = byIdentifier.get(id);
            JsonObject configured = leaves.containsKey(id) ? leaves.get(id) : composite(feature, namespace);
            Names.Identifier at = Names.split(id, namespace);
            if (configured != null) {
                write(fresh, path(root, at, Platform.featureDirectory()), configured);
                write(fresh, path(root, at, "worldgen/placed_feature"), placed(id, new JsonArray()));
            } else {
                JsonArray placement = new JsonArray();
                String inner = unwrap(id, byIdentifier, placement, namespace);
                write(fresh, path(root, at, "worldgen/placed_feature"), placed(inner, placement));
            }
        }

        for (FeatureRuleDefinition rule : rules) {
            String named = featureId(rule.placesFeature(), namespace);
            if (!placeable.contains(named)) continue;

            // The biome is asked once, at the surface over the chunk's corner, before the scatter
            // moves anywhere: a rule's heights are often below the surface, where Java may answer
            // with a cave biome the pack's filter never named.
            JsonArray placement = new JsonArray();
            JsonObject surface = new JsonObject();
            surface.addProperty("type", "minecraft:heightmap");
            surface.addProperty("heightmap", "WORLD_SURFACE_WG");
            placement.add(surface);
            placement.add(marker("minecraft:biome"));
            JsonObject scatter = new JsonObject();
            scatter.addProperty("type", BedrockFeatures.SCATTER);
            scatter.add("distribution", rule.scatter().body());
            placement.add(scatter);
            String inner = unwrap(named, byIdentifier, placement, namespace);

            write(fresh, path(root, Names.split(rule.identifier(), namespace), "worldgen/placed_feature"),
                placed(inner, placement));
        }
    }

    /**
     * The features that end in something Java can place, each of which gets a placed feature under its
     * own name for others to refer to.
     *
     * A feature naming others is placeable once everything it names is, so a feature naming one that
     * no type here builds, one no pack declares, or itself through a loop is left out rather than
     * written as an entry whose reference the game cannot resolve.
     */
    private static Set<String> placeable(Map<String, FeatureDefinition> features, Set<String> leaves,
        String namespace) {
        Set<String> placeable = new LinkedHashSet<>(leaves);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, FeatureDefinition> feature : features.entrySet()) {
                if (placeable.contains(feature.getKey())) continue;

                List<String> named = references(feature.getValue(), namespace);
                if (named.isEmpty() || !placeable.containsAll(named)) continue;
                placeable.add(feature.getKey());
                grew = true;
            }
        }
        return placeable;
    }

    private static List<String> references(FeatureDefinition feature, String namespace) {
        JsonElement listed = feature.body().get("features");
        if (feature.type().equals("minecraft:weighted_random_feature")
            || feature.type().equals("minecraft:aggregate_feature")) {
            return listed == null || !listed.isJsonArray() ? List.of() : listed.getAsJsonArray().asList()
                .stream().map(entry -> featureOf(entry, namespace)).toList();
        }
        String wrapped = feature.wraps();
        return wrapped == null ? List.of() : List.of(featureId(wrapped, namespace));
    }

    /**
     * Follows features that state a position Java states as placement modifiers, adding each one's
     * modifiers, to the feature they wrap.
     */
    private static String unwrap(String id, Map<String, FeatureDefinition> features, JsonArray placement,
        String namespace) {
        String named = id;
        for (int step = 0; step < WRAPPER_LIMIT; step++) {
            FeatureDefinition feature = features.get(named);
            if (feature == null || !FeatureDefinition.PLACEMENT.contains(feature.type())
                || feature.type().equals("minecraft:scatter_feature")) {
                break;
            }
            wrapperPlacement(feature, placement);
            named = featureId(feature.wraps(), namespace);
        }
        return named;
    }

    private static String featureId(String identifier, String namespace) {
        Names.Identifier id = Names.split(identifier, namespace);
        return id.namespace() + ":" + id.path();
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
        Platform.biomeSpawning(json, biome.creatureSpawnProbability());
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
     * A feature that builds blocks itself, as the configured feature it becomes, or nothing where no
     * type here builds it.
     *
     * An ore vein has a Java feature that takes the same fields and builds the same thing. A single
     * block has Java's `simple_block`, which always asks whether the block can stay where it lands and
     * has nothing for the neighbours a pack may ask for, so it takes a type of this mod's own. The
     * rest name a Java feature whose configuration is a different model of the same idea, and a
     * converted entry would be a guess at what the pack meant rather than what it wrote, so each is
     * named against the pack instead.
     */
    private static JsonObject leaf(FeatureDefinition feature) {
        return switch (feature.type()) {
            case "minecraft:ore_feature" -> typed("minecraft:ore", ore(feature.body()));
            case "minecraft:single_block_feature" -> typed(BedrockFeatures.SINGLE_BLOCK, singleBlock(feature.body()));
            default -> null;
        };
    }

    /**
     * A feature that places other features, as the configured feature it becomes, or nothing for one
     * Java states as placement modifiers instead.
     */
    private static JsonObject composite(FeatureDefinition feature, String namespace) {
        return switch (feature.type()) {
            case "minecraft:weighted_random_feature" ->
                typed("minecraft:random_selector", weighted(feature.body(), namespace));
            case "minecraft:aggregate_feature" -> typed(BedrockFeatures.AGGREGATE, aggregate(feature, namespace));
            case "minecraft:scatter_feature" -> {
                JsonObject config = new JsonObject();
                config.add("distribution", feature.scatter().body());
                config.addProperty("places_feature", featureId(feature.wraps(), namespace));
                yield typed(BedrockFeatures.SCATTER, config);
            }
            default -> null;
        };
    }

    private static JsonObject aggregate(FeatureDefinition feature, String namespace) {
        JsonArray named = new JsonArray();
        references(feature, namespace).forEach(named::add);

        JsonObject config = new JsonObject();
        config.add("features", named);
        config.addProperty("early_out", Json.text(feature.body(), "early_out", "none"));
        return config;
    }

    private static JsonObject typed(String type, JsonObject config) {
        return config == null ? null : Platform.feature(type, config);
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
        BlockReference placed = block(rule.get("places_block"));
        if (placed == null) return;
        JsonObject state = placed.json();

        JsonElement replaceable = rule.get("may_replace");
        List<JsonElement> named = replaceable != null && replaceable.isJsonArray()
            ? replaceable.getAsJsonArray().asList() : List.of();

        if (named.isEmpty()) {
            targets.add(target(alwaysTrue(), state));
            return;
        }
        named.forEach(one -> {
            BlockReference replaced = block(one);
            if (replaced != null) targets.add(target(blockMatch(replaced.id()), state));
        });
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
     * A single block, as the configuration of this mod's own single block feature.
     *
     * Bedrock names the faces a neighbour is asked about as `top`, `bottom`, the four directions,
     * `sides` for all four of those and `all` for all six, and the configuration names each face by
     * its Java direction.
     */
    private static JsonObject singleBlock(JsonObject body) {
        JsonElement places = body.get("places_block");
        if (places == null) return null;

        JsonArray choices = new JsonArray();
        List<JsonElement> listed = places.isJsonArray() ? places.getAsJsonArray().asList() : List.of(places);
        listed.forEach(one -> {
            boolean weighted = places.isJsonArray() && one.isJsonObject() && one.getAsJsonObject().has("block");
            BlockReference state = block(weighted ? one.getAsJsonObject().get("block") : one);
            if (state == null) return;

            JsonObject choice = new JsonObject();
            choice.add("state", state.json());
            choice.addProperty("weight",
                weighted ? Math.max(1, (int) Json.number(one.getAsJsonObject(), "weight", 1)) : 1);
            choices.add(choice);
        });
        if (choices.isEmpty()) return null;

        JsonObject config = new JsonObject();
        config.add("places", choices);
        JsonElement replaceable = body.get("may_replace");
        if (replaceable != null) config.add("may_replace", matches(replaceable));

        JsonElement attach = body.get("may_attach_to");
        if (attach != null && attach.isJsonObject()) {
            JsonObject attachment = new JsonObject();
            attachment.add("faces", faces(attach.getAsJsonObject()));
            attachment.addProperty("min_sides_must_attach",
                (int) Json.number(attach.getAsJsonObject(), "min_sides_must_attach", 1));
            attachment.addProperty("auto_rotate", Json.bool(attach.getAsJsonObject(), "auto_rotate", true));
            config.add("may_attach_to", attachment);
        }
        JsonElement shunned = body.get("may_not_attach_to");
        if (shunned != null && shunned.isJsonObject()) {
            config.add("may_not_attach_to", faces(shunned.getAsJsonObject()));
        }

        config.addProperty("enforce_rules", Json.bool(body, "enforce_survivability_rules", false)
            || Json.bool(body, "enforce_placement_rules", false));
        config.addProperty("randomize_rotation", Json.bool(body, "randomize_rotation", false));
        return config;
    }

    /** Bedrock's face names, each as the Java directions it stands for. */
    private static final Map<String, List<String>> FACES = Map.of(
        "top", List.of("up"), "bottom", List.of("down"),
        "north", List.of("north"), "east", List.of("east"), "south", List.of("south"), "west", List.of("west"),
        "sides", List.of("north", "east", "south", "west"),
        "all", List.of("up", "down", "north", "east", "south", "west"));

    private static JsonObject faces(JsonObject bedrock) {
        JsonObject faces = new JsonObject();
        bedrock.entrySet().forEach(face -> FACES.getOrDefault(face.getKey(), List.of()).forEach(direction -> {
            JsonArray listed = faces.has(direction) ? faces.getAsJsonArray(direction) : new JsonArray();
            listed.addAll(matches(face.getValue()));
            faces.add(direction, listed);
        }));
        return faces;
    }

    /** One block reference or a list of them, as the blocks and named states a neighbour is matched by. */
    private static JsonArray matches(JsonElement declared) {
        JsonArray matches = new JsonArray();
        List<JsonElement> listed = declared.isJsonArray() ? declared.getAsJsonArray().asList() : List.of(declared);
        listed.forEach(one -> {
            BlockReference reference = block(one);
            if (reference == null) return;

            JsonObject match = new JsonObject();
            match.addProperty("block", reference.id());
            JsonObject properties = new JsonObject();
            reference.properties().forEach(properties::addProperty);
            match.add("properties", properties);
            matches.add(match);
        });
        return matches;
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
        return named.isJsonPrimitive() ? featureId(named.getAsString(), namespace) : null;
    }

    /**
     * The modifiers one feature that states a position adds to the placement around it.
     *
     * Two of the five have a Java placement modifier that asks the same question. The other three
     * search for a spot where the feature they wrap would succeed, which Java decides inside the
     * feature rather than before it, so each is named against the pack and the rest of the chain
     * still places.
     */
    private static void wrapperPlacement(FeatureDefinition feature, JsonArray placement) {
        switch (feature.type()) {
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

    private static void write(boolean fresh, Path file, JsonObject content) throws IOException {
        if (!fresh) return;

        Files.createDirectories(file.getParent());
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
    }

    /**
     * A Bedrock block reference as the state Java writes one with, or null for a vanilla block Java
     * has nothing for, which is reported.
     *
     * A reference is a name or an object carrying a name and the states beside it, in Bedrock's
     * spelling of both, so `golden_rail` is a powered rail and `log` with `old_log_type` set to
     * `spruce` is a spruce log. A pack's own block is registered after its features are written,
     * so its name is written as it stands and its states, which are its own, are named instead.
     */
    private static BlockReference block(JsonElement declared) {
        String name = declared == null ? null : declared.isJsonPrimitive() ? declared.getAsString()
            : declared.isJsonObject() ? Json.text(declared.getAsJsonObject(), "name", null) : null;
        if (name == null) return null;

        JsonObject written = declared.isJsonObject() ? declared.getAsJsonObject().getAsJsonObject("states") : null;
        Map<String, String> states = new LinkedHashMap<>();
        if (written != null) {
            written.entrySet().forEach(state -> states.put(state.getKey(), state.getValue().getAsString()));
        }

        if (DataValues.vanilla(name) == null) {
            if (!states.isEmpty()) AddonEntities.unsupported("feature block states");
            return new BlockReference(BedrockBlockNames.packOwn(name), Map.of());
        }

        BedrockBlockNames.Resolved resolved = BedrockBlockNames.state(name, states,
            detail -> AddonEntities.unsupported("feature " + detail));
        if (resolved == null) {
            AddonEntities.unsupported("feature block " + name);
            return null;
        }

        return new BlockReference(resolved.id(),
            resolved.stated().isEmpty() ? Map.of() : resolved.properties());
    }

    private record BlockReference(String id, Map<String, String> properties) {
        JsonObject json() {
            return Platform.blockState(id, properties);
        }
    }
}
