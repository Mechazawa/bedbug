package at.ioexception.mcaddon.bedrock.behaviour;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one path rule that decides whether a pack's mobs drop anything.
 *
 * The conversion writes a table to this path and an entity's `minecraft:loot` reaches for it by the
 * same one, so a change to either half that the other does not follow costs every pack its drops
 * while the server still starts, the entity still spawns and nothing is reported.
 */
class LootTableDefinitionTest {
    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "loot_tables/entities/reporter.json -> entities/reporter",
        "entities/reporter.json             -> entities/reporter",
        "loot_tables/Entities/Big Cow.json  -> entities/big_cow",
        "loot_tables/entities/reporter      -> entities/reporter",
    })
    void turnsAPackRelativeTablePathIntoTheOneJavaFilesItUnder(String bedrock, String expected) {
        assertEquals(expected, LootTableDefinition.javaPath(bedrock.strip()));
    }
}
