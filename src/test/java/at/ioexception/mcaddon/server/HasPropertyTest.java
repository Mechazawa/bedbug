package at.ioexception.mcaddon.server;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Bedrock's `has_property` selector argument asks of an entity's properties, read from just
 * after the `=` the way `SelectorTags` hands it over.
 */
class HasPropertyTest {
    private static boolean asks(String written, Map<String, Object> held) throws CommandSyntaxException {
        return HasProperty.read(new StringReader(written)).answeredBy(held::get);
    }

    @Test
    void readsEveryQuestionAndStopsWhereTheSelectorGoesOn() throws CommandSyntaxException {
        StringReader reader = new StringReader("{tp:mood=\"calm\", property=!tp:lit},tag=x]");
        HasProperty read = HasProperty.read(reader);

        assertEquals(new HasProperty.Question("tp:mood", "calm", false), read.questions().get(0));
        assertEquals(new HasProperty.Question("tp:lit", null, true), read.questions().get(1));
        assertEquals(",tag=x]", reader.getRemaining());
    }

    @Test
    void comparesABooleanAnEnumAndANumberEachAsItsOwnKind() throws CommandSyntaxException {
        Map<String, Object> held = Map.of("tp:lit", true, "tp:mood", "calm", "tp:level", 3.0);

        assertTrue(asks("{tp:lit=true,tp:mood=calm,tp:level=3}", held));
        assertFalse(asks("{tp:lit=false}", held));
        assertFalse(asks("{tp:mood=\"angry\"}", held));
        assertFalse(asks("{tp:level=4}", held));
    }

    @Test
    void readsBedrocksRangesOverWholeAndFractionalNumbers() throws CommandSyntaxException {
        Map<String, Object> held = Map.of("tp:level", 2.5);

        assertTrue(asks("{tp:level=2..3}", held));
        assertTrue(asks("{tp:level=..2.5}", held));
        assertTrue(asks("{tp:level=2..}", held));
        assertFalse(asks("{tp:level=3..}", held));
        assertFalse(asks("{tp:level=!2..3}", held));
    }

    @Test
    void turnsAValueQuestionRoundWithAnExclamationMark() throws CommandSyntaxException {
        Map<String, Object> held = Map.of("tp:mood", "calm");

        assertTrue(asks("{tp:mood=!\"angry\"}", held));
        assertFalse(asks("{tp:mood=!calm}", held));
    }

    /** An entity without a property answers no question about its value, however it is turned. */
    @Test
    void asksAfterPresenceOnlyThroughTheWordProperty() throws CommandSyntaxException {
        Map<String, Object> held = Map.of("tp:mood", "calm");

        assertTrue(asks("{property=tp:mood}", held));
        assertFalse(asks("{property=tp:lit}", held));
        assertTrue(asks("{property=!tp:lit}", held));
        assertFalse(asks("{tp:lit=true}", held));
        assertFalse(asks("{tp:lit=!true}", held));
    }
}
