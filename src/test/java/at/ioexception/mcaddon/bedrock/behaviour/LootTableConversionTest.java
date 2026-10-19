package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

        assertEquals(2, functions(entry).size());
    }

    @Test
    void carriesTheConditionsOfAPoolAndOfAnEntryToTheSideEachBelongsOn() throws IOException {
        JsonObject pool = firstPool("""
            {"pools": [{"rolls": 1, "conditions": [{"condition": "killed_by_player"}],
              "entries": [{"type": "item", "name": "minecraft:stick",
                "conditions": [{"condition": "random_chance", "chance": 0.25}]}]}]}
            """);

        assertEquals(1, conditions(pool).size());
        assertEquals("minecraft:killed_by_player", conditionType(conditions(pool).get(0)));
        assertEquals(0.25, conditions(pool.getAsJsonArray("entries").get(0).getAsJsonObject())
            .get(0).getAsJsonObject().get("chance").getAsDouble());
    }

    @Test
    void makesAnEmptyEntryTheJavaEmptyRatherThanReportingIt() throws IOException {
        assertEquals("minecraft:empty", firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "empty", "weight": 4}]}]}
            """).get("type").getAsString());
    }

    @Test
    void writesALevelRangeAsAUniformDistributionWhateverShapeItWasWrittenIn() throws IOException {
        JsonObject enchantments = firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:diamond_sword",
              "functions": [{"function": "specific_enchants",
                "enchants": [{"id": "sharpness", "level": [1, 3]}]}]}]}]}
            """).getAsJsonObject("enchantments");

        JsonObject level = enchantments.getAsJsonObject("minecraft:sharpness");
        assertEquals("minecraft:uniform", level.get("type").getAsString());
        assertEquals(1, level.get("min").getAsInt());
        assertEquals(3, level.get("max").getAsInt());
    }

    @Test
    void dropsAFunctionNothingReadsRatherThanWritingOneTheGameWouldRefuse() throws IOException {
        JsonObject entry = firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:stick",
              "functions": [{"function": "random_block_state", "block_state": "facing"},
                            {"function": "set_count", "count": 2}]}]}]}
            """);

        assertEquals(1, functions(entry).size());
        assertEquals("minecraft:set_count", functionType(functions(entry).get(0)));
    }

    /**
     * A stated data value picks the item outright, and nothing is left of the function.
     *
     * Java has one id per item, so the pair has to be settled before the table is written: a Java
     * table naming `minecraft:dye` is one the game refuses to read at all.
     */
    @Test
    void namesTheItemAStatedDataValuePicksAndLeavesNoFunctionBehind() throws IOException {
        JsonObject entry = firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:dye",
              "functions": [{"function": "set_data", "data": 4}]}]}]}
            """);

        assertEquals("minecraft:lapis_lazuli", entry.get("name").getAsString());
        assertFalse(hasFunctions(entry));
    }

    /** A Bedrock id with no data value beside it means the first of what Java split it into. */
    @Test
    void readsABedrockIdWithNoDataValueAsTheFirstOfItsFamily() throws IOException {
        assertEquals("minecraft:white_wool", firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:wool"}]}]}
            """).get("name").getAsString());
    }

    /**
     * A range is picked as the table is rolled, out of the items the conversion worked out.
     *
     * The entry names the first of them so the game will read the table at all, and the list
     * travels with the function because a Java id no longer says which family it came out of.
     */
    @Test
    void carriesWhatARangeOfDataValuesCouldNameToTheLootRoll() throws IOException {
        JsonObject entry = firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:wool",
              "functions": [{"function": "random_aux_value", "values": {"min": 1, "max": 3}}]}]}]}
            """);

        assertEquals("minecraft:orange_wool", entry.get("name").getAsString());
        JsonObject function = functions(entry).get(0).getAsJsonObject();
        assertEquals(BedrockFunction.NAME, functionType(function));
        assertTrue(function.get("bedrock").getAsString().contains("minecraft:magenta_wool"));
    }

    /** A colour index counts along the whole family, so every place in it has to be kept. */
    @Test
    void carriesEverySixteenthOfAColourIndexedFamilyToTheLootRoll() throws IOException {
        JsonObject function = firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:wool",
              "functions": [{"function": "minecraft:set_data_from_color_index"}]}]}]}
            """);

        assertEquals(BedrockFunction.NAME, functionType(function));
        assertEquals(16, JsonParser.parseString(function.get("bedrock").getAsString())
            .getAsJsonObject().getAsJsonArray("items").size());
    }

    /**
     * A stew keeps one id and carries the effect its number picked inside the stack.
     *
     * Java's own function takes a list and picks one of it as the stack is made, which is the same
     * thing Bedrock says with a range, so a stated number is that list with one effect on it.
     */
    @Test
    void turnsAStewDataValueIntoTheEffectJavaCarriesInsideTheStack() throws IOException {
        JsonObject function = firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:suspicious_stew",
              "functions": [{"function": "set_data", "data": 4}]}]}]}
            """);

        assertEquals("minecraft:set_stew_effect", functionType(function));
        assertEquals(1, function.getAsJsonArray("effects").size());
        assertEquals("minecraft:poison", function.getAsJsonArray("effects").get(0)
            .getAsJsonObject().get("type").getAsString());
    }

    @Test
    void listsEveryEffectARangeOverTheStewNumbersCouldLandOn() throws IOException {
        assertEquals(7, firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:suspicious_stew",
              "functions": [{"function": "random_aux_value", "values": {"min": 0, "max": 6}}]}]}]}
            """).getAsJsonArray("effects").size());
    }

    /**
     * The four goat horns a range covers are the ones Java ships a tag of.
     *
     * Java keeps all eight under one id and picks between them by a tag of instruments, so the
     * numbers Bedrock gives them are answerable where they line up with one.
     */
    @Test
    void picksAGoatHornOutOfTheTagCoveringTheValuesTheRangeRuns() throws IOException {
        JsonObject function = firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:goat_horn",
              "functions": [{"function": "set_data", "data": {"min": 0, "max": 3}}]}]}]}
            """);

        assertEquals("minecraft:set_instrument", functionType(function));
        assertEquals("#minecraft:regular_goat_horns", function.get("options").getAsString());
    }

    /** A data value nothing answers leaves the item the pack named and reports itself. */
    @Test
    void keepsTheItemWhenTheDataValueBesideItNamesNothing() throws IOException {
        JsonObject entry = firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:oak_sign",
              "functions": [{"function": "set_data", "data": 1}]}]}]}
            """);

        assertEquals("minecraft:oak_sign", entry.get("name").getAsString());
        assertFalse(hasFunctions(entry));
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
        assertFalse(hasFunctions(firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:suspicious_stew",
              "functions": [{"function": "set_stew_effect", "effects": [{"id": 40}]}]}]}]}
            """)));
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
        assertFalse(hasFunctions(firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:white_banner",
              "functions": [{"function": "set_banner_details", "type": 0}]}]}]}
            """)));
    }

    @Test
    void reportsATrimNoSupportedVersionShipsRatherThanNamingARegistryEntryWithNothingBehindIt()
        throws IOException {
        assertFalse(hasFunctions(firstEntry("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:iron_helmet",
              "functions": [{"function": "set_armor_trim",
                "material": "copper", "pattern": "invented"}]}]}]}
            """)));
    }

    @Test
    void guardsTheGearEnchantmentWithTheChanceTheDifficultyScales() throws IOException {
        JsonObject function = firstFunction("""
            {"pools": [{"rolls": 1, "entries": [{"type": "item", "name": "minecraft:golden_helmet",
              "functions": [{"function": "enchant_random_gear", "chance": 0.25}]}]}]}
            """);

        assertEquals("minecraft:enchant_with_levels", functionType(function));
        assertEquals(BedrockCondition.NAME, conditionType(conditions(function).get(0)));
    }

    @Test
    void keepsTheBedrockObjectOfAConditionThisModAnswersAsTheLootIsRolled() throws IOException {
        JsonObject condition = conditions(firstPool("""
            {"pools": [{"rolls": 1, "conditions": [{"condition": "has_mark_variant", "value": 3}],
              "entries": [{"type": "item", "name": "minecraft:stick"}]}]}
            """)).get(0).getAsJsonObject();

        assertEquals(BedrockCondition.NAME, conditionType(condition));
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
        JsonObject condition = conditions(firstPool("""
            {"pools": [{"rolls": 1, "conditions": [{"condition": "entity_killed",
              "entity_type": "minecraft:slime"}],
              "entries": [{"type": "item", "name": "minecraft:slime_ball"}]}]}
            """)).get(0).getAsJsonObject();

        assertEquals(BedrockCondition.NAME, conditionType(condition));
        assertTrue(condition.get("bedrock").getAsString().contains("minecraft:slime"));
    }

    private JsonObject firstFunction(String table) throws IOException {
        return functions(firstEntry(table)).get(0).getAsJsonObject();
    }

    private static boolean hasFunctions(JsonObject holder) {
        return !functions(holder).isEmpty();
    }

    private static JsonArray functions(JsonObject holder) {
        //? if >=26.3 {
        /*return holder.has("modifier") ? holder.getAsJsonArray("modifier") : new JsonArray();
        *///?} else {
        return holder.has("functions") ? holder.getAsJsonArray("functions") : new JsonArray();
        //?}
    }

    private static String functionType(JsonElement function) {
        //? if >=26.3 {
        /*return function.getAsJsonObject().get("type").getAsString();
        *///?} else {
        return function.getAsJsonObject().get("function").getAsString();
        //?}
    }

    private static JsonArray conditions(JsonObject holder) {
        //? if >=26.3 {
        /*if (!holder.has("condition")) return new JsonArray();
        JsonObject condition = holder.getAsJsonObject("condition");
        if (!conditionType(condition).equals("minecraft:all_of")) {
            JsonArray single = new JsonArray();
            single.add(condition);
            return single;
        }
        return condition.getAsJsonArray("terms");
        *///?} else {
        return holder.has("conditions") ? holder.getAsJsonArray("conditions") : new JsonArray();
        //?}
    }

    private static String conditionType(JsonElement condition) {
        //? if >=26.3 {
        /*return condition.getAsJsonObject().get("type").getAsString();
        *///?} else {
        return condition.getAsJsonObject().get("condition").getAsString();
        //?}
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
