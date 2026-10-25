package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * What a resource pack paints and plays one biome with.
 *
 * Bedrock puts the drawing half of a biome in the resource pack and the generating half in the
 * behaviour pack, under the same identifier. Java keeps both on one entry, so the two halves are
 * read together and the colours, the particle, the ambient sounds and the music land on the
 * converted biome.
 *
 * A pack may write them in either of two shapes. `biomes/<name>.client_biome.json` is one file per
 * biome with named components; `biomes_client.json` is one file naming every biome at once, which
 * is what a pack written before the split says. Both are read, and the per-biome file wins where a
 * pack ships both.
 *
 * The fog a biome names is a file of its own under `fogs/`, so it is resolved here and carried on
 * the biome that named it, and the same goes for the music definition its `minecraft:biome_music`
 * names.
 */
public record ClientBiomeDefinition(int water, int sky, int grass, int foliage, int dryFoliage,
    FogDefinition fog, BiomeParticles particles, BiomeSounds sounds, BiomeMusic music) {

    /** No colour at all, which is what a biome the resource pack never mentions gets. */
    public static final ClientBiomeDefinition NONE = new ClientBiomeDefinition(-1, -1, -1, -1, -1,
        FogDefinition.NONE, BiomeParticles.NONE, BiomeSounds.NONE, BiomeMusic.NONE);

    /** What a biome no resource pack states a water colour for is drawn with. */
    public static final int DEFAULT_WATER = 4159204;

    /**
     * The components a converted biome carries something of, and the grade the client draws it
     * with, which `ColourGrading` reads.
     */
    private static final Set<String> READ = Set.of("minecraft:water_appearance",
        "minecraft:sky_color", "minecraft:grass_appearance", "minecraft:foliage_appearance",
        "minecraft:dry_foliage_color", "minecraft:fog_appearance", "minecraft:ambient_sounds",
        "minecraft:biome_music", "minecraft:precipitation", "minecraft:color_grading_identifier");

    /** What one entry in the whole-pack file states that a converted biome carries something of. */
    private static final Set<String> LEGACY_READ = Set.of("water_surface_color", "water_fog_color",
        "water_fog_distance", "fog_identifier");

    /**
     * Every biome the resource pack paints, by identifier.
     *
     * The whole-pack file is read first so a per-biome file overrides it, which is the order
     * Bedrock reads them in.
     *
     * @param sound the Java sound event one of the pack's sound names becomes, or null for none
     */
    public static Map<String, ClientBiomeDefinition> readAll(BedrockPack pack,
        UnaryOperator<String> sound) {
        Map<String, FogDefinition> fogs = FogDefinition.readAll(pack);
        Map<String, MusicDefinitions> music = MusicDefinitions.readAll(pack);
        Map<String, ClientBiomeDefinition> found = new LinkedHashMap<>();
        readLegacy(pack, fogs, found);

        for (Path file : pack.list("biomes", ".json")) {
            try {
                read(Json.parseObject(file), fogs, music, sound, found);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return found;
    }

    /**
     * The fog file each painted biome names, by biome identifier, read without reporting anything.
     *
     * The client draws a biome's lava, powder snow and weather fog from that file, so it needs the
     * name after the conversion that already reported what it dropped.
     */
    public static Map<String, String> fogNames(BedrockPack pack) {
        Map<String, String> names = new LinkedHashMap<>();

        try {
            if (pack.has("biomes_client.json")) {
                JsonObject biomes = Json.parseObject(pack.file("biomes_client.json"))
                    .getAsJsonObject("biomes");
                if (biomes != null) {
                    biomes.entrySet().stream().filter(entry -> entry.getValue().isJsonObject())
                        .forEach(entry -> remember(names, entry.getKey(),
                            Json.text(entry.getValue().getAsJsonObject(), "fog_identifier", null)));
                }
            }

            for (Path file : pack.list("biomes", ".json")) {
                JsonObject body = Json.parseObject(file).getAsJsonObject("minecraft:client_biome");
                JsonObject description = body == null ? null : body.getAsJsonObject("description");
                JsonObject components = body == null ? null : body.getAsJsonObject("components");
                JsonObject appearance = components == null ? null
                    : components.getAsJsonObject("minecraft:fog_appearance");
                if (description == null || appearance == null) continue;

                remember(names, Json.text(description, "identifier", null),
                    Json.text(appearance, "fog_identifier", null));
            }
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read the fogs of the client biomes: {}", error.toString());
        }
        return names;
    }

    private static void remember(Map<String, String> names, String biome, String fog) {
        if (biome != null && fog != null) names.put(biome, fog);
    }

    /** The same colours with the behaviour pack's own tints where it states them. */
    public ClientBiomeDefinition tinted(int grassTint, int foliageTint) {
        return new ClientBiomeDefinition(water, sky, grassTint >= 0 ? grassTint : grass,
            foliageTint >= 0 ? foliageTint : foliage, dryFoliage, fog, particles, sounds, music);
    }

    private static void read(JsonObject root, Map<String, FogDefinition> fogs,
        Map<String, MusicDefinitions> music, UnaryOperator<String> sound,
        Map<String, ClientBiomeDefinition> into) {
        JsonObject body = root.has("minecraft:client_biome")
            && root.get("minecraft:client_biome").isJsonObject()
            ? root.getAsJsonObject("minecraft:client_biome") : null;
        if (body == null) return;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        JsonObject components = body.getAsJsonObject("components");
        if (identifier == null || components == null) return;

        components.keySet().stream().filter(name -> !READ.contains(name))
            .forEach(name -> AddonEntities.unsupported("client biome " + name));

        JsonObject appearance = components.getAsJsonObject("minecraft:fog_appearance");
        into.put(identifier, new ClientBiomeDefinition(
            colour(components, "minecraft:water_appearance", "surface_color"),
            colour(components, "minecraft:sky_color", "sky_color"),
            colour(components, "minecraft:grass_appearance", "color"),
            colour(components, "minecraft:foliage_appearance", "color"),
            colour(components, "minecraft:dry_foliage_color", "color"),
            named(appearance == null ? null : Json.text(appearance, "fog_identifier", null), fogs),
            BiomeParticles.read(components.getAsJsonObject("minecraft:precipitation")),
            BiomeSounds.read(components.getAsJsonObject("minecraft:ambient_sounds"), sound),
            BiomeMusic.read(components.getAsJsonObject("minecraft:biome_music"), music, sound)));
    }

    private static void readLegacy(BedrockPack pack, Map<String, FogDefinition> fogs,
        Map<String, ClientBiomeDefinition> into) {
        if (!pack.has("biomes_client.json")) return;

        try {
            JsonObject biomes = Json.parseObject(pack.file("biomes_client.json"))
                .getAsJsonObject("biomes");
            if (biomes == null) return;

            for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;

                JsonObject body = entry.getValue().getAsJsonObject();
                body.keySet().stream().filter(name -> !LEGACY_READ.contains(name))
                    .forEach(name -> AddonEntities.unsupported("biomes_client.json " + name));

                into.put(entry.getKey(), new ClientBiomeDefinition(
                    Json.colour(Json.text(body, "water_surface_color", null)), -1, -1, -1, -1,
                    legacyFog(body, fogs), BiomeParticles.NONE, BiomeSounds.NONE, BiomeMusic.NONE));
            }
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read biomes_client.json: {}", error.toString());
        }
    }

    /**
     * The fog one entry in the whole-pack file paints.
     *
     * Such an entry names a fog of its own and may state the water half outright beside it, and
     * what it states itself is what Bedrock draws.
     */
    private static FogDefinition legacyFog(JsonObject body, Map<String, FogDefinition> fogs) {
        FogDefinition named = named(Json.text(body, "fog_identifier", null), fogs);
        int colour = Json.colour(Json.text(body, "water_fog_color", null));
        float end = (float) Json.number(body, "water_fog_distance", -1);
        if (colour < 0 && end < 0) return named;

        return new FogDefinition(named.air(), new FogDefinition.Location(
            colour >= 0 ? colour : named.water().colour(), named.water().start(),
            end >= 0 ? end : named.water().end()));
    }

    /**
     * The fog under one identifier, or nothing where no installed pack ships it.
     *
     * Bedrock's own `minecraft:fog_*` settings live in the engine rather than in any add-on, so a
     * biome naming one is asking for a fog this mod never sees the values of. It is named against
     * the pack, because the biome keeps Java's own fog instead of the one the author chose.
     */
    private static FogDefinition named(String identifier, Map<String, FogDefinition> fogs) {
        if (identifier == null) return FogDefinition.NONE;

        FogDefinition found = fogs.get(identifier);
        if (found != null) return found;

        AddonEntities.unsupported("fog " + identifier + ", which no installed pack defines");
        return FogDefinition.NONE;
    }

    private static int colour(JsonObject components, String component, String field) {
        JsonObject body = components.getAsJsonObject(component);
        return body == null ? -1 : Json.colour(Json.text(body, field, null));
    }
}
