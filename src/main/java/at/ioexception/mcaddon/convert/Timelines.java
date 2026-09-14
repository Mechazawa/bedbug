package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.bedrock.AtmosphereDefinition;
import at.ioexception.mcaddon.bedrock.Keyframes;
import at.ioexception.mcaddon.bedrock.LightingDefinition;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pack's lighting and atmospherics as the timeline registry Java reads them from.
 *
 * Both are a table of values against the time of day, and `Timeline` is exactly that: an
 * `AttributeTrack` per `EnvironmentAttribute`, each a `KeyframeTrack` the game samples as the day
 * runs. `RegistryDataLoader` loads the registry out of a data pack, so a converted pack can put
 * entries in it.
 *
 * Which world a timeline reaches is the one thing the two editions disagree about. Bedrock names a
 * lighting and an atmosphere on each biome; Java has `HolderSet<Timeline>` on `DimensionType` and
 * nothing of the kind on `Biome`, whose own `EnvironmentAttributeMap` holds a constant rather than a
 * curve. So the settings collection Bedrock gives every biome that names no other is written into
 * the overworld's timeline tag, and a collection one biome names is named against the pack by the
 * reader that found it.
 *
 * A written track is an override where the file states the value outright and a multiply or an add
 * where it states a factor or an offset. The game's own timelines are applied before a data pack's,
 * so an override is the last word and a pack gets the sky it wrote.
 */
public final class Timelines {
    /** The tag the overworld's dimension type names, which a data pack adds to. */
    private static final String OVERWORLD_TIMELINES = "data/minecraft/tags/timeline/in_overworld.json";

    /** The clock a Bedrock day runs on, and how long one turn of it is. */
    private static final String OVERWORLD_CLOCK = "minecraft:overworld";

    /** What the band Java paints around the sun is written with, so the game's own alpha stands. */
    private static final float OPAQUE = 255f;

    private Timelines() {
    }

    /** Writes the timelines the pack's settings collections become, and the tag naming them. */
    public static void write(Path root, String namespace,
        Map<String, LightingDefinition> lightings,
        Map<String, AtmosphereDefinition> atmospheres) throws IOException {
        if (lightings.isEmpty() && atmospheres.isEmpty()) return;
        if (!Platform.convertsTimelines()) {
            lightings.keySet().forEach(name ->
                AddonEntities.unsupported("resource pack lighting (needs 26 or newer)"));
            atmospheres.keySet().forEach(name ->
                AddonEntities.unsupported("resource pack atmospherics (needs 26 or newer)"));
            return;
        }

        List<String> written = new ArrayList<>();
        for (LightingDefinition lighting : lightings.values()) {
            if (lighting.wholeWorld()) {
                add(written, write(root, namespace, "lighting", tracks(lighting)));
            }
        }
        for (AtmosphereDefinition atmosphere : atmospheres.values()) {
            if (atmosphere.wholeWorld()) {
                add(written, write(root, namespace, "atmospherics", tracks(atmosphere)));
            }
        }

        if (!written.isEmpty()) writeTag(root, written);
    }

    /** Every attribute a lighting file drives, by the name the timeline holds its track under. */
    public static Map<String, JsonObject> tracks(LightingDefinition lighting) {
        Map<String, JsonObject> tracks = new LinkedHashMap<>();
        // The ambient term is a colour in both editions, so it is stated outright.
        put(tracks, "minecraft:visual/ambient_light_color", lighting.ambientColour(), null);
        // The sky intensity is already a factor on how much the sky lights a scene.
        put(tracks, "minecraft:visual/sky_light_factor", lighting.skyIntensity(), "multiply");
        // The orbital offset turns both bodies away from where the game already puts them.
        put(tracks, "minecraft:visual/sun_angle", lighting.orbitalOffset(), "add");
        put(tracks, "minecraft:visual/moon_angle", lighting.orbitalOffset(), "add");
        put(tracks, "minecraft:gameplay/sky_light_level", lighting.skyLightLevel(), null);
        put(tracks, "minecraft:visual/sky_light_color", lighting.skyLightColour(), null);
        return tracks;
    }

    /** Every attribute an atmospherics file drives, by the name the timeline holds its track under. */
    public static Map<String, JsonObject> tracks(AtmosphereDefinition atmosphere) {
        Map<String, JsonObject> tracks = new LinkedHashMap<>();
        put(tracks, "minecraft:visual/sky_color", atmosphere.zenithColour(), null);
        // Java paints the horizon colour as the fog, which a pack's own `fogs/` files already state,
        // and keeps a band of its own around the sun. The band is what is left for the horizon
        // colour, and it is multiplied in so the game's own alpha still decides when it shows.
        Keyframes horizon = atmosphere.horizonColour();
        put(tracks, "minecraft:visual/sunrise_sunset_color",
            horizon == null ? null : horizon.withAlpha(OPAQUE), "multiply");
        return tracks;
    }

    private static void put(Map<String, JsonObject> tracks, String attribute, Keyframes curve,
        String modifier) {
        if (curve != null) tracks.put(attribute, curve.track(modifier));
    }

    private static void add(List<String> written, String timeline) {
        if (timeline != null) written.add(timeline);
    }

    /** One timeline entry, or nothing where the settings collection drove no attribute at all. */
    private static String write(Path root, String namespace, String name,
        Map<String, JsonObject> tracks) throws IOException {
        if (tracks.isEmpty()) return null;

        JsonObject written = new JsonObject();
        written.addProperty("clock", OVERWORLD_CLOCK);
        written.addProperty("period_ticks", Keyframes.DAY_TICKS);
        JsonObject body = new JsonObject();
        tracks.forEach(body::add);
        written.add("tracks", body);

        Path file = root.resolve("data").resolve(namespace).resolve("timeline")
            .resolve(name + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, written.toString(), StandardCharsets.UTF_8);
        return namespace + ":" + name;
    }

    /**
     * Adds the pack's timelines to the overworld's own tag.
     *
     * Tags merge across data packs unless one says otherwise, so this leaves the game's day, moon
     * and early game timelines where they are and appends the pack's behind them.
     */
    private static void writeTag(Path root, List<String> timelines) throws IOException {
        JsonArray values = new JsonArray();
        timelines.forEach(values::add);

        JsonObject tag = new JsonObject();
        tag.add("values", values);

        Path file = root.resolve(OVERWORLD_TIMELINES);
        Files.createDirectories(file.getParent());
        Files.writeString(file, tag.toString(), StandardCharsets.UTF_8);
    }
}
