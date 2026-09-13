package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Bedrock loot table is rewritten as, for the parts a running game cannot be asked about.
 *
 * A table the game refuses loads nothing and says so, but a table it accepts that carries one
 * dropped function drops the item without the enchantment and reports nothing, so the shape of the
 * output is what has to be checked here.
 */
class LootTableConversionTest {
    @TempDir
    Path root;

    @Test
    void readsAFunctionWrittenWithTheVanillaNamespaceAndOneWithout() throws IOException {
        JsonObject entry = firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:stick",
              "functions": [{"function": "minecraft:set_count", "count": 3},
                            {"function": "set_damage", "damage": 0.5}]}]}]}
            """);

        assertEquals(2, entry.getAsJsonArray("functions").size());
    }

    @Test
    void carriesTheConditionsOfAPoolAndOfAnEntryToTheSideEachBelongsOn() throws IOException {
        JsonObject pool = firstPool("""
            {"pools": [{"rolls": 1, "conditions": [{"condition": "killed_by_player"}],
              "entries": [{"type": "item", "name": "minecraft:stick",
                "conditions": [{"condition": "random_chance", "chance": 0.25}]}]}]}
            """);

        assertEquals(1, pool.getAsJsonArray("conditions").size());
        assertEquals("minecraft:killed_by_player", pool.getAsJsonArray("conditions").get(0)
            .getAsJsonObject().get("condition").getAsString());
        assertEquals(0.25, pool.getAsJsonArray("entries").get(0).getAsJsonObject()
            .getAsJsonArray("conditions").get(0).getAsJsonObject().get("chance").getAsDouble());
    }

    @Test
    void makesAnEmptyEntryTheJavaEmptyRatherThanReportingIt() throws IOException {
        assertEquals("minecraft:empty", firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "empty", "weight": 4}]}]}
            """).get("type").getAsString());
    }

    @Test
    void writesALevelRangeAsAUniformDistributionWhateverShapeItWasWrittenIn() throws IOException {
        JsonObject enchantments = firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:diamond_sword",
              "functions": [{"function": "specific_enchants",
                "enchants": [{"id": "sharpness", "level": [1, 3]}]}]}]}]}
            """).getAsJsonArray("functions").get(0).getAsJsonObject().getAsJsonObject("enchantments");

        JsonObject level = enchantments.getAsJsonObject("minecraft:sharpness");
        assertEquals("minecraft:uniform", level.get("type").getAsString());
        assertEquals(1, level.get("min").getAsInt());
        assertEquals(3, level.get("max").getAsInt());
    }

    @Test
    void dropsAFunctionNothingReadsRatherThanWritingOneTheGameWouldRefuse() throws IOException {
        JsonObject entry = firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:stick",
              "functions": [{"function": "set_data", "data": 4},
                            {"function": "set_count", "count": 2}]}]}]}
            """);

        assertEquals(1, entry.getAsJsonArray("functions").size());
        assertEquals("minecraft:set_count", entry.getAsJsonArray("functions").get(0)
            .getAsJsonObject().get("function").getAsString());
    }

    @Test
    void keepsAQualityAndABonusRollBecauseJavaSpellsBothTheSameWay() throws IOException {
        JsonObject pool = firstPool("""
            {"pools": [{"rolls": 1, "bonus_rolls": 2, "entries":
              [{"type": "item", "name": "minecraft:stick", "quality": 5}]}]}
            """);

        assertTrue(pool.has("bonus_rolls"));
        assertEquals(5, pool.getAsJsonArray("entries").get(0).getAsJsonObject()
            .get("quality").getAsInt());
        assertFalse(pool.has("tiers"));
    }

    private JsonObject firstPool(String table) throws IOException {
        return LootTableDefinition
            .readAll(BehaviourPackFixture.holding(root, "loot_tables/entities/probe.json", table))
            .get(0).java().getAsJsonArray("pools").get(0).getAsJsonObject();
    }

    private JsonObject firstEntry(String table) throws IOException {
        return firstPool(table).getAsJsonArray("entries").get(0).getAsJsonObject();
    }
}
