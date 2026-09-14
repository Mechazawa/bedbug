package at.ioexception.mcaddon.bedrock.fog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a pack's `fogs/*.json` becomes.
 *
 * The two distances mean different things depending on one word in the file, and the colour is
 * written in two shapes, so a file read the wrong way puts the fog either on the player's nose or
 * past the horizon with nothing saying which.
 */
class FogDefinitionTest {
    private static final float TOLERANCE = 1e-4f;

    @TempDir
    Path directory;

    @Test
    void readsTheAirFogAPackDescribes() throws IOException {
        FogDefinition fog = read("""
            {
              "format_version": "1.16.100",
              "minecraft:fog_settings": {
                "description": {"identifier": "test:dusk"},
                "distance": {
                  "air": {
                    "fog_start": 0.2,
                    "fog_end": 0.8,
                    "fog_color": "#FF8000",
                    "render_distance_type": "render"
                  }
                }
              }
            }
            """);

        assertEquals("test:dusk", fog.identifier());
        assertEquals(1f, fog.red(), TOLERANCE);
        assertEquals(0.5019f, fog.green(), TOLERANCE);
        assertEquals(0f, fog.blue(), TOLERANCE);
        assertTrue(fog.scaledToRenderDistance());
    }

    /** `render` makes the distances a multiple of the render distance; `fixed` makes them blocks. */
    @Test
    void resolvesTheDistancesAgainstTheRenderDistanceOnlyWhenAsked() throws IOException {
        FogDefinition scaled = read(file("test:scaled", "0.25", "0.75", "render"));
        assertEquals(32f, scaled.startAt(128f), TOLERANCE);
        assertEquals(96f, scaled.endAt(128f), TOLERANCE);

        FogDefinition fixed = read(file("test:fixed", "8", "40", "fixed"));
        assertEquals(8f, fixed.startAt(128f), TOLERANCE);
        assertEquals(40f, fixed.endAt(128f), TOLERANCE);
    }

    /** A colour written as three numbers is a fraction of full, which is what the format's own are. */
    @Test
    void readsAColourWrittenAsNumbers() throws IOException {
        FogDefinition fog = read("""
            {
              "minecraft:fog_settings": {
                "description": {"identifier": "test:numbers"},
                "distance": {"air": {"fog_color": [0.25, 0.5, 1.0]}}
              }
            }
            """);

        assertEquals(0.25f, fog.red(), TOLERANCE);
        assertEquals(0.5f, fog.green(), TOLERANCE);
        assertEquals(1f, fog.blue(), TOLERANCE);
    }

    @Test
    void readsNothingFromAFileThatNamesNoAirFog() throws IOException {
        assertNull(read("""
            {
              "minecraft:fog_settings": {
                "description": {"identifier": "test:water_only"},
                "distance": {"water": {"fog_start": 0.1, "fog_end": 0.4}}
              }
            }
            """));
        assertNull(read("{\"format_version\": \"1.16.100\"}"));
    }

    @Test
    void survivesTheWire() throws IOException {
        FogDefinition sent = read(file("test:sent", "0.1", "0.9", "fixed"));

        assertEquals(sent, FogDefinition.decode(sent.encode()));
        assertNull(FogDefinition.decode(""));
    }

    private static String file(String identifier, String start, String end, String type) {
        return "{\"minecraft:fog_settings\": {\"description\": {\"identifier\": \"" + identifier
            + "\"}, \"distance\": {\"air\": {\"fog_start\": " + start + ", \"fog_end\": " + end
            + ", \"fog_color\": \"#102030\", \"render_distance_type\": \"" + type + "\"}}}}";
    }

    private FogDefinition read(String source) throws IOException {
        Path file = directory.resolve("fog.json");
        Files.writeString(file, source);
        return FogDefinition.tryFrom(file);
    }
}
