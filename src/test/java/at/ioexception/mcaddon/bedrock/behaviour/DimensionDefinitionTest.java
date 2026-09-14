package at.ioexception.mcaddon.bedrock.behaviour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The band of height a Bedrock dimension states, as the pair Java's dimension type takes.
 *
 * Bedrock writes the band in two spellings and the two do not agree about the second number:
 * `minecraft:dimension_bounds` gives the ceiling and `minecraft:dimension_height` gives how tall
 * the band is. Java takes the height in both cases, so reading a ceiling as a height makes a
 * dimension that is its own floor too tall or too short and nothing about either number says which
 * it arrived as.
 */
class DimensionDefinitionTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({
        // The ceiling is the ceiling: 320 above a floor of -64 is 384 of height, not 320.
        "-64, 320, -64, 384",
        "0, 128, 0, 128",
        "-512, 512, -512, 1024",
        "64, 320, 64, 256",
        // A ceiling at or under the floor leaves no band, and Java's shortest is one section.
        "0, 0, 0, 16",
        "32, -32, 32, 16",
        // Java counts in whole chunk sections, so a band written between two is read to the one
        // below it.
        "-70, 313, -80, 384",
    })
    void aCeilingBecomesAHeight(int min, int max, int expectedFloor, int expectedHeight)
        throws IOException {
        DimensionDefinition read = onlyOne("""
            {
              "format_version": "1.21.60",
              "minecraft:dimension": {
                "description": {"identifier": "tp:band"},
                "components": {
                  "minecraft:dimension_bounds": {"min": %d, "max": %d},
                  "minecraft:generation": {"generator_type": "void"}
                }
              }
            }
            """.formatted(min, max));

        assertEquals(expectedFloor, read.minimumY());
        assertEquals(expectedHeight, read.height());
    }

    @ParameterizedTest
    @CsvSource({
        "-64, 384, -64, 384",
        "0, 128, 0, 128",
        // A band taller than the world above its floor is cut to what is left above it.
        "2016, 1024, 2016, 16",
    })
    void aHeightRangeIsTakenAsItStands(int minimumY, int range, int expectedFloor, int expectedHeight)
        throws IOException {
        DimensionDefinition read = onlyOne("""
            {
              "format_version": "1.21.90",
              "minecraft:dimension": {
                "description": {"identifier": "tp:band"},
                "components": {
                  "minecraft:dimension_height": {"min_y": %d, "height_range": %d},
                  "minecraft:generation": {"generator_type": "void"}
                }
              }
            }
            """.formatted(minimumY, range));

        assertEquals(expectedFloor, read.minimumY());
        assertEquals(expectedHeight, read.height());
    }

    @Test
    void aDimensionStatingNoBandTakesTheOverworldsOwn() throws IOException {
        DimensionDefinition read = onlyOne("""
            {
              "format_version": "1.21.60",
              "minecraft:dimension": {
                "description": {"identifier": "tp:band"},
                "components": {"minecraft:generation": {"generator_type": "void"}}
              }
            }
            """);

        assertEquals(DimensionDefinition.DEFAULT_MINIMUM_Y, read.minimumY());
        assertEquals(DimensionDefinition.DEFAULT_HEIGHT, read.height());
    }

    @Test
    void onlyTheVoidGeneratorConverts() throws IOException {
        assertTrue(onlyOne(generator("void")).convertible());
        assertFalse(onlyOne(generator("overworld")).convertible());
    }

    @Test
    void aBiomeOutOfThePackItselfIsNotNamedInADimension() throws IOException {
        assertEquals("minecraft:plains", onlyOne(biome("minecraft:plains")).biome());
        assertEquals("minecraft:plains", onlyOne(biome("plains")).biome());
        assertEquals("minecraft:the_void", onlyOne(biome("tp:ashfield")).biome());
    }

    private static String generator(String type) {
        return """
            {
              "format_version": "1.21.60",
              "minecraft:dimension": {
                "description": {"identifier": "tp:band"},
                "components": {"minecraft:generation": {"generator_type": "%s"}}
              }
            }
            """.formatted(type);
    }

    private static String biome(String named) {
        return """
            {
              "format_version": "1.21.90",
              "minecraft:dimension": {
                "description": {"identifier": "tp:band"},
                "components": {
                  "minecraft:default_biome": {"biome": "%s"},
                  "minecraft:generation": {"generator_type": "void"}
                }
              }
            }
            """.formatted(named);
    }

    private DimensionDefinition onlyOne(String body) throws IOException {
        List<DimensionDefinition> read = DimensionDefinition.readAll(
            BehaviourPackFixture.holding(directory, "dimensions/band.json", body));
        assertEquals(1, read.size());
        return read.get(0);
    }
}
