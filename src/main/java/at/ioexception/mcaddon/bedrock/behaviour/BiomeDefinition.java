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
 * A biome a behaviour pack declares.
 *
 * The two editions describe a biome from opposite ends. Bedrock states the climate a player feels,
 * the material the surface is built from and a list of tags every other file selects on; Java states
 * a climate the biome source searches in, the colours a client draws with, and the carvers, features
 * and spawners that fill it. What both hold is the climate and the colours, and this reads the rest
 * of Bedrock's side to decide where in Java's climate space the biome belongs.
 *
 * Surfaces and heights have no field on a Java biome at all: Java states those in
 * `worldgen/noise_settings` and in density functions, one set for a whole dimension rather than one
 * per biome. Every component naming one is reported against the pack.
 */
public record BiomeDefinition(String identifier, List<String> tags,
    Map<String, JsonObject> components) {

    /** The components a converted biome carries something of. Anything else is reported. */
    private static final Set<String> READ = Set.of(
        "minecraft:climate", "minecraft:tags", "minecraft:multinoise_generation_rules",
        "minecraft:map_tints", "minecraft:creature_spawn_probability",
        "minecraft:partially_frozen");

    /** What a climate states beyond the two numbers Java carries. */
    private static final List<String> CLIMATE_DROPPED = List.of("snow_accumulation");

    /** The tags that put a biome in a world other than the overworld. */
    private static final Set<String> NETHER_TAGS = Set.of("nether", "nether_wastes",
        "netherwart_forest", "warped_forest", "crimson_forest", "soulsand_valley",
        "basalt_deltas");

    public static List<BiomeDefinition> readAll(BedrockPack pack) {
        List<BiomeDefinition> biomes = new ArrayList<>();
        for (Path file : pack.list("biomes", ".json")) {
            try {
                BiomeDefinition biome = read(Json.parseObject(file));
                if (biome != null) biomes.add(biome);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return biomes;
    }

    private static BiomeDefinition read(JsonObject root) {
        JsonObject body = root.has("minecraft:biome") && root.get("minecraft:biome").isJsonObject()
            ? root.getAsJsonObject("minecraft:biome") : null;
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        Map<String, JsonObject> components = new LinkedHashMap<>();
        JsonObject declared = body.getAsJsonObject("components");
        if (declared != null) {
            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                components.put(entry.getKey(), entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject() : new JsonObject());
            }
        }
        return new BiomeDefinition(identifier, tags(components.get("minecraft:tags")), components);
    }

    private static List<String> tags(JsonObject component) {
        List<String> found = new ArrayList<>();
        if (component == null || !component.has("tags")) return found;

        JsonElement declared = component.get("tags");
        if (!declared.isJsonArray()) return found;

        declared.getAsJsonArray().forEach(one -> {
            if (one.isJsonPrimitive()) found.add(one.getAsString());
        });
        return found;
    }

    /** Which of the three worlds the biome's own tags put it in. */
    public String world() {
        if (tags.contains("the_end")) return "end";
        return tags.stream().anyMatch(NETHER_TAGS::contains) ? "nether" : "overworld";
    }

    public float temperature() {
        return number("minecraft:climate", "temperature", 0.8f);
    }

    public float downfall() {
        return number("minecraft:climate", "downfall", 0.4f);
    }

    /** Bedrock states no precipitation flag, and a biome nothing falls in is one with no downfall. */
    public boolean hasPrecipitation() {
        return downfall() > 0f;
    }

    /** Java's `frozen` temperature modifier, which is what Bedrock calls a partially frozen biome. */
    public boolean partiallyFrozen() {
        return components.containsKey("minecraft:partially_frozen");
    }

    /** How likely a chunk of this biome is to have mobs in it when it is first generated. */
    public float creatureSpawnProbability() {
        return number("minecraft:creature_spawn_probability", "probability", 0.1f);
    }

    /** The grass colour the biome paints a map with, as a packed integer, or -1 for none. */
    public int grassColour() {
        JsonObject tints = components.get("minecraft:map_tints");
        if (tints == null) return -1;

        JsonElement grass = tints.get("grass");
        if (grass == null) return -1;
        if (grass.isJsonPrimitive()) return Json.colour(grass.getAsString());

        JsonObject body = grass.getAsJsonObject();
        // A grass tint stated as noise is sampled per block by Bedrock's own renderer. Java offers
        // two named modifiers instead, one per vanilla biome that uses them, and no way to say which
        // a pack meant.
        if ("noise".equals(Json.text(body, "type", "tint"))) {
            AddonEntities.unsupported("biome minecraft:map_tints grass noise");
            return -1;
        }
        return Json.colour(Json.text(body, "tint", null));
    }

    public int foliageColour() {
        JsonObject tints = components.get("minecraft:map_tints");
        return tints == null ? -1 : Json.colour(Json.text(tints, "foliage", null));
    }

    /** How far either side of its centre a converted biome claims temperature and humidity. */
    private static final float CLIMATE_REACH = 0.1f;

    /** How far either side of zero it claims the two Bedrock never mentions. */
    private static final float SHAPE_REACH = 0.2f;

    /**
     * Where in Java's climate space the biome sits, as the seven bands a biome source searches.
     *
     * Java picks the biome whose bands are nearest the point the terrain noise landed on, and a biome
     * stating a single value for a band rather than a range is nearest nowhere: every one of the
     * game's own sits in a box, so a converted biome has to claim a box of its own or never generate.
     * The bands are as narrow as the pack's own words allow, so the biome takes a corner of the world
     * rather than a swathe of it.
     *
     * A pack writing `minecraft:multinoise_generation_rules` has already stated four of the seven,
     * because Bedrock's nether searches the same kind of space Java searches everywhere, and there
     * the game's own entries are single values too, so these are as well. A pack writing only a
     * climate has stated the two a player feels, and the shape of the land is read off the tags: an
     * ocean sits out at sea, a beach on the shoreline, and a mountain where the land is least eroded.
     */
    public float[][] climate() {
        JsonObject noise = components.get("minecraft:multinoise_generation_rules");
        if (noise != null) {
            return new float[][] {
                at((float) Json.number(noise, "target_temperature", 0)),
                at((float) Json.number(noise, "target_humidity", 0)),
                at(0f), at(0f),
                at((float) Json.number(noise, "target_altitude", 0)),
                at((float) Json.number(noise, "target_weirdness", 0)),
                at(0f)};
        }

        // Bedrock runs from a snowy biome at -0.7 to the nether at 2.0 with the plains at 0.8, and
        // Java's band runs from -1 to 1 with its temperate biomes around zero.
        float temperature = clamp((temperature() - 0.8f) / 1.2f);
        float humidity = clamp(downfall() * 2f - 1f);
        boolean mountain = tags.contains("mountain") || tags.contains("mountains")
            || tags.contains("extreme_hills");

        float[] continentalness = tags.contains("ocean") || tags.contains("deep")
            ? new float[] {-0.8f, -0.6f}
            : tags.contains("beach") || tags.contains("shore") ? new float[] {-0.19f, -0.11f}
            : mountain ? new float[] {0.3f, 0.5f} : new float[] {0.1f, 0.3f};
        float[] erosion = mountain
            ? new float[] {-1f, -0.6f} : new float[] {-SHAPE_REACH, SHAPE_REACH};

        return new float[][] {
            around(temperature, CLIMATE_REACH), around(humidity, CLIMATE_REACH),
            continentalness, erosion, at(0f),
            new float[] {-SHAPE_REACH, SHAPE_REACH}, at(0f)};
    }

    private static float[] at(float value) {
        return new float[] {value, value};
    }

    private static float[] around(float centre, float reach) {
        return new float[] {clamp(centre - reach), clamp(centre + reach)};
    }

    /** Names every component the conversion has no Java field for, against the pack that wrote it. */
    public void report() {
        components.keySet().stream().filter(name -> !READ.contains(name))
            .forEach(name -> AddonEntities.unsupported("biome " + name));

        JsonObject climate = components.get("minecraft:climate");
        if (climate != null) {
            AddonEntities.unsupportedFields("biome minecraft:climate", climate, CLIMATE_DROPPED);
        }
    }

    private float number(String component, String field, float fallback) {
        JsonObject body = components.get(component);
        return body == null ? fallback : (float) Json.number(body, field, fallback);
    }

    private static float clamp(float value) {
        return Math.clamp(value, -1f, 1f);
    }
}
