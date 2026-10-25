package at.ioexception.mcaddon;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Every easing runs from its first argument to its second, the way `math.lerp` does.
     *
     * Reversing the two ends is invisible at the midpoint and on a curve that starts where it
     * finishes, so the cases here are asymmetric and read at the ends.
     */
    @ParameterizedTest
    @CsvSource({
        "'math.ease_in_quad(10, 20, 0)', 10",
        "'math.ease_in_quad(10, 20, 1)', 20",
        "'math.ease_in_quad(10, 20, 0.5)', 12.5",
        "'math.ease_out_quad(10, 20, 0.5)', 17.5",
        "'math.ease_out_expo(0, 100, 0)', 0",
        "'math.ease_in_out_cubic(0, 8, 0.25)', 0.5",
        "'math.inverse_lerp(10, 20, 12.5)', 0.25",
    })
    void easesFromTheFirstArgumentToTheSecond(String source, double expected) {
        assertEquals(expected, evaluate(source), 1e-6, source);
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

    /**
     * A query is asked by its own name, and the member a pack names after it is read off the struct
     * it answers with, whether the member follows the name or the call.
     */
    @Test
    void readsAStructMemberOffWhatAQueryAnswers() {
        List<String> asked = new ArrayList<>();
        environment.setQueries((name, arguments) -> {
            asked.add(name);
            return MolangValue.struct(Map.of("r", MolangValue.of(2),
                "max", MolangValue.struct(Map.of("y", MolangValue.of(arguments.size())))));
        });

        assertEquals(3, evaluate("q.surface_particle_color.R + q.bone_aabb('leg1a').max.y"), 1e-6);
        assertEquals(List.of("surface_particle_color", "bone_aabb"), asked);
        assertEquals(0, evaluate("q.bone_aabb('leg1a').min.y"), 1e-6);
    }

    @Test
    void keepsAStructAQueryAnsweredWith() {
        environment.setQueries((name, arguments) -> MolangValue.struct(Map.of(
            "r", MolangValue.struct(Map.of("x", MolangValue.of(30))))));

        assertEquals(30, evaluate("v.pose = q.bone_orientation_trs('head'); return v.pose.r.x;"), 1e-6);
    }

    /** Mojang's own examples, less the arrows, which need an entity at the other end. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "v.test.a.b.c = 1.23; v.moo = v.test; return v.moo.a.b.c;",
        "v.test.a.b.c = 1.23; v.moo = v.test.a; return v.moo.b.c;",
        "v.test.a.b.c = 1.23; v.moo = v.test.a.b; return v.moo.c;",
        "v.test.a.b.c = 1.23; v.moo = v.test.a.b.c; return v.moo;",
        "t.test.a.b.c = 1.23; return t.test.A.b.C;",
    })
    void buildsStructsByUse(String source) {
        assertEquals(1.23, evaluate(source), 1e-6, source);
    }

    @Test
    void copiesAStructByAssignment() {
        assertEquals(1, evaluate("v.a.x = 1; v.b = v.a; v.a.x = 2; return v.b.x;"), 1e-6);
        assertEquals(2, evaluate("v.a.x"), 1e-6);
    }

    @Test
    void readsAMissingMemberAsZero() {
        assertEquals(0, evaluate("v.a.x = 1; return v.a.y + v.a.x.z + v.never.x;"), 1e-6);
        assertEquals(5, evaluate("v.a.y ?? 5"), 1e-6);
        assertEquals(1, evaluate("v.a.x ?? 5"), 1e-6);
    }

    /** How a pack declares a struct before any query fills it: each level set to zero, then its members. */
    @Test
    void turnsANumberIntoAStructWhenAMemberIsSet() {
        assertEquals(3, evaluate("v.pose = 0; v.pose.t = 0; v.pose.t.x = 3; return v.pose.t.x;"), 1e-6);
        assertEquals(0, evaluate("v.pose"), 1e-6);
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

    /** An attachable sees the variables its wearer published and nothing else it holds. */
    @Test
    void readsOnlyThePublishedVariablesOfALinkedEntity() {
        MolangEnvironment wearer = new MolangEnvironment();
        wearer.setVariable("attack_time", MolangValue.of(3));
        wearer.setVariable("secret", MolangValue.of(7));

        MolangEnvironment attachable = new MolangEnvironment();
        MolangEnvironment published = wearer.publishedAs(Set.of("attack_time"));
        attachable.setContext("owning_entity", MolangValue.of(() -> published));

        assertEquals(3, MolangParser.parse("c.owning_entity->v.attack_time")
            .asFloat(attachable), 1e-6);
        assertEquals(0, MolangParser.parse("c.owning_entity->v.secret").asFloat(attachable), 1e-6);
    }

    /** Mojang's own struct examples, each of which answers 1.23 after the same two statements. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "return v.cowcow.friend->v.test.a.b.c;",
        "v.moo = v.cowcow.friend->v.test; return v.moo.a.b.c;",
        "v.moo = v.cowcow.friend->v.test.a; return v.moo.b.c;",
        "v.moo = v.cowcow.friend->v.test.a.b; return v.moo.c;",
        "v.moo = v.cowcow.friend->v.test.a.b.c; return v.moo;",
    })
    void readsAndWritesThroughAnEntityAStructMemberHolds(String ending) {
        Entity pig = new Entity("minecraft:pig");
        environment.setVariable("pigpig", MolangValue.of(pig));
        String source = "v.cowcow.friend = v.pigpig; v.pigpig->v.test.a.b.c = 1.23; " + ending;
        assertEquals(1.23, evaluate(source), 1e-6, source);
        assertEquals(1.23, pig.scope.variable("test").member(List.of("a", "b", "c")).number(), 1e-6);
    }

    /** The arrow asks the entity on its left, so the same query answers for whoever is named there. */
    @Test
    void asksTheEntityOnTheLeftOfAnArrow() {
        Entity pig = new Entity("minecraft:pig");
        pig.scope.setQueries((name, arguments) -> MolangValue.of(name.equals("is_baby") ? 1 : 0));
        environment.setVariable("pig", MolangValue.of(pig));
        assertEquals(1, evaluate("v.pig->q.is_baby"), 1e-6);
        assertEquals(0, evaluate("q.is_baby"), 1e-6);
        assertEquals(3, evaluate("1 + 2 * v.pig->q.is_baby"), 1e-6);
    }

    @Test
    void answersZeroThroughAnEntityThatIsGone() {
        Entity pig = new Entity("minecraft:pig");
        pig.scope.setVariable("x", MolangValue.of(5));
        environment.setVariable("pig", MolangValue.of(pig));
        assertEquals(5, evaluate("v.pig->v.x"), 1e-6);

        pig.gone = true;
        assertEquals(0, evaluate("v.pig->v.x"), 1e-6);
        assertEquals(0, evaluate("v.pig ? 1 : 0"), 1e-6);
        assertEquals(1.2, evaluate("v.pig ?? 1.2"), 1e-6);
    }

    /** Mojang's own example: count the pigs near, and add up what each of them answers. */
    @Test
    void runsABlockOncePerEntityOfAnArray() {
        List<Entity> pigs = List.of(new Entity("minecraft:pig"), new Entity("minecraft:pig"),
            new Entity("minecraft:pig"));
        for (int i = 0; i < pigs.size(); i++) {
            double flammable = i == 1 ? 0 : 1;
            pigs.get(i).scope.setQueries((name, arguments) -> MolangValue.of(flammable));
        }
        environment.setQueries((name, arguments) -> name.equals("get_nearby_entities")
            ? MolangValue.of(pigs) : MolangValue.ZERO);

        assertEquals(3, evaluate("v.x = 0; for_each(t.pig, query.get_nearby_entities(4, 'minecraft:pig'), {"
            + " v.x = v.x + 1; }); return v.x;"), 1e-6);
        assertEquals(2, evaluate("v.x = 0; for_each(v.pig, query.get_nearby_entities(4, 'minecraft:pig'), {"
            + " v.x = v.x + v.pig->query.get_relative_block_state(0, 1, 0, 'flammable'); }); return v.x;"), 1e-6);
    }

    @Test
    void combinesAndCountsEntities() {
        Entity cow = new Entity("minecraft:cow");
        Entity pig = new Entity("minecraft:pig");
        Entity gone = new Entity("minecraft:sheep");
        gone.gone = true;
        environment.setVariable("cow", MolangValue.of(cow));
        environment.setVariable("both", MolangValue.of(List.of(cow, pig)));
        environment.setVariable("gone", MolangValue.of(gone));

        List<MolangValue> combined = List.of(environment.variable("both"), environment.variable("cow"),
            environment.variable("gone"));
        MolangValue array = MolangValue.combined(combined);
        assertEquals(List.of(cow, pig), array.entities());
        assertEquals(2, MolangValue.count(List.of(array)));
        assertEquals(4, MolangValue.count(List.of(array, MolangValue.of(7), MolangValue.of(cow))));
    }

    /** A stand-in for an entity: a scope of its own, and a switch that makes it gone. */
    private static final class Entity implements MolangValue.Reference {
        private final MolangEnvironment scope = new MolangEnvironment();
        private final String identifier;
        private boolean gone;

        Entity(String identifier) {
            this.identifier = identifier;
        }

        @Override
        public MolangEnvironment scope() {
            return gone ? null : scope;
        }

        @Override
        public String toString() {
            return identifier;
        }
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

    /** A `return` inside a block ends the whole expression, which is how a pack picks one of several textures. */
    @ParameterizedTest
    @CsvSource({"0, 1", "1, 2", "2, 3"})
    void returnsFromInsideABlock(double style, double expected) {
        environment.setVariable("style", MolangValue.of(style));
        assertEquals(expected, evaluate("v.style == 1 ? {return 2;}; v.style == 2 ? {return 3;}; return 1;"), 1e-6);
    }

    @Test
    void returnsFromInsideALoop() {
        assertEquals(3, evaluate("v.n = 0; loop(10, {v.n = v.n + 1; v.n == 3 ? {return v.n;};}); return 0;"), 1e-6);
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

    /** Both compare the first argument against the rest, so a value in the list is what makes `any` true. */
    @Test
    void anyAndAllCompareAgainstTheFirstArgument() {
        environment.setQueries((name, arguments) -> MolangValue.of(
            name.equals("any") ? MolangValue.matchesAny(arguments) : MolangValue.matchesAll(arguments)));

        assertEquals(1, evaluate("q.any(4, 0, 4, 5)"), 1e-6);
        assertEquals(0, evaluate("q.any(4, 0, 2, 5)"), 1e-6);
        assertEquals(0, evaluate("q.any(0, 1, 2)"), 1e-6);
        assertEquals(1, evaluate("q.all(3, 3, 3)"), 1e-6);
        assertEquals(0, evaluate("q.all(3, 3, 4)"), 1e-6);
    }
}
