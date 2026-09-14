package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One `lighting/` file, as much of it as Java's environment attributes carry.
 *
 * Bedrock lights a scene with two orbital lights and an ambient term, each stated in lux across the
 * day. Java has no lux anywhere: `EnvironmentAttributes` carries one sky light colour, one sky light
 * level and one ambient light colour, all of them fractions and colours. So the sun and the moon are
 * folded into that one sky light, each measured against the brightest the file itself makes it, and
 * the moon's share is scaled to the fraction Java's own night runs at.
 *
 * What is left over is named against the pack. `ambient.illuminance` is a strength in lux and
 * `AMBIENT_LIGHT_COLOR` is a colour with no luminance beside it. `emissive.desaturation` shapes the
 * light a Bedrock emissive pixel gives off, which this mod draws through a labPBR sidecar a shader
 * pack reads rather than through an attribute. `directional_lights.flash` colours the light a
 * lightning bolt throws, and Java draws that flash itself from no value a pack can state.
 */
public record LightingDefinition(String identifier, Keyframes ambientColour, Keyframes skyIntensity,
    Keyframes orbitalOffset, Keyframes sunIlluminance, Keyframes sunColour,
    Keyframes moonIlluminance, Keyframes moonColour) {

    /**
     * The fraction of full sky light Java's own night runs at.
     *
     * Bedrock's moon is stated in lux beside a sun hundreds of times stronger, and a sky light level
     * is a fraction of fifteen. Taking the moon's own curve as a fraction of itself and scaling it
     * here is what makes a file carrying Bedrock's own default curves come out at the level Java
     * already uses after dark.
     */
    public static final float NIGHT_SKY_LIGHT = 4f / 15f;

    /** The settings collection Bedrock gives every biome that names no other. */
    public static final String WHOLE_WORLD = "minecraft:default_lighting";

    /** What an unlit sky light is tinted with, which is no tint at all. */
    private static final List<Float> WHITE = List.of(255f, 255f, 255f);

    /** Every field a lighting file may carry, whether this converts it or names it. */
    private static final Set<String> KNOWN = Set.of("format_version", "description", "ambient",
        "directional_lights", "emissive", "sky");

    /** Every lighting settings collection the resource pack ships, by identifier. */
    public static Map<String, LightingDefinition> readAll(BedrockPack pack) {
        Map<String, LightingDefinition> found = new LinkedHashMap<>();

        for (Path file : pack.list("lighting", ".json")) {
            try {
                LightingDefinition lighting = tryFrom(Json.parseObject(file));
                if (lighting == null) continue;
                if (!lighting.wholeWorld()) {
                    AddonEntities.unsupported(
                        "lighting one biome names, which Java gives a dimension");
                }
                found.put(lighting.identifier(), lighting);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return found;
    }

    /** One file, or nothing where it carries no lighting settings collection. */
    public static LightingDefinition tryFrom(JsonObject root) {
        JsonObject body = root.getAsJsonObject("minecraft:lighting_settings");
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        body.keySet().stream().filter(name -> !KNOWN.contains(name))
            .forEach(name -> AddonEntities.unsupported("lighting " + name));
        if (body.has("emissive")) {
            AddonEntities.unsupported("lighting emissive, which shapes the light an emissive pixel "
                + "gives off and this mod draws through a labPBR sidecar instead");
        }

        JsonObject ambient = body.getAsJsonObject("ambient");
        if (ambient != null && ambient.has("illuminance")) {
            AddonEntities.unsupported("lighting ambient illuminance, which is a strength in lux "
                + "beside a colour Java states on its own");
        }

        JsonObject orbital = orbital(body.getAsJsonObject("directional_lights"));
        JsonObject sun = orbital == null ? null : orbital.getAsJsonObject("sun");
        JsonObject moon = orbital == null ? null : orbital.getAsJsonObject("moon");
        JsonObject sky = body.getAsJsonObject("sky");

        return new LightingDefinition(identifier,
            ambient == null ? null : Keyframes.tryFrom(ambient.get("color")),
            sky == null ? null : Keyframes.tryFrom(sky.get("intensity")),
            orbital == null ? null : Keyframes.tryFrom(orbital.get("orbital_offset_degrees")),
            sun == null ? null : Keyframes.tryFrom(sun.get("illuminance")),
            sun == null ? null : Keyframes.tryFrom(sun.get("color")),
            moon == null ? null : Keyframes.tryFrom(moon.get("illuminance")),
            moon == null ? null : Keyframes.tryFrom(moon.get("color")));
    }

    /**
     * Whether these settings light the whole world rather than one biome.
     *
     * Bedrock hands a biome the collection its `minecraft:lighting_identifier` names and this one to
     * every biome that names nothing, which is what makes this the only one with a world to reach:
     * Java hangs a timeline off a `DimensionType` and a biome carries constants alone.
     */
    public boolean wholeWorld() {
        return WHOLE_WORLD.equals(identifier);
    }

    /**
     * How much of Java's sky light the two orbital lights leave lit, across the day.
     *
     * Each light is measured against the brightest the file itself makes it, so a file carrying the
     * sun at a hundred lux and the moon at four tenths comes out full by day rather than four
     * hundred times too bright, and the two are combined by whichever is lighting the sky at that
     * moment.
     */
    public Keyframes skyLightLevel() {
        if (sunIlluminance == null && moonIlluminance == null) return null;

        float sunPeak = sunIlluminance == null ? 0 : sunIlluminance.peak();
        float moonPeak = moonIlluminance == null ? 0 : moonIlluminance.peak();
        return Keyframes.sampled(momentsOf(sunIlluminance, moonIlluminance),
            tick -> List.of(Math.max(fraction(sunIlluminance, sunPeak, tick),
                fraction(moonIlluminance, moonPeak, tick) * NIGHT_SKY_LIGHT)));
    }

    /**
     * What Java's one sky light is tinted with, across the day.
     *
     * The tint is the two lights' own colours mixed by how much each of them is lighting the sky, so
     * the sun's colour holds while the sun is up, the moon's takes over after dark and the two blend
     * through dusk. A moment with neither light on has no colour to mix and is left untinted.
     */
    public Keyframes skyLightColour() {
        if (sunColour == null && moonColour == null) return null;

        float sunPeak = sunIlluminance == null ? 0 : sunIlluminance.peak();
        float moonPeak = moonIlluminance == null ? 0 : moonIlluminance.peak();
        return Keyframes.sampled(
            momentsOf(sunIlluminance, moonIlluminance, sunColour, moonColour),
            tick -> mixed(fraction(sunIlluminance, sunPeak, tick),
                fraction(moonIlluminance, moonPeak, tick), tick));
    }

    private List<Float> mixed(float sunShare, float moonShare, int tick) {
        List<Float> sun = sunColour == null ? WHITE : sunColour.at(tick);
        List<Float> moon = moonColour == null ? WHITE : moonColour.at(tick);
        float total = sunShare + moonShare;
        if (total <= 0) return WHITE;

        List<Float> mixed = new ArrayList<>(sun.size());
        for (int channel = 0; channel < sun.size(); channel++) {
            mixed.add((sun.get(channel) * sunShare + moon.get(channel) * moonShare) / total);
        }
        return List.copyOf(mixed);
    }

    /**
     * How much of its own brightest a light is at one moment.
     *
     * A light a file never turns on has no brightest to measure against and gives nothing, which is
     * what keeps a lighting file stating only a sun from dividing by nothing for the moon.
     */
    private static float fraction(Keyframes illuminance, float peak, int tick) {
        if (illuminance == null || peak <= 0) return 0;

        return Math.clamp(illuminance.at(tick).getFirst() / peak, 0f, 1f);
    }

    /** Every tick any of these curves states something at, which is where a fold is worked out. */
    private static List<Integer> momentsOf(Keyframes... curves) {
        List<Integer> moments = new ArrayList<>();
        for (Keyframes curve : curves) {
            if (curve != null) moments.addAll(curve.times());
        }
        return moments;
    }

    /**
     * The block holding the sun and the moon, which a pack writes in either of two places.
     *
     * A file written for 1.21.80 or newer puts them under `orbital` beside the lightning flash, and
     * one written before that puts them straight under `directional_lights`.
     */
    private static JsonObject orbital(JsonObject lights) {
        if (lights == null) return null;

        if (lights.has("flash")) {
            AddonEntities.unsupported("lighting directional_lights flash, which colours the light a "
                + "lightning bolt throws and Java draws from no value a pack states");
        }

        JsonObject nested = lights.getAsJsonObject("orbital");
        return nested == null ? lights : nested;
    }
}
