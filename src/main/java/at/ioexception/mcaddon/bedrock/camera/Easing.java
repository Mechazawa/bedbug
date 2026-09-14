package at.ioexception.mcaddon.bedrock.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The curve an `ease` on `/camera` names.
 *
 * Bedrock writes thirty-one names, which are ten curves taken in three directions plus `linear` and
 * `spring`. Each curve is written once in the direction that starts slowly; the other two are that
 * same curve read backwards and its two halves joined, so every name Bedrock has is answered without
 * thirty-one bodies to keep in step.
 */
public record Easing(Curve curve, Direction direction) {
    /** `linear` and `spring` are written on their own; the rest carry one of the three directions. */
    public enum Curve {
        LINEAR(false), SPRING(false), QUAD(true), CUBIC(true), QUART(true), QUINT(true), SINE(true),
        EXPO(true), CIRC(true), BACK(true), ELASTIC(true), BOUNCE(true);

        private final boolean directional;

        Curve(boolean directional) {
            this.directional = directional;
        }

        public boolean directional() {
            return directional;
        }
    }

    public enum Direction {
        IN, OUT, IN_OUT
    }

    public static final Easing LINEAR = new Easing(Curve.LINEAR, Direction.IN);

    private static final float BACK_OVERSHOOT = 1.70158f;
    private static final float ELASTIC_PERIOD = (float) (2 * Math.PI / 3);
    private static final float BOUNCE_STEEPNESS = 7.5625f;
    private static final float BOUNCE_WIDTH = 2.75f;

    /** Null for a name Bedrock has not got, which the command reports rather than guesses at. */
    public static Easing tryFrom(String written) {
        String name = written.toLowerCase(Locale.ROOT);
        if (name.equals("linear")) return LINEAR;
        if (name.equals("spring")) return new Easing(Curve.SPRING, Direction.IN);

        Direction direction;
        String rest;
        if (name.startsWith("in_out_")) {
            direction = Direction.IN_OUT;
            rest = name.substring("in_out_".length());
        } else if (name.startsWith("in_")) {
            direction = Direction.IN;
            rest = name.substring("in_".length());
        } else if (name.startsWith("out_")) {
            direction = Direction.OUT;
            rest = name.substring("out_".length());
        } else {
            return null;
        }

        for (Curve curve : Curve.values()) {
            if (curve.directional() && curve.name().toLowerCase(Locale.ROOT).equals(rest)) {
                return new Easing(curve, direction);
            }
        }
        return null;
    }

    /** The name Bedrock writes for this curve, which `tryFrom` reads back. */
    public String bedrockName() {
        String lower = curve.name().toLowerCase(Locale.ROOT);
        return curve.directional() ? direction.name().toLowerCase(Locale.ROOT) + "_" + lower : lower;
    }

    /** Every name `tryFrom` answers, for the suggestions the command offers as it is typed. */
    public static List<String> names() {
        List<String> names = new ArrayList<>(List.of("linear", "spring"));
        for (Curve curve : Curve.values()) {
            if (!curve.directional()) continue;
            String lower = curve.name().toLowerCase(Locale.ROOT);
            names.add("in_" + lower);
            names.add("out_" + lower);
            names.add("in_out_" + lower);
        }
        return names;
    }

    /** Zero at the start and one at the end; `back` and `elastic` leave that range in between. */
    public float at(float progress) {
        float held = Math.max(0f, Math.min(1f, progress));
        if (curve == Curve.LINEAR) return held;
        if (curve == Curve.SPRING) return spring(held);

        return switch (direction) {
            case IN -> rise(held);
            case OUT -> 1f - rise(1f - held);
            case IN_OUT -> held < 0.5f ? rise(held * 2f) / 2f : 1f - rise(2f - held * 2f) / 2f;
        };
    }

    /** The curve in the direction that starts slowly, which the other two are built from. */
    private float rise(float t) {
        return switch (curve) {
            case QUAD -> t * t;
            case CUBIC -> t * t * t;
            case QUART -> t * t * t * t;
            case QUINT -> t * t * t * t * t;
            case SINE -> 1f - (float) Math.cos(t * Math.PI / 2);
            case EXPO -> t == 0f ? 0f : (float) Math.pow(2, 10 * t - 10);
            case CIRC -> 1f - (float) Math.sqrt(1 - t * t);
            case BACK -> (BACK_OVERSHOOT + 1f) * t * t * t - BACK_OVERSHOOT * t * t;
            case ELASTIC -> elastic(t);
            case BOUNCE -> 1f - bounce(1f - t);
            case LINEAR, SPRING -> t;
        };
    }

    private static float elastic(float t) {
        if (t == 0f || t == 1f) return t;
        return (float) (-Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * ELASTIC_PERIOD));
    }

    /** Four falls of shrinking height, read from the end, which is what a bounce is everywhere. */
    private static float bounce(float t) {
        if (t < 1f / BOUNCE_WIDTH) return BOUNCE_STEEPNESS * t * t;
        if (t < 2f / BOUNCE_WIDTH) {
            float shifted = t - 1.5f / BOUNCE_WIDTH;
            return BOUNCE_STEEPNESS * shifted * shifted + 0.75f;
        }
        if (t < 2.5f / BOUNCE_WIDTH) {
            float shifted = t - 2.25f / BOUNCE_WIDTH;
            return BOUNCE_STEEPNESS * shifted * shifted + 0.9375f;
        }
        float shifted = t - 2.625f / BOUNCE_WIDTH;
        return BOUNCE_STEEPNESS * shifted * shifted + 0.984375f;
    }

    /** An oscillation that settles, landing exactly on one because the cosine is zero there. */
    private static float spring(float t) {
        return 1f - (float) (Math.cos(t * Math.PI * 4.5) * Math.exp(-t * 6));
    }
}
