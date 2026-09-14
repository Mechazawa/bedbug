package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.convert.Timelines;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic between a Bedrock settings file and the timeline it becomes.
 *
 * Nothing about a sky can be checked by counting files: a pack whose curves land an eighth of a day
 * early, or whose moon comes out four hundred times brighter than its sun, loads and registers
 * exactly like one that is right. Everything from the time of day to the written keyframe is a
 * function of its input, so it is asserted here.
 */
class LightingTimelineTest {

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** A lighting file carrying every field, with a sun and a moon a day apart in strength. */
    private static final String LIGHTING = """
        {
          "format_version": "1.21.80",
          "minecraft:lighting_settings": {
            "description": {"identifier": "minecraft:default_lighting"},
            "directional_lights": {
              "orbital": {
                "sun": {
                  "illuminance": {"0.00": 80.0, "0.25": 0.0, "0.50": 0.0, "0.75": 0.0},
                  "color": {"0.00": [255, 244, 214], "0.50": [255, 168, 96]}
                },
                "moon": {
                  "illuminance": {"0.00": 0.0, "0.25": 0.0, "0.50": 0.5, "0.75": 0.0},
                  "color": "#8fa8ff"
                },
                "orbital_offset_degrees": 12.0
              },
              "flash": {"illuminance": 8.0, "color": [228, 93, 255]}
            },
            "emissive": {"desaturation": 0.15},
            "ambient": {"color": "#2b3042", "illuminance": 0.03},
            "sky": {"intensity": 0.8}
          }
        }
        """;

    /** The same lights written the way a pack made before the `orbital` block put them. */
    private static final String FLAT_LIGHTING = """
        {
          "format_version": "1.21.70",
          "minecraft:lighting_settings": {
            "description": {"identifier": "tp:glade_lighting"},
            "directional_lights": {
              "sun": {"illuminance": 60.0, "color": [255, 238, 196]},
              "moon": {"illuminance": 0.35, "color": [150, 176, 255]}
            }
          }
        }
        """;

    private static final String ATMOSPHERE = """
        {
          "format_version": "1.21.40",
          "minecraft:atmosphere_settings": {
            "description": {"identifier": "minecraft:default_atmospherics"},
            "sky_zenith_color": {"0.00": [84, 134, 198], "0.50": [10, 12, 28]},
            "sky_horizon_color": {"0.25": "#f0a05a", "0.75": "#f0a05a"},
            "rayleigh_strength": 8.0,
            "sun_glare_shape": 0.05
          }
        }
        """;

    /**
     * Bedrock's day starts at noon and Java's at sunrise, so a time reaches Java a quarter later.
     *
     * These four are the corners the rest of a curve hangs off: getting the offset wrong moves every
     * sunset in a pack to midnight and nothing about the file would look wrong.
     */
    @Test
    void aTimeOfDayBecomesTheTickJavaCountsIt() {
        assertEquals(6000, Keyframes.tickOf(0f));
        assertEquals(12000, Keyframes.tickOf(0.25f));
        assertEquals(18000, Keyframes.tickOf(0.5f));
        assertEquals(0, Keyframes.tickOf(0.75f));
        assertEquals(6000, Keyframes.tickOf(1f));
    }

    /** Bedrock writes a colour as components or as hex, and both are the same three channels. */
    @Test
    void aColourIsReadInBothShapes() {
        assertEquals(List.of(255f, 168f, 96f),
            Keyframes.tryFrom(Json.parse("[255, 168, 96]")).at(0));
        assertEquals(List.of(255f, 168f, 96f),
            Keyframes.tryFrom(Json.parse("\"#ffa860\"")).at(0));
    }

    /** A bare value is a curve that never moves, and stands at every tick of the day. */
    @Test
    void aBareValueStandsAllDay() {
        Keyframes flat = Keyframes.tryFrom(Json.parse("0.8"));

        assertEquals(1, flat.stops().size());
        assertEquals(List.of(0.8f), flat.at(0));
        assertEquals(List.of(0.8f), flat.at(17000));
    }

    /** Between two stops a curve is straight, and the last stop runs into the first across midnight. */
    @Test
    void aCurveIsStraightBetweenItsStopsAndLoops() {
        // Stops at ticks 6000 and 18000.
        Keyframes curve = Keyframes.tryFrom(Json.parse("{\"0.0\": 0.0, \"0.5\": 12.0}"));

        assertEquals(6f, curve.at(12000).getFirst(), 1e-4);
        assertEquals(6f, curve.at(0).getFirst(), 1e-4);
        assertEquals(9f, curve.at(21000).getFirst(), 1e-4);
    }

