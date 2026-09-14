package at.ioexception.mcaddon.bedrock.model;

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
}
