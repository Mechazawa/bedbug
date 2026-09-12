package at.ioexception.mcaddon.bedrock.molang;

import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The `Math.*` functions.
 *
 * Bedrock's trigonometry works in degrees, not radians, which is the one detail that silently
 * ruins every animation if you miss it.
 */
public final class MolangMath {
    private MolangMath() {
    }

    public static MolangValue call(String name, List<MolangValue> arguments) {
        double a = argument(arguments, 0);
        double b = argument(arguments, 1);
        double c = argument(arguments, 2);

        return MolangValue.of(switch (name.toLowerCase(Locale.ROOT)) {
            case "abs" -> Math.abs(a);
            case "pi" -> Math.PI;
            case "copy_sign" -> Math.copySign(a, b);
            case "acos" -> Math.toDegrees(Math.acos(a));
            case "asin" -> Math.toDegrees(Math.asin(a));
            case "atan" -> Math.toDegrees(Math.atan(a));
            case "atan2" -> Math.toDegrees(Math.atan2(a, b));
            case "ceil" -> Math.ceil(a);
            case "clamp" -> Math.min(Math.max(a, b), c);
            case "cos" -> Math.cos(Math.toRadians(a));
            case "sin" -> Math.sin(Math.toRadians(a));
            case "exp" -> Math.exp(a);
            case "floor" -> Math.floor(a);
            case "ln" -> Math.log(a);
            case "max" -> Math.max(a, b);
            case "min" -> Math.min(a, b);
            case "mod" -> b == 0 ? 0 : a % b;
            case "pow" -> Math.pow(a, b);
            case "round" -> Math.round(a);
            case "sqrt" -> Math.sqrt(a);
            case "trunc" -> (long) a;
            case "lerp" -> a + (b - a) * clamp01(c);
            case "lerprotate" -> lerpRotate(a, b, clamp01(c));
            case "hermite_blend" -> hermite(a);
            case "die_roll" -> dieRoll(a, b, c);
            case "die_roll_integer" -> Math.floor(dieRoll(a, b + 1, c));
            case "random" -> arguments.isEmpty() ? random().nextDouble() : a + random().nextDouble() * (b - a);
            case "random_integer" -> Math.floor(a + random().nextDouble() * (b - a + 1));
            case "ease_in_expo" -> a == 0 ? 0 : easeInExpo(a, b, c);
            case "ease_out_expo" -> easeOutExpo(a, b, c);
            case "min_angle" -> minAngle(a);
            case "sign" -> Math.signum(a);
            case "trunc_towards_zero" -> (long) a;
            // Reading an unknown function as zero keeps one stray expression from breaking a model.
            default -> 0;
        });
    }

    private static double argument(List<MolangValue> arguments, int index) {
        return index < arguments.size() ? arguments.get(index).number() : 0;
    }

    private static double clamp01(double value) {
        return Math.min(Math.max(value, 0), 1);
    }

    /** Smoothstep, which Bedrock names after the Hermite curve it comes from. */
    private static double hermite(double value) {
        double t = clamp01(value);
        return t * t * (3 - 2 * t);
    }

    private static double lerpRotate(double from, double to, double progress) {
        double difference = minAngle(to - from);
        return from + difference * progress;
    }

    /** Folds an angle into -180 to 180, so a turn takes the short way round. */
    private static double minAngle(double degrees) {
        double angle = degrees % 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    private static double dieRoll(double count, double low, double high) {
        double total = 0;
        for (int roll = 0; roll < (int) count; roll++) total += low + random().nextDouble() * (high - low);
        return total;
    }

    private static double easeInExpo(double value, double from, double progress) {
        return from + (value - from) * Math.pow(2, 10 * (clamp01(progress) - 1));
    }

    private static double easeOutExpo(double value, double from, double progress) {
        return from + (value - from) * (1 - Math.pow(2, -10 * clamp01(progress)));
    }

    private static RandomGenerator random() {
        return ThreadLocalRandom.current();
    }
}
