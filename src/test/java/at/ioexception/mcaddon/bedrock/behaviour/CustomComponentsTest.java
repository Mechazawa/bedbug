package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of a definition's components are a pack's own, under either of the two spellings Bedrock
 * has had for them, and what each callback is handed.
 */
class CustomComponentsTest {
    @Test
    void readsTheListAnOlderDefinitionNamesAndTheKeysANewerOneWrites() {
        CustomComponents found = CustomComponents.of(components("""
            {"minecraft:custom_components": ["tp:old"],
             "minecraft:display_name": {"value": "x"},
             "tp:new": {"step": 3}}
            """));

        assertEquals(List.of("tp:old", "tp:new"), List.copyOf(found.params().keySet()));
        assertEquals(3, found.params().get("tp:new").getAsJsonObject().get("step").getAsInt());
    }

    @Test
    void handsEachCallbackItsOwnParametersBesideTheMomentsFields() {
        CustomComponents found = CustomComponents.of(components("""
            {"minecraft:custom_components": ["tp:old"], "tp:new": {"step": 3}}
            """));
        JsonObject fields = components("{\"player\": \"someone\"}");

        List<String> handed = new ArrayList<>();
        boolean anyCancelled = found.run(fields, (name, arguments) -> {
            handed.add(name + " " + arguments);
            return name.equals("tp:old");
        });

        assertTrue(anyCancelled);
        assertEquals(List.of("tp:old {\"player\":\"someone\",\"params\":{}}",
            "tp:new {\"player\":\"someone\",\"params\":{\"step\":3}}"), handed);
    }

    @Test
    void namesNoneWhereEveryComponentIsBedrocksOwn() {
        assertTrue(CustomComponents.of(components("{\"minecraft:icon\": \"dust\"}")).isEmpty());
    }

    private static JsonObject components(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
