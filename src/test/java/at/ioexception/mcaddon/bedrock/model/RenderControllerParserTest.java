package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a render controller keeps of the order its rules were written in.
 *
 * `part_visibility` and `materials` both let a later rule answer for a bone an earlier one matched,
 * and Mojang writes the wildcard and the bones overriding it inside one rule object, so the order of
 * the keys inside a rule is as much of the meaning as the order of the rules themselves.
 */
class RenderControllerParserTest {
    private static RenderController read(String json) {
        return RenderControllerParser.read("controller.render.test",
            JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void keepsTheOrderOfTheBonesInsideOneRule() {
        RenderController controller = read("""
            { "part_visibility": [
                { "*": "0.0", "head": "1.0", "nose": "1.0", "body": "1.0" }
            ] }""");

        assertEquals(List.of("*", "head", "nose", "body"),
            List.copyOf(controller.partVisibility().get(0).keySet()));
    }

    @Test
    void keepsTheOrderOfTheRulesThemselves() {
        RenderController controller = read("""
            { "part_visibility": [ { "*": "0.0" }, { "flower": "1.0" } ] }""");

        assertEquals(List.of("*", "flower"), controller.partVisibility().stream()
            .flatMap(rule -> rule.keySet().stream()).toList());
    }

    @Test
    void keepsTheOrderOfTheBonesInsideOneMaterialRule() {
        RenderController controller = read("""
            { "materials": [ { "*": "Material.default", "hat": "Material.alpha" } ] }""");

        Map<String, ?> rule = controller.materials().get(0);
        assertEquals(List.of("*", "hat"), List.copyOf(rule.keySet()));
    }

    @Test
    void anArrayDeclaredInLowerCaseAnswersItsCapitalisedName() {
        RenderController controller = read("""
            { "arrays": { "textures": { "array.skins": [ "Texture.plain", "Texture.dyed" ] } },
              "textures": [ "Array.skins[1]" ] }""");
        MolangEnvironment environment = new MolangEnvironment();
        environment.setArrays(controller.arrays());

        assertEquals("dyed", controller.textures().get(0).evaluate(environment).text());
    }

    @Test
    void anIndexPastTheEndWrapsAndANegativeOneIsClamped() {
        RenderController controller = read("""
            { "arrays": { "textures": { "Array.skins": [ "Texture.a", "Texture.b", "Texture.c" ] } },
              "textures": [ "Array.skins[4]", "Array.skins[-2]", "Array.skins[2.9]" ] }""");
        MolangEnvironment environment = new MolangEnvironment();
        environment.setArrays(controller.arrays());

        assertEquals(List.of("b", "a", "c"), controller.textures().stream()
            .map(texture -> texture.evaluate(environment).text()).toList());
    }
}
