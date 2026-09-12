package at.ioexception.mcaddon;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Molang answers, for the cases where Bedrock and an ordinary expression language disagree.
 *
 * Molang never throws: a bad expression evaluates to zero. That makes "it parsed" worthless as a
 * measurement, so every case here states the answer it must give.
 */
class MolangTest {
    private final MolangEnvironment environment = new MolangEnvironment();

    private double evaluate(String source) {
        return MolangParser.parse(source).asFloat(environment);
    }

    @ParameterizedTest
    @CsvSource({
        "'1 + 2 * 3', 7",
        "'2 < 3 && 3 <= 3', 1",
        "'!0', 1",
        "'math.floor(-1.5)', -2",
        "'math.trunc(-1.5)', -1",
        "'math.clamp(5, 0, 1)', 1",
        "'math.copy_sign(3, -1)', -3",
    })
    void answersArithmetic(String source, double expected) {
        assertEquals(expected, evaluate(source), 1e-6, source);
    }

    /** Bedrock measures angles in degrees, unlike every maths library the parser could borrow. */
    @ParameterizedTest
    @CsvSource({"'math.sin(90)', 1", "'math.cos(0)', 1", "'math.sin(0)', 0"})
    void measuresAnglesInDegrees(String source, double expected) {
        assertEquals(expected, evaluate(source), 1e-6, source);
    }

    @Test
    void knowsPi() {
        assertEquals(Math.PI, evaluate("math.pi"), 1e-6);
    }

    /** Dividing by zero is a zero rather than an error, which is what a pack relies on. */
    @ParameterizedTest
    @CsvSource({"'1 / 0', 0", "'math.mod(7, 0)', 0", "'q.missing_query', 0", "'0 ? 1', 0"})
    void answersZeroRatherThanFailing(String source, double expected) {
        assertEquals(expected, evaluate(source), 1e-6, source);
    }

    /**
     * `??` falls through on a variable that was never set, and not on one holding zero.
     *
     * Testing truthiness instead would turn every `v.x ?? 5` on a zeroed variable into a 5, which is
     * the difference between a hidden bone and a visible one.
     */
    @Test
    void nullCoalesceOnlyFallsThroughOnUnset() {
        assertEquals(5, evaluate("v.never_set ?? 5"), 1e-6);
        assertEquals(0, evaluate("{v.zero = 0; return v.zero ?? 5;}"), 1e-6);
    }

    @Test
    void readsMemberNamesWithoutCase() {
        assertEquals(4, evaluate("{v.Mixed = 4; return v.mixed;}"), 1e-6);
    }

    @Test
    void runsLoops() {
        assertEquals(8, evaluate("{v.total = 0; loop(4, {v.total = v.total + 2;}); return v.total;}"), 1e-6);
    }

    /** A loop that never ends has to stop anyway, because a pack's frame cannot wait for it. */
    @Test
    void stopsARunawayLoop() {
        assertEquals(1024, evaluate("{v.n = 0; loop(100000, {v.n = v.n + 1;}); return v.n;}"), 1e-6);
    }

    @Test
    void readsThroughAnArrow() {
        assertEquals(0, evaluate("c.owning_entity->v.anything"), 1e-6);
    }

    @Test
    void comparesStrings() {
        assertEquals(1, evaluate("'a' == 'a'"), 1e-6);
        assertEquals(0, evaluate("'a' == 1"), 1e-6);
    }

    @Test
    void runsStatementBlocks() {
        assertEquals(6, evaluate("{v.a = 3; return v.a * 2;}"), 1e-6);
    }

    /** A broken expression is a zero and never an exception, however badly it is written. */
    @ParameterizedTest
    @CsvSource({"'((('", "'v.'", "'math.'", "'1 +'", "''", "'q.x(('", "'{'", "'loop('", "'v.x['"})
    void survivesNonsense(String source) {
        assertEquals(0, evaluate(source), 1e-6, source);
    }

    /**
     * A mistake makes the whole value zero, and not the part in front of it.
     *
     * Each of these reads as a valid expression up to the mistake, so a parser that answered with
     * what it managed to read would hand back a plausible number. A bone placed by half an
     * expression is worse than one left where the model put it, which is why unconsumed input counts
     * as a failure rather than as the end of the value.
     */
    @ParameterizedTest
    @CsvSource({"'1 + 2 @ 3'", "'2 + 2 garbage'", "'3 )'", "'v.x = '", "'1; @'"})
    void answersZeroForAnExpressionWithAMistakeAfterAValidStart(String source) {
        assertEquals(0, evaluate(source), 1e-6, source);
    }

    @Test
    void keepsVariablesBetweenExpressions() {
        MolangParser.parse("v.kept = 12").evaluate(environment);
        assertTrue(environment.hasVariable("kept"));
        assertEquals(12, evaluate("v.kept"), 1e-6);
    }
}
