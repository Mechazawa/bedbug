package at.ioexception.mcaddon.bedrock.ui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UiExpressionTest {
    private static final Map<String, Object> VALUES = Map.of("#title_text", "§c§h§e§s§tLoot", "$count", 3.0);

    private static Object evaluate(String expression) {
        return UiExpression.evaluate(expression, VALUES::get);
    }

    @Test
    void aTitleHoldingAMarkerIsToldFromOneWithout() {
        assertEquals(true, evaluate("(not ((#title_text - '§c§h§e§s§t') = #title_text))"));
        assertEquals(false, evaluate("(not ((#title_text - 'furnace') = #title_text))"));
    }

    @Test
    void takingAStringAwayCutsItOut() {
        assertEquals("Loot", evaluate("(#title_text - '§c§h§e§s§t')"));
    }

    @Test
    void aNumberAddedToAStringIsWrittenWhole() {
        assertEquals("Slots: 4", evaluate("('Slots: ' + ($count + 1))"));
    }

    @Test
    void andBindsTighterThanOr() {
        assertEquals(true, evaluate("(true or false and false)"));
        assertEquals(true, evaluate("($count > 2 and $count < 4)"));
    }
}
