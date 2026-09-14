package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Java biomes a spawn rule's biome filter allows.
 *
 * The two editions name biomes differently, so every answer here is a decision this mod makes: a
 * bare array means all of its tests, a tag with a Java counterpart is matched against the id of the
 * biome carrying the same idea, and a tag with none is allowed rather than refused, so a pack whose
 * rule mentions one keeps the rest of the filter.
 */
class BiomeMatchTest {
    @Test
    void abareArrayAsksForEveryTestInIt() {
        BiomeMatch turtle = parse("""
            [{"test": "has_biome_tag", "operator": "==", "value": "beach"},
             {"test": "has_biome_tag", "operator": "==", "value": "warm"}]""");

        assertTrue(turtle.matches("beach"));
        assertFalse(turtle.matches("snowy_beach"));
        assertFalse(turtle.matches("desert"));
    }

    @Test
    void anyOfAsksForOneOfThem() {
        BiomeMatch bee = parse("""
            {"any_of": [{"test": "has_biome_tag", "value": "plains"},
                        {"test": "has_biome_tag", "value": "flower_forest"}]}""");

        assertTrue(bee.matches("sunflower_plains"));
        assertTrue(bee.matches("flower_forest"));
        assertFalse(bee.matches("desert"));
    }

    @Test
    void anExcludedTagRefusesTheBiomesCarryingIt() {
        BiomeMatch away = parse(
            "{\"test\": \"has_biome_tag\", \"operator\": \"!=\", \"value\": \"ocean\"}");

        assertFalse(away.matches("warm_ocean"));
        assertTrue(away.matches("plains"));
    }

    @Test
    void aTagBedrockSpellsDifferentlyReachesTheJavaBiome() {
        assertTrue(parse("{\"test\": \"has_biome_tag\", \"value\": \"mesa\"}").matches("eroded_badlands"));
        assertTrue(parse("{\"test\": \"has_biome_tag\", \"value\": \"mooshroom_island\"}")
            .matches("mushroom_fields"));
        assertTrue(parse("{\"test\": \"has_biome_tag\", \"value\": \"roofed\"}").matches("dark_forest"));
    }

    @Test
    void aTagWithNoJavaCounterpartLeavesTheRestOfTheFilterDeciding() {
        BiomeMatch zombie = parse("""
            {"all_of": [{"test": "has_biome_tag", "value": "sulfur_caves"},
                        {"test": "has_biome_tag", "value": "taiga"}]}""");

        assertTrue(zombie.matches("snowy_taiga"));
        assertFalse(zombie.matches("plains"));
    }

    @Test
    void theWorldComesFromATagNamingOneAndNotFromATagExcludingIt() {
        assertEquals("nether", world("{\"test\": \"has_biome_tag\", \"value\": \"nether\"}"));
        assertEquals("end", world("{\"test\": \"has_biome_tag\", \"value\": \"the_end\"}"));
        assertEquals("overworld", world("""
            {"all_of": [{"test": "has_biome_tag", "operator": "!=", "value": "nether"},
                        {"test": "has_biome_tag", "operator": "!=", "value": "the_end"}]}"""));
    }

    private static BiomeMatch parse(String filter) {
        return BiomeMatch.parse(Json.parse(filter));
    }

    private static String world(String filter) {
        return BiomeMatch.world(Json.parse(filter));
    }
}