    /**
     * The sun and the moon come out as one sky light, each measured against its own brightest.
     *
     * This is the whole of the fold: a file stating eighty lux of sun and half a lux of moon has to
     * give full daylight at noon and the fraction Java's own night runs at after dark, not a moon a
     * hundred and sixty times too dim to see.
     */
    @Test
    void theSunAndMoonFoldIntoOneSkyLight() {
        Keyframes level = lighting(LIGHTING).skyLightLevel();

        assertEquals(1f, level.at(Keyframes.tickOf(0f)).getFirst(), 1e-4);
        assertEquals(LightingDefinition.NIGHT_SKY_LIGHT, level.at(Keyframes.tickOf(0.5f)).getFirst(),
            1e-4);
        assertEquals(0f, level.at(Keyframes.tickOf(0.25f)).getFirst(), 1e-4);
    }

    /** The sky light takes whichever light is lighting it, and mixes the two where both are. */
    @Test
    void theSkyLightTakesTheColourOfWhicheverBodyIsUp() {
        Keyframes colour = lighting(LIGHTING).skyLightColour();

        assertEquals(List.of(255f, 244f, 214f), colour.at(Keyframes.tickOf(0f)));
        assertEquals(List.of(143f, 168f, 255f), colour.at(Keyframes.tickOf(0.5f)));
    }

    /** A pack that puts the sun and moon straight under `directional_lights` is read the same. */
    @Test
    void bothSpellingsOfTheOrbitalBlockAreRead() {
        LightingDefinition flat = lighting(FLAT_LIGHTING);

        assertNotNull(flat.sunIlluminance());
        assertNotNull(flat.moonColour());
        assertEquals(1f, flat.skyLightLevel().at(0).getFirst(), 1e-4);
    }

    /** Each converted field lands on the attribute that carries the same quantity, and no other. */
    @Test
    void aLightingFileDrivesTheSixAttributesJavaHas() {
        Map<String, JsonObject> tracks = Timelines.tracks(lighting(LIGHTING));

        assertEquals(List.of("minecraft:visual/ambient_light_color",
            "minecraft:visual/sky_light_factor", "minecraft:visual/sun_angle",
            "minecraft:visual/moon_angle", "minecraft:gameplay/sky_light_level",
            "minecraft:visual/sky_light_color"), List.copyOf(tracks.keySet()));

        assertEquals("#2b3042", value(tracks.get("minecraft:visual/ambient_light_color"), 0));
        assertEquals("multiply",
            tracks.get("minecraft:visual/sky_light_factor").get("modifier").getAsString());
        assertEquals("add", tracks.get("minecraft:visual/sun_angle").get("modifier").getAsString());
        assertEquals("add", tracks.get("minecraft:visual/moon_angle").get("modifier").getAsString());
    }

    /** An override carries no modifier at all, which is what a stated value means. */
    @Test
    void aStatedValueIsWrittenAsAnOverride() {
        Map<String, JsonObject> tracks = Timelines.tracks(lighting(LIGHTING));

        assertNull(tracks.get("minecraft:gameplay/sky_light_level").get("modifier"));
        assertNull(tracks.get("minecraft:visual/sky_light_color").get("modifier"));
    }

    /** A keyframe carries the tick Java counts it at, not the fraction Bedrock wrote. */
    @Test
    void aWrittenKeyframeStandsAtTheJavaTick() {
        JsonObject track = Timelines.tracks(lighting(LIGHTING))
            .get("minecraft:gameplay/sky_light_level");

        List<Integer> ticks = track.getAsJsonArray("keyframes").asList().stream()
            .map(one -> one.getAsJsonObject().get("ticks").getAsInt()).toList();
        assertEquals(List.of(0, 6000, 12000, 18000), ticks);
        assertEquals("linear", track.get("ease").getAsString());
    }

    /**
     * The horizon colour reaches the band Java paints around the sun, with an alpha of its own.
     *
     * Java's band is an ARGB colour whose alpha decides when it shows at all. Writing the pack's
     * colour opaque and multiplying it in leaves that decision where the game makes it.
     */
    @Test
    void theHorizonColourIsWrittenOpaqueAndMultipliedIn() {
        Map<String, JsonObject> tracks = Timelines.tracks(
            AtmosphereDefinition.tryFrom(Json.parse(ATMOSPHERE).getAsJsonObject()));

        assertEquals(List.of("minecraft:visual/sky_color",
            "minecraft:visual/sunrise_sunset_color"), List.copyOf(tracks.keySet()));

        JsonObject band = tracks.get("minecraft:visual/sunrise_sunset_color");
        assertEquals("multiply", band.get("modifier").getAsString());
        assertEquals("#fff0a05a", value(band, 0));
        assertNull(tracks.get("minecraft:visual/sky_color").get("modifier"));
        assertEquals("#5486c6", value(tracks.get("minecraft:visual/sky_color"), 0));
    }

