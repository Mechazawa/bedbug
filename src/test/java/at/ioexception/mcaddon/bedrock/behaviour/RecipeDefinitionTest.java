package at.ioexception.mcaddon.bedrock.behaviour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a recipe file is read as, for the parts that decide which Java recipe it becomes.
 *
 * A recipe read as the wrong kind, or one whose tag reference is read as an item id, is written to
 * the data pack and then matches nothing, which no log line reports.
 */
class RecipeDefinitionTest {
    @TempDir
    Path root;

    @Test
    void readsATagReferenceAsATagRatherThanAsAnItemName() throws IOException {
        RecipeDefinition recipe = read("""
            {"minecraft:recipe_shaped": {
              "description": {"identifier": "probe:plank_block"},
              "tags": ["crafting_table"],
              "pattern": ["##", "##"],
              "key": {"#": {"tag": "minecraft:planks"}},
              "result": {"item": "probe:plank_block", "count": 1}}}
            """);

        assertEquals("#minecraft:planks", recipe.key().get("#"));
    }

    @Test
    void readsAnUnlockListAsTheItemsAndTagsAnAdvancementWaitsForAPlayerToHold() throws IOException {
        RecipeDefinition recipe = read("""
            {"minecraft:recipe_shaped": {
              "description": {"identifier": "probe:plank_block"},
              "tags": ["crafting_table"],
              "pattern": ["##", "##"],
              "key": {"#": {"item": "minecraft:oak_planks"}},
              "unlock": [{"tag": "minecraft:planks"}, {"item": "minecraft:stick"}],
              "result": {"item": "probe:plank_block", "count": 1}}}
            """);

        assertEquals(2, recipe.unlock().size());
        assertEquals("#minecraft:planks", recipe.unlock().get(0));
        assertEquals("minecraft:stick", recipe.unlock().get(1));
    }

    @Test
    void readsATagAShapelessRecipeIsUnlockedByAsATag() throws IOException {
        RecipeDefinition recipe = read("""
            {"minecraft:recipe_shapeless": {
              "description": {"identifier": "probe:cut"},
              "tags": ["crafting_table"],
              "ingredients": [{"item": "probe:raw"}],
              "unlock": [{"tag": "minecraft:logs"}],
              "result": {"item": "probe:cut", "count": 1}}}
            """);

        assertEquals(java.util.List.of("#minecraft:logs"), recipe.unlock());
    }

    @Test
    void leavesAnUnlockStatedAsAGameEventWithNothingToWaitFor() throws IOException {
        RecipeDefinition recipe = read("""
            {"minecraft:recipe_shapeless": {
              "description": {"identifier": "probe:cut"},
              "tags": ["crafting_table"],
              "ingredients": [{"item": "probe:raw"}],
              "unlock": {"context": "PlayerInWater"},
              "result": {"item": "probe:cut", "count": 1}}}
            """);

        assertEquals(0, recipe.unlock().size());
    }

    @Test
    void keepsEveryCookerAFurnaceRecipeNames() throws IOException {
        RecipeDefinition recipe = read("""
            {"minecraft:recipe_furnace": {
              "description": {"identifier": "probe:baked"},
              "tags": ["furnace", "smoker", "campfire", "soul_campfire"],
              "input": "probe:raw",
              "output": "probe:baked"}}
            """);

        assertEquals(RecipeDefinition.Kind.FURNACE, recipe.kind());
        assertEquals(4, recipe.tags().size());
        assertEquals("probe:raw", recipe.ingredients().get(0).item());
    }

    @Test
    void readsTheThreeInputsOfASmithingTransform() throws IOException {
        RecipeDefinition recipe = read("""
            {"minecraft:recipe_smithing_transform": {
              "description": {"identifier": "probe:upgrade"},
              "tags": ["smithing_table"],
              "template": "minecraft:netherite_upgrade_smithing_template",
              "base": "probe:blade",
              "addition": "minecraft:netherite_ingot",
              "result": "probe:better_blade"}}
            """);

        assertEquals(RecipeDefinition.Kind.SMITHING_TRANSFORM, recipe.kind());
        assertEquals("probe:blade", recipe.smithing().base());
        assertEquals("minecraft:netherite_ingot", recipe.smithing().addition());
        assertEquals("probe:better_blade", recipe.result());
    }

    @Test
    void readsASmithingTrimWhichNamesNoResultOfItsOwn() throws IOException {
        RecipeDefinition recipe = read("""
            {"minecraft:recipe_smithing_trim": {
              "description": {"identifier": "probe:trim"},
              "tags": ["smithing_table"],
              "template": "minecraft:bolt_armor_trim_smithing_template",
              "base": {"tag": "minecraft:trimmable_armor"},
              "addition": {"tag": "minecraft:trim_materials"}}}
            """);

        assertEquals(RecipeDefinition.Kind.SMITHING_TRIM, recipe.kind());
        assertEquals("#minecraft:trimmable_armor", recipe.smithing().base());
        assertNull(recipe.result());
    }

    @Test
    void carriesTheGroupARecipeShares() throws IOException {
        assertEquals("probe_planks", read("""
            {"minecraft:recipe_shapeless": {
              "description": {"identifier": "probe:planks"},
              "tags": ["crafting_table"],
              "group": "probe_planks",
              "ingredients": [{"item": "probe:log"}],
              "result": {"item": "probe:planks", "count": 4}}}
            """).group());
    }

    private RecipeDefinition read(String recipe) throws IOException {
        return RecipeDefinition
            .readAll(BehaviourPackFixture.holding(root, "recipes/probe.json", recipe)).get(0);
    }
}
