package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic between a colour a pack writes and the integer a Java biome is drawn from.
 *
 * A colour is the one thing no log line and no count can report on: a pack with the right number of
 * fog files and the wrong value inside one of them counts exactly the same. Everything from the
 * `#rrggbb` string to the packed integer is a function of its input, so it is asserted here and the
 * pictures are left to say whether the integer reaches the screen.
 */
class BiomeColourTest {

    private static final String FOG = """
        {
          "format_version": "1.16.100",
          "minecraft:fog_settings": {
            "description": {"identifier": "tp:fog_glade"},
            "distance": {
              "air": {
                "fog_start": 0.3,
                "fog_end": 0.85,
                "fog_color": "#d2661e",
                "render_distance_type": "render"
              },
              "water": {
                "fog_start": 0.0,
                "fog_end": 18.0,
                "fog_color": "#1ed2a0",
                "render_distance_type": "fixed"
              },
              "lava": {"fog_color": "#991a00", "render_distance_type": "fixed"}
            },
            "volumetric": {"density": {"air": {"max_density": 0.05}}}
          }
        }
        """;

    private static final String CLIENT_BIOME = """
        {
          "format_version": "1.21.120",
          "minecraft:client_biome": {
            "description": {"identifier": "tp:glade"},
            "components": {
              "minecraft:water_appearance": {"surface_color": "#3f9bd4"},
              "minecraft:sky_color": {"sky_color": "#79a6ff"},
              "minecraft:fog_appearance": {"fog_identifier": "tp:fog_glade"}
            }
          }
        }
        """;

    private static final String WHOLE_PACK = """
        {
          "biomes": {
            "tp:cinder_wastes": {
              "water_surface_color": "#8a2f12",
              "water_fog_color": "#61200c",
              "water_fog_distance": 11,
              "fog_identifier": "tp:fog_glade"
            }
          }
        }
        """;

    @Test
    void aFogColourBecomesThePackedIntegerJavaDrawsFrom(@TempDir Path root) throws IOException {
        FogDefinition fog = FogDefinition.readAll(packHolding(root,
            Map.of("fogs/glade.fog.json", FOG))).get("tp:fog_glade");

        // The decimals a Java biome file carries for #d2661e and #1ed2a0.
        assertEquals(13788702, fog.air().colour());
        assertEquals(2020000, fog.water().colour());
    }

    /**
     * A distance stated as a fraction of the render distance has no value Java can be given, so it
     * has to come out absent rather than as the fraction read as blocks.
     */
    @Test
    void onlyADistanceInBlocksSurvives(@TempDir Path root) throws IOException {
        FogDefinition fog = FogDefinition.readAll(packHolding(root,
            Map.of("fogs/glade.fog.json", FOG))).get("tp:fog_glade");

        assertEquals(-1f, fog.air().start());
        assertEquals(-1f, fog.air().end());
        assertEquals(0f, fog.water().start());
        assertEquals(18f, fog.water().end());
        assertTrue(fog.water().painted());
    }

    @Test
    void aPerBiomeFileCarriesTheFogItNames(@TempDir Path root) throws IOException {
        ClientBiomeDefinition glade = ClientBiomeDefinition.readAll(packHolding(root, Map.of(
            "fogs/glade.fog.json", FOG,
            "biomes/glade.client_biome.json", CLIENT_BIOME))).get("tp:glade");

        assertEquals(4168660, glade.water());
        assertEquals(7972607, glade.sky());
        assertEquals(2020000, glade.fog().water().colour());
        assertEquals(18f, glade.fog().water().end());
    }

    /**
     * The whole-pack file states the water half itself and names a fog for the rest, and what it
     * states is what Bedrock draws.
     */
    @Test
    void theWholePackFileOverridesTheFogItNames(@TempDir Path root) throws IOException {
        ClientBiomeDefinition wastes = ClientBiomeDefinition.readAll(packHolding(root, Map.of(
            "fogs/glade.fog.json", FOG,
            "biomes_client.json", WHOLE_PACK))).get("tp:cinder_wastes");

        assertEquals(9056018, wastes.water());
        assertEquals(6365196, wastes.fog().water().colour());
        assertEquals(11f, wastes.fog().water().end());
        assertEquals(13788702, wastes.fog().air().colour());
    }

    /** The behaviour pack's own tints win over the resource pack's, and the fog is untouched. */
    @Test
    void aBehaviourPackTintWins() {
        ClientBiomeDefinition painted = new ClientBiomeDefinition(1, 2, 3, 4, 5,
            new FogDefinition(new FogDefinition.Location(6, -1, -1), FogDefinition.Location.NONE));

        assertEquals(7, painted.tinted(7, -1).grass());
        assertEquals(4, painted.tinted(7, -1).foliage());
        assertEquals(6, painted.tinted(7, -1).fog().air().colour());
    }

    private static BedrockPack packHolding(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "00000000-0000-0000-0000-0000000000fd", "version": [1, 0, 0]},
              "modules": [{"type": "resources", "uuid": "00000000-0000-0000-0000-0000000000fc"}]
            }
            """).getAsJsonObject());
        return new BedrockPack(root, manifest, "test");
    }
}
