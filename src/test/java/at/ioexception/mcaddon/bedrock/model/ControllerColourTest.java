package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The `{r, g, b, a}` groups a render controller tints a pass with.
 *
 * `this` inside a channel means the value the pass would otherwise have drawn with, which is how a
 * pack switches one channel under a condition and leaves the rest alone.
 */
class ControllerColourTest {
    private static final int WHITE = 0xFFFFFFFF;

    @Test
    void isAbsentForAControllerThatDeclaresNoGroup() {
        assertNull(ControllerColour.tryFrom(Json.parse("{}").getAsJsonObject(), "color"));
    }

    @Test
    void packsTheFourChannelsIntoOneColour() {
        assertEquals(0x80FF0000, colour("""
            {"color": {"r": 1.0, "g": 0.0, "b": 0.0, "a": 0.501960784}}
            """).evaluate(new MolangEnvironment(), WHITE));
    }

    /** A channel a pack left out keeps whatever the pass already held. */
    @Test
    void leavesAnUndeclaredChannelWhereItWas() {
        assertEquals(0xFF00FFFF, colour("""
            {"color": {"r": 0.0}}
            """).evaluate(new MolangEnvironment(), WHITE));
    }

    @Test
    void readsThisAsTheValueThePassWouldHaveUsed() {
        MolangEnvironment environment = new MolangEnvironment();
        environment.setVariable("flashing", MolangValue.of(0));
        ControllerColour overlay = colour("""
            {"overlay_color": {"r": "variable.flashing ? 1.0 : this", "a": "variable.flashing ? 0.5 : this"}}
            """, "overlay_color");

        assertEquals(0, overlay.evaluate(environment, 0));

        environment.setVariable("flashing", MolangValue.of(1));
        assertEquals(0x80FF0000, overlay.evaluate(environment, 0));
    }

    @Test
    void evaluatesRedBeforeAlphaSoTemporariesCarryOver() {
        assertEquals(0x80FF0000, colour("""
            {"color": {"r": "{t.half = 0.501960784; return 1.0;}", "g": 0.0, "b": 0.0, "a": "t.half"}}
            """).evaluate(new MolangEnvironment(), WHITE));
    }

    private static ControllerColour colour(String body) {
        return colour(body, "color");
    }

    private static ControllerColour colour(String body, String field) {
        return ControllerColour.tryFrom(Json.parse(body).getAsJsonObject(), field);
    }
}
