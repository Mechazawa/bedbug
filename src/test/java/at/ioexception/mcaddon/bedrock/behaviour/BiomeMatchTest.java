package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Java biomes a spawn rule's biome filter allows.
 *
 * The two editions name biomes differently, so every answer here is a decision this mod makes: a
 * bare array means all of its tests, a tag with a Java counterpart is matched against the id of the
 * biome carrying the same idea, and a tag with none is allowed rather than refused, so a pack whose
 * rule mentions one keeps the rest of the filter. A pack's own biome is answered on its own tags.
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
        assertTrue(parse("{\"test\": \"has_biome_tag\", \"value\": \"mega\"}").matches("old_growth_spruce_taiga"));
        assertFalse(parse("{\"test\": \"has_biome_tag\", \"value\": \"mega\"}").matches("old_growth_birch_forest"));
    }

    @Test
    void aForestThatIsNotMutatedIsTheSetBedrocksOwnBiomesCarry() {
        BiomeMatch woods = parse("""
            [{"test": "has_biome_tag", "operator": "==", "value": "forest"},
             {"test": "has_biome_tag", "operator": "!=", "value": "mutated"}]""");

        List.of("forest", "birch_forest", "dark_forest", "taiga", "snowy_taiga", "old_growth_pine_taiga",
            "windswept_forest").forEach(biome -> assertTrue(woods.matches(biome), biome));
        List.of("flower_forest", "old_growth_birch_forest", "old_growth_spruce_taiga", "windswept_hills",
            "plains").forEach(biome -> assertFalse(woods.matches(biome), biome));
    }

    @Test
    void mutatedLeavesOutTheBiomesBedrockKeepsUnmutated() {
        BiomeMatch mutated = parse("{\"test\": \"has_biome_tag\", \"value\": \"mutated\"}");

        List.of("windswept_hills", "windswept_forest", "sparse_jungle", "bamboo_jungle", "old_growth_pine_taiga")
            .forEach(biome -> assertFalse(mutated.matches(biome), biome));
        List.of("windswept_gravelly_hills", "windswept_savanna", "sunflower_plains", "eroded_badlands")
            .forEach(biome -> assertTrue(mutated.matches(biome), biome));
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

    @Test
    void aPacksOwnBiomeIsAnsweredOnTheTagsItsPackGaveIt() {
        PackBiomes.declare("tp:ash_flats", List.of("nether", "ashen"));

        BiomeMatch ashen = parse("{\"test\": \"has_biome_tag\", \"value\": \"ashen\"}");
        assertTrue(ashen.allows("tp:ash_flats", () -> false));
        assertFalse(ashen.allows("minecraft:nether_wastes", () -> true));
        assertFalse(ashen.allows("minecraft:plains", () -> true));

        BiomeMatch overworld = parse("{\"test\": \"has_biome_tag\", \"value\": \"overworld\"}");
        assertFalse(overworld.allows("tp:ash_flats", () -> true));
        assertTrue(overworld.allows("minecraft:plains", () -> true));
    }

    private static BiomeMatch parse(String filter) {
        return BiomeMatch.parse(Json.parse(filter));
    }

    private static String world(String filter) {
        return BiomeMatch.world(Json.parse(filter));
    }
}
