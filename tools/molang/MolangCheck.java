import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Parses every expression a real pack contains, then checks the answers that can be worked out. */
public final class MolangCheck {
    public static void main(String[] args) throws Exception {
        List<String> expressions = Files.readAllLines(Path.of(args[0]));
        MolangEnvironment environment = new MolangEnvironment();
        // A query answering with several numbers at once is asked for one member at a time, and the
        // member reaches the answering side as part of the name whether or not the call took
        // arguments. These stand in for the ones the game answers from an entity.
        environment.setQueries((name, arguments) -> switch (name) {
            case "is_baby" -> MolangValue.ONE;
            case "anim_time" -> MolangValue.of(0.5);
            case "bone_rotation.x" -> MolangValue.of(arguments.size());
            case "bone_aabb.min.y" -> MolangValue.of(-7);
            case "spellcolor.r" -> MolangValue.of(0.25);
            case "key_frame_lerp_time" -> MolangValue.of(0.5);
            default -> MolangValue.ZERO;
        });

        int threw = 0;
        for (String expression : expressions) {
            if (expression.isBlank()) continue;
            try {
                MolangParser.parse(expression).evaluate(environment);
            } catch (RuntimeException error) {
                threw++;
                if (threw <= 5) System.out.println("threw: " + expression + " -> " + error);
            }
        }
        System.out.println("parsed " + expressions.size() + " expressions, " + threw + " threw");

        int failed = 0;
        failed += check(environment, "1 + 2 * 3", 7);
        failed += check(environment, "math.sin(90)", 1);
        failed += check(environment, "math.cos(0)", 1);
        failed += check(environment, "math.pi", Math.PI);
        failed += check(environment, "math.copy_sign(3, -1)", -3);
        failed += check(environment, "math.clamp(5, 0, 1)", 1);
        failed += check(environment, "1 / 0", 0);
        failed += check(environment, "math.mod(7, 0)", 0);
        failed += check(environment, "q.is_baby ? 10 : 20", 10);
        failed += check(environment, "q.missing_query", 0);
        failed += check(environment, "0 ? 1", 0);
        failed += check(environment, "{v.a = 3; return v.a * 2;}", 6);
        failed += check(environment, "v.never_set ?? 5", 5);
        failed += check(environment, "{v.zero = 0; return v.zero ?? 5;}", 0);
        failed += check(environment, "{v.Mixed = 4; return v.mixed;}", 4);
        failed += check(environment, "{v.total = 0; loop(4, {v.total = v.total + 2;}); return v.total;}", 8);
        failed += check(environment, "c.owning_entity->v.anything", 0);
        failed += check(environment, "'a' == 'a'", 1);
        failed += check(environment, "'a' == 1", 0);
        failed += check(environment, "!0", 1);
        failed += check(environment, "math.floor(-1.5)", -2);
        failed += check(environment, "math.trunc(-1.5)", -1);
        failed += check(environment, "2 < 3 && 3 <= 3", 1);
        failed += check(environment, "q.bone_rotation('head').x", 1);
        failed += check(environment, "q.bone_aabb('leg1a').min.y", -7);
        failed += check(environment, "q.spellcolor.r", 0.25);
        failed += check(environment, "q.key_frame_lerp_time * 4", 2);
        failed += check(environment, "q.bone_rotation('head').x + 1", 2);
        failed += check(environment, "q.bone_rotation('head').z", 0);
        System.out.println(failed == 0 ? "every assertion held" : failed + " assertions failed");
    }

    private static int check(MolangEnvironment environment, String source, double expected) {
        MolangExpression parsed = MolangParser.parse(source);
        double actual = parsed.evaluate(environment).number();
        if (Math.abs(actual - expected) < 1e-6) return 0;
        System.out.println("  " + source + " gave " + actual + ", expected " + expected);
        return 1;
    }
}
