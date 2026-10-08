package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The four shapes a curve takes, and the answer each gives for a given input.
 *
 * The cases are the points where the four differ from one another rather than a sweep of the whole
 * range: the ends, which every shape has to hit, and a middle where a straight line and a spline
 * part company.
 */
class ParticleCurveTest {
    private final MolangEnvironment environment = new MolangEnvironment();

    @ParameterizedTest
    @CsvSource({
        "0.0, 0.0",
        "0.25, 5.0",
        "0.5, 10.0",
        "1.0, 20.0",
    })
    void joinsLinearNodesWithStraightLines(float input, float expected) {
        assertEquals(expected, at("{\"type\": \"linear\", \"input\": " + input
            + ", \"nodes\": [0, 10, 20]}"), 0.0001f);
    }

    /** The input is measured against the range, so half the range is halfway along the nodes. */
    @Test
    void measuresTheInputAgainstTheHorizontalRange() {
        assertEquals(10f, at("{\"type\": \"linear\", \"input\": 2, \"horizontal_range\": 4,"
            + " \"nodes\": [0, 10, 20]}"), 0.0001f);
    }

    /** Anything past either end of the range reads as the node at that end. */
    @Test
    void holdsTheEndValueBeyondTheRange() {
        assertEquals(20f, at("{\"type\": \"linear\", \"input\": 9, \"nodes\": [0, 10, 20]}"), 0.0001f);
    }

    /**
     * A bezier starts and ends on its outer nodes and is pulled towards the middle two.
     *
     * Halfway along, the four rising values give 16.25 rather than the 20 a straight line between
     * the ends would, which is what tells the two shapes apart.
     */
    @ParameterizedTest
    @CsvSource({
        "0.0, 0.0",
        "0.5, 16.25",
        "1.0, 40.0",
    })
    void pullsABezierTowardsItsMiddleNodes(float input, float expected) {
        assertEquals(expected, at("{\"type\": \"bezier\", \"input\": " + input
            + ", \"nodes\": [0, 10, 20, 40]}"), 0.0001f);
    }

    /** A catmull-rom passes through every node but the first and the last. */
    @ParameterizedTest
    @CsvSource({
        "0.0, 10.0",
        "1.0, 20.0",
    })
    void runsACatmullRomThroughItsInnerNodes(float input, float expected) {
        assertEquals(expected, at("{\"type\": \"catmull_rom\", \"input\": " + input
            + ", \"nodes\": [0, 10, 20, 30]}"), 0.0001f);
    }

    @ParameterizedTest
    @CsvSource({
        "0.2, 4.0",
        "0.25, 4.0",
        "0.75, 8.0",
        "0.9, 8.0",
    })
    void readsAChainAtTheNodesItIsBuiltFrom(float input, float expected) {
        assertEquals(expected, at("{\"type\": \"bezier_chain\", \"input\": " + input + ", \"nodes\": {"
            + "\"0.25\": {\"value\": 4, \"slope\": 0}, \"0.75\": {\"value\": 8, \"slope\": 0}}}"),
            0.0001f);
    }

    /** A chain's nodes are keyed by the input itself, and Bedrock ignores a range written beside them. */
    @Test
    void readsAChainAtTheInputWhateverItsRange() {
        assertEquals(8f, at("{\"type\": \"bezier_chain\", \"input\": 0.75, \"horizontal_range\": 2, \"nodes\": {"
            + "\"0.25\": {\"value\": 4, \"slope\": 0}, \"0.75\": {\"value\": 8, \"slope\": 0}}}"),
            0.0001f);
    }

    /** A chain is sorted on the way in, because a pack may write its nodes in any order. */
    @Test
    void sortsAChainWrittenOutOfOrder() {
        assertEquals(4f, at("{\"type\": \"bezier_chain\", \"input\": 0.25, \"nodes\": {"
            + "\"0.75\": {\"value\": 8, \"slope\": 0}, \"0.25\": {\"value\": 4, \"slope\": 0}}}"),
            0.0001f);
    }

    /** Bedrock takes Molang anywhere it takes a number, nodes included. */
    @Test
    void readsANodeWrittenAsMolang() {
        environment.setVariable("peak", MolangValue.of(7));
        assertEquals(7f, at("{\"type\": \"linear\", \"input\": 1, \"nodes\": [0, \"v.peak\"]}"), 0.0001f);
    }

    @Test
    void readsNothingFromACurveWithNoNodes() {
        assertNull(ParticleCurve.tryFrom(Json.parse("{\"type\": \"linear\", \"input\": 1}")));
    }

    /** The variable a curve writes to is its own name, with the `variable.` prefix taken off. */
    @Test
    void namesEachCurveAfterTheVariableItWritesTo() {
        assertEquals(Set.of("size", "alpha"), ParticleCurve.readAll(Json.parse(
            "{\"variable.size\": {\"type\": \"linear\", \"input\": 0, \"nodes\": [1, 2]},"
                + " \"alpha\": {\"type\": \"linear\", \"input\": 0, \"nodes\": [1, 2]}}")
            .getAsJsonObject()).keySet());
    }

    private float at(String json) {
        return ParticleCurve.tryFrom(Json.parse(json)).at(environment);
    }
}
