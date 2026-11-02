package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStateQueriesTest {

    private static BlockDefinition lamp(String condition) {
        JsonObject lit = new JsonObject();
        lit.addProperty("minecraft:light_emission", 15);
        return new BlockDefinition("tp:lamp", Map.of(), null, false, new JsonObject(),
            Map.of("tp:lit", List.of(new JsonPrimitive(false), new JsonPrimitive(true))),
            List.of(new BlockDefinition.Permutation(condition, lit)), new JsonObject(), new JsonObject());
    }

    private static boolean lights(BlockDefinition block, boolean lit) {
        Map<String, JsonElement> chosen = Map.of("tp:lit", new JsonPrimitive(lit));
        return block.componentsFor(chosen).has("minecraft:light_emission");
    }

    @Test
    void aPermutationReadsItsStateThroughTheOlderPropertyName() {
        BlockDefinition block = lamp("query.block_property('tp:lit')");
        assertTrue(lights(block, true));
        assertFalse(lights(block, false));
    }

    @Test
    void aStateTheBlockDeclaresIsOneItHas() {
        assertTrue(lights(lamp("q.has_block_state('tp:lit')"), false));
        assertTrue(lights(lamp("q.has_block_property('tp:lit')"), false));
        assertFalse(lights(lamp("q.has_block_state('tp:dim')"), true));
    }

    @Test
    void aStateReadsAsTheValueItHolds() {
        BlockStateQueries queries = BlockStateQueries.of(Map.of("tp:uses", new JsonPrimitive(2)));
        assertEquals(2, MolangParser.parse("q.block_state('tp:uses')")
            .evaluate(queries.environment()).number());
    }
}
