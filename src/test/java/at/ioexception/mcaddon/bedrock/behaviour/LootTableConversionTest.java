package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
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
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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

    @Test
    void namesEachStewEffectByTheJavaEffectItsNumberStandsFor() throws IOException {
        JsonArray effects = firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:suspicious_stew",
              "functions": [{"function": "set_stew_effect",
                "effects": [{"id": 0}, {"id": 4, "duration": 6}]}]}]}]}
            """).getAsJsonArray("effects");

        assertEquals("minecraft:night_vision", effects.get(0).getAsJsonObject()
            .get("type").getAsString());
        assertEquals(5, effects.get(0).getAsJsonObject().get("duration").getAsInt());
        assertEquals("minecraft:poison", effects.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals(6, effects.get(1).getAsJsonObject().get("duration").getAsInt());
    }

    @Test
    void dropsAStewEffectNumberNoEffectAnswersToRatherThanNamingTheWrongOne() throws IOException {
        assertFalse(firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:suspicious_stew",
              "functions": [{"function": "set_stew_effect", "effects": [{"id": 40}]}]}]}]}
            """).has("functions"));
    }

    @Test
    void givesABannerTheEightLayersOfTheOneAnIllagerCaptainCarries() throws IOException {
        JsonArray patterns = firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:white_banner",
              "functions": [{"function": "set_banner_details", "type": 1}]}]}]}
            """).getAsJsonArray("patterns");

        assertEquals(8, patterns.size());
        assertEquals("minecraft:rhombus", patterns.get(0).getAsJsonObject()
            .get("pattern").getAsString());
        assertEquals("cyan", patterns.get(0).getAsJsonObject().get("color").getAsString());
    }

    @Test
    void writesNoPatternForTheBannerTypeThatMeansAPlainOne() throws IOException {
        assertFalse(firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:white_banner",
              "functions": [{"function": "set_banner_details", "type": 0}]}]}]}
            """).has("functions"));
    }

    @Test
    void reportsATrimNoSupportedVersionShipsRatherThanNamingARegistryEntryWithNothingBehindIt()
        throws IOException {
        assertFalse(firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:iron_helmet",
              "functions": [{"function": "set_armor_trim",
                "material": "copper", "pattern": "invented"}]}]}]}
            """).has("functions"));
    }

    @Test
    void guardsTheGearEnchantmentWithTheChanceTheDifficultyScales() throws IOException {
        JsonObject function = firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:golden_helmet",
              "functions": [{"function": "enchant_random_gear", "chance": 0.25}]}]}]}
            """);

        assertEquals("minecraft:enchant_with_levels", function.get("function").getAsString());
        assertEquals(BedrockCondition.NAME, function.getAsJsonArray("conditions").get(0)
            .getAsJsonObject().get("condition").getAsString());
    }

    @Test
    void keepsTheBedrockObjectOfAConditionThisModAnswersAsTheLootIsRolled() throws IOException {
        JsonObject condition = firstPool("""
            {"pools": [{"rolls": 1, "conditions": [{"condition": "has_mark_variant", "value": 3}],
              "entries": [{"type": "item", "name": "minecraft:stick"}]}]}
            """).getAsJsonArray("conditions").get(0).getAsJsonObject();

        assertEquals(BedrockCondition.NAME, condition.get("condition").getAsString());
        assertTrue(condition.get("bedrock").getAsString().contains("has_mark_variant"));
    }

    /**
     * `entity_killed` asks what the mob the table belongs to just swallowed.
     *
     * Bedrock names such a table on `minecraft:behavior.eat_mob` rather than on `minecraft:loot`,
     * so it is rolled as the meal goes down and the mob doing the killing is the loot's own entity.
     */
    @Test
    void keepsTheMealAConditionAsksAbout() throws IOException {
        JsonObject condition = firstPool("""
            {"pools": [{"rolls": 1, "conditions": [{"condition": "entity_killed",
              "entity_type": "minecraft:slime"}],
              "entries": [{"type": "item", "name": "minecraft:slime_ball"}]}]}
            """).getAsJsonArray("conditions").get(0).getAsJsonObject();

        assertEquals(BedrockCondition.NAME, condition.get("condition").getAsString());
        assertTrue(condition.get("bedrock").getAsString().contains("minecraft:slime"));
    }

    private JsonObject firstFunction(String table) throws IOException {
        return firstEntry(table).getAsJsonArray("functions").get(0).getAsJsonObject();
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
