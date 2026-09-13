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
    /** The overshoot constant from Penner's back curves, and the wider one its in-out form takes. */
    private static final double BACK_OVERSHOOT = 1.70158;
    private static final double BACK_OVERSHOOT_INOUT = BACK_OVERSHOOT * 1.525;

    private MolangMath() {
    }

    public static MolangValue call(String name, List<MolangValue> arguments) {
        double a = argument(arguments, 0);
        double b = argument(arguments, 1);
        double c = argument(arguments, 2);
        double progress = clamp01(c);

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
            case "lerp" -> a + (b - a) * progress;
            case "inverse_lerp" -> a == b ? 0 : clamp01((c - a) / (b - a));
            case "lerprotate" -> lerpRotate(a, b, progress);
            case "hermite_blend" -> hermite(a);
            case "die_roll" -> dieRoll(a, b, c);
            case "die_roll_integer" -> Math.floor(dieRoll(a, b + 1, c));
            case "random" -> arguments.isEmpty() ? random().nextDouble() : a + random().nextDouble() * (b - a);
            case "random_integer" -> Math.floor(a + random().nextDouble() * (b - a + 1));
            case "ease_in_sine" -> ease(a, b, 1 - Math.cos(progress * Math.PI / 2));
            case "ease_out_sine" -> ease(a, b, Math.sin(progress * Math.PI / 2));
            case "ease_in_out_sine" -> ease(a, b, (1 - Math.cos(progress * Math.PI)) / 2);
            case "ease_in_quad" -> ease(a, b, Math.pow(progress, 2));
            case "ease_out_quad" -> ease(a, b, 1 - Math.pow(1 - progress, 2));
            case "ease_in_out_quad" -> ease(a, b, inOut(progress, Math.pow(mirrored(progress), 2)));
            case "ease_in_cubic" -> ease(a, b, Math.pow(progress, 3));
            case "ease_out_cubic" -> ease(a, b, 1 - Math.pow(1 - progress, 3));
            case "ease_in_out_cubic" -> ease(a, b, inOut(progress, Math.pow(mirrored(progress), 3)));
            case "ease_in_quart" -> ease(a, b, Math.pow(progress, 4));
            case "ease_out_quart" -> ease(a, b, 1 - Math.pow(1 - progress, 4));
            case "ease_in_out_quart" -> ease(a, b, inOut(progress, Math.pow(mirrored(progress), 4)));
            case "ease_in_quint" -> ease(a, b, Math.pow(progress, 5));
            case "ease_out_quint" -> ease(a, b, 1 - Math.pow(1 - progress, 5));
            case "ease_in_out_quint" -> ease(a, b, inOut(progress, Math.pow(mirrored(progress), 5)));
            case "ease_in_expo" -> ease(a, b, expoIn(progress));
            case "ease_out_expo" -> ease(a, b, 1 - expoIn(1 - progress));
            case "ease_in_out_expo" -> ease(a, b, inOut(progress, expoIn(mirrored(progress))));
            case "ease_in_circ" -> ease(a, b, circIn(progress));
            case "ease_out_circ" -> ease(a, b, 1 - circIn(1 - progress));
            case "ease_in_out_circ" -> ease(a, b, inOut(progress, circIn(mirrored(progress))));
            case "ease_in_back" -> ease(a, b, backIn(progress, BACK_OVERSHOOT));
            case "ease_out_back" -> ease(a, b, 1 - backIn(1 - progress, BACK_OVERSHOOT));
            case "ease_in_out_back" -> ease(a, b, inOut(progress, backIn(mirrored(progress), BACK_OVERSHOOT_INOUT)));
            case "ease_in_elastic" -> ease(a, b, elasticIn(progress, 10.75, 3));
            case "ease_out_elastic" -> ease(a, b, 1 - elasticIn(1 - progress, 10.75, 3));
            case "ease_in_out_elastic" -> ease(a, b, inOut(progress, elasticIn(mirrored(progress), 11.125, 4.5)));
            case "ease_in_bounce" -> ease(a, b, 1 - bounceOut(1 - progress));
            case "ease_out_bounce" -> ease(a, b, bounceOut(progress));
            case "ease_in_out_bounce" -> ease(a, b, inOut(progress, 1 - bounceOut(1 - mirrored(progress))));
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

    /**
     * An easing carries the two ends it runs between, which keeps every curve itself a 0 to 1 shape.
     *
     * Bedrock spells every one of them `math.ease_*(start, end, 0_to_1)`, the same argument order as
     * `math.lerp`, so the first argument is where the curve leaves and the second is where it lands.
     */
    private static double ease(double start, double end, double curve) {
        return start + (end - start) * curve;
    }

    /** An in-out curve runs its in half over the first half of the range, then the mirror image. */
    private static double mirrored(double progress) {
        return progress < 0.5 ? 2 * progress : 2 - 2 * progress;
    }

    /** Folds a curve read at mirrored progress back into the in-out range. */
    private static double inOut(double progress, double curve) {
        return progress < 0.5 ? curve / 2 : 1 - curve / 2;
    }

    private static double expoIn(double progress) {
        return progress <= 0 ? 0 : Math.pow(2, 10 * progress - 10);
    }

    private static double circIn(double progress) {
        return 1 - Math.sqrt(1 - progress * progress);
    }

    private static double backIn(double progress, double overshoot) {
        return (overshoot + 1) * progress * progress * progress - overshoot * progress * progress;
    }

    /** The phase shift and the period are Penner's, and the in-out form takes a different pair. */
    private static double elasticIn(double progress, double phaseShift, double period) {
        if (progress <= 0) return 0;
        return -Math.pow(2, 10 * progress - 10) * Math.sin((10 * progress - phaseShift) * 2 * Math.PI / period);
    }

    private static double bounceOut(double progress) {
        if (progress < 1 / 2.75) return 7.5625 * progress * progress;
        if (progress < 2 / 2.75) {
            double offset = progress - 1.5 / 2.75;
            return 7.5625 * offset * offset + 0.75;
        }
        if (progress < 2.5 / 2.75) {
            double offset = progress - 2.25 / 2.75;
            return 7.5625 * offset * offset + 0.9375;
        }
        double offset = progress - 2.625 / 2.75;
        return 7.5625 * offset * offset + 0.984375;
    }

    private static RandomGenerator random() {
        return ThreadLocalRandom.current();
    }
}
