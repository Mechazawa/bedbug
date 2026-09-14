package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One `atmospherics/` file, as much of it as Java's environment attributes carry.
 *
 * Bedrock draws its sky by scattering light through an atmosphere: a rayleigh term, a mie term per
 * orbital light, a glare lobe and a set of heights the terms blend between. Java draws no atmosphere
 * at all. Its sky is the two colours `SKY_COLOR` and `SUNRISE_SUNSET_COLOR` are given, so the two
 * colours this file states land and the five terms that would feed a scattering model are named
 * against the pack.
 *
 * The horizon colour lands on the band Java paints around the sun rather than on the whole horizon,
 * because the horizon itself is the fog colour in Java and that is already what a pack's own `fogs/`
 * files state.
 */
public record AtmosphereDefinition(String identifier, Keyframes zenithColour,
    Keyframes horizonColour) {

    /** The settings collection Bedrock gives every biome that names no other. */
    public static final String WHOLE_WORLD = "minecraft:default_atmospherics";

    /** Every field an atmospherics file may carry, whether this converts it or names it. */
    private static final Set<String> KNOWN = Set.of("format_version", "description",
        "sky_zenith_color", "sky_horizon_color", "horizon_blend_stops", "rayleigh_strength",
        "sun_mie_strength", "moon_mie_strength", "sun_glare_shape");

    /** The terms of the scattering model Java's sky is not drawn with. */
    private static final List<String> SCATTERING = List.of("horizon_blend_stops",
        "rayleigh_strength", "sun_mie_strength", "moon_mie_strength", "sun_glare_shape");

    /** Every atmospheric settings collection the resource pack ships, by identifier. */
    public static Map<String, AtmosphereDefinition> readAll(BedrockPack pack) {
        Map<String, AtmosphereDefinition> found = new LinkedHashMap<>();

        for (Path file : pack.list("atmospherics", ".json")) {
            try {
                AtmosphereDefinition atmosphere = tryFrom(Json.parseObject(file));
                if (atmosphere == null) continue;
                if (!atmosphere.wholeWorld()) {
                    AddonEntities.unsupported(
                        "atmospherics one biome names, which Java gives a dimension");
                }
                found.put(atmosphere.identifier(), atmosphere);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return found;
    }

    /** One file, or nothing where it carries no atmospheric settings collection. */
    public static AtmosphereDefinition tryFrom(JsonObject root) {
        JsonObject body = root.getAsJsonObject("minecraft:atmosphere_settings");
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        body.keySet().stream().filter(name -> !KNOWN.contains(name))
            .forEach(name -> AddonEntities.unsupported("atmospherics " + name));
        SCATTERING.stream().filter(body::has).forEach(name -> AddonEntities.unsupported(
            "atmospherics " + name + ", a term of the scattering model Java draws no sky with"));

        return new AtmosphereDefinition(identifier, Keyframes.tryFrom(body.get("sky_zenith_color")),
            Keyframes.tryFrom(body.get("sky_horizon_color")));
    }

    /**
     * Whether these settings paint the whole world's sky rather than one biome's.
     *
     * Bedrock hands a biome the collection its `minecraft:atmosphere_identifier` names and this one
     * to every biome that names nothing, which is what makes this the only one with a world to
     * reach: Java hangs a timeline off a `DimensionType` and a biome carries constants alone.
     */
    public boolean wholeWorld() {
        return WHOLE_WORLD.equals(identifier);
    }
}