    /** A file stating nothing a Java attribute carries writes no timeline at all. */
    @Test
    void anAtmosphereOfNothingButScatteringDrivesNoAttribute() {
        AtmosphereDefinition scattering = AtmosphereDefinition.tryFrom(Json.parse("""
            {
              "minecraft:atmosphere_settings": {
                "description": {"identifier": "tp:scattering"},
                "rayleigh_strength": 8.0
              }
            }
            """).getAsJsonObject());

        assertTrue(Timelines.tracks(scattering).isEmpty());
    }

    /**
     * Every setting Java has no attribute for is named, rather than dropped in silence.
     *
     * A pack author whose emissive desaturation or whose rayleigh strength does nothing has no way
     * to tell that from a pack that failed to load, which is what `/mcaddon entities` answers.
     */
    @Test
    void whatJavaHasNoAttributeForIsNamedAgainstThePack() {
        lighting(LIGHTING);
        AtmosphereDefinition.tryFrom(Json.parse(ATMOSPHERE).getAsJsonObject());

        assertNamed("lighting emissive");
        assertNamed("lighting ambient illuminance");
        assertNamed("lighting directional_lights flash");
        assertNamed("atmospherics rayleigh_strength");
        assertNamed("atmospherics sun_glare_shape");
    }

    /**
     * Only the settings Bedrock gives the whole world reach a timeline, and both halves land.
     *
     * A pack shipping a lighting per biome would otherwise stack every one of them on the same
     * world, which is neither what the pack wrote nor anything a player would recognise.
     */
    @Test
    void onlyTheWholeWorldSettingsBecomeTimelines(@TempDir Path root) throws IOException {
        Timelines.write(root, "tp",
            Map.of("minecraft:default_lighting", lighting(LIGHTING),
                "tp:glade_lighting", lighting(FLAT_LIGHTING)),
            Map.of("minecraft:default_atmospherics",
                AtmosphereDefinition.tryFrom(Json.parse(ATMOSPHERE).getAsJsonObject())));

        try (Stream<Path> written = Files.list(root.resolve("data/tp/timeline"))) {
            assertEquals(List.of("atmospherics.json", "lighting.json"),
                written.map(file -> file.getFileName().toString()).sorted().toList());
        }

        JsonObject tag = Json.parse(Files.readString(
            root.resolve("data/minecraft/tags/timeline/in_overworld.json"))).getAsJsonObject();
        assertEquals(List.of("tp:lighting", "tp:atmospherics"),
            tag.getAsJsonArray("values").asList().stream().map(one -> one.getAsString()).toList());
    }

    /**
     * A settings collection one biome names is named against the pack as it is read.
     *
     * The reading happens on every start and the writing only on a conversion, so putting the
     * naming here is what keeps a player who has played once before still being told.
     */
    @Test
    void aPerBiomeCollectionIsNamedAgainstThePack(@TempDir Path root) throws IOException {
        Map<String, LightingDefinition> found = LightingDefinition.readAll(packHolding(root,
            Map.of("lighting/global.json", LIGHTING, "lighting/glade.json", FLAT_LIGHTING)));

        assertEquals(2, found.size());
        assertNamed("lighting one biome names");
    }

    private static void assertNamed(String opening) {
        assertTrue(at.ioexception.mcaddon.registry.AddonEntities.report().stream()
                .anyMatch(line -> line.trim().startsWith(opening)),
            opening + " was dropped without being named.");
    }

    private static LightingDefinition lighting(String source) {
        return LightingDefinition.tryFrom(Json.parse(source).getAsJsonObject());
    }

    private static BedrockPack packHolding(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "00000000-0000-0000-0000-0000000000f9", "version": [1, 0, 0]},
              "modules": [{"type": "resources", "uuid": "00000000-0000-0000-0000-0000000000f8"}]
            }
            """).getAsJsonObject());
        return new BedrockPack(root, manifest, "test");
    }

    private static String value(JsonObject track, int keyframe) {
        return track.getAsJsonArray("keyframes").get(keyframe).getAsJsonObject()
            .get("value").getAsString();
    }
}
