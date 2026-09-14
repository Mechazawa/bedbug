package at.ioexception.mcaddon.bedrock.camera;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The curves `/camera ... ease` names.
 *
 * Bedrock writes each curve three times, once per direction, and this derives the other two from the
 * one that starts slowly. That derivation is what these cover: a curve that does not reach one at the
 * end, or a direction composed the wrong way round, moves the camera to somewhere nobody asked for
 * and nothing else would say so.
 */
class EasingTest {
    private static final float TOLERANCE = 1e-4f;

    static List<String> names() {
        return Easing.names();
    }

    @ParameterizedTest
    @MethodSource("names")
    void startsAtZeroAndEndsAtOne(String name) {
        Easing easing = Easing.tryFrom(name);
        assertNotNull(easing, name);
        assertEquals(0f, easing.at(0f), TOLERANCE, name);
        assertEquals(1f, easing.at(1f), TOLERANCE, name);
    }

    @ParameterizedTest
    @MethodSource("names")
    void readsBackTheNameItWrites(String name) {
        assertEquals(name, Easing.tryFrom(name).bedrockName());
    }

    @ParameterizedTest
    @CsvSource({
        "linear, 0.3, 0.3",
        "in_quad, 0.5, 0.25",
        "out_quad, 0.5, 0.75",
        "in_out_quad, 0.25, 0.125",
        "in_out_quad, 0.5, 0.5",
        "in_out_quad, 0.75, 0.875",
        "in_cubic, 0.5, 0.125",
        "out_cubic, 0.5, 0.875",
    })
    void followsTheCurveItIsNamedAfter(String name, float progress, float expected) {
        assertEquals(expected, Easing.tryFrom(name).at(progress), TOLERANCE);
    }

    /** A frame can arrive after the ease was due to end, and one before it started is possible too. */
    @Test
    void holdsBothEndsBeyondTheirRange() {
        assertEquals(0f, Easing.tryFrom("in_out_bounce").at(-1f), TOLERANCE);
        assertEquals(1f, Easing.tryFrom("in_out_bounce").at(2f), TOLERANCE);
    }

    @Test
    void answersNothingForANameBedrockHasNotGot() {
        assertNull(Easing.tryFrom("in_out_wobble"));
        assertNull(Easing.tryFrom("quad"));
        assertNull(Easing.tryFrom("in_linear"));
    }
}
