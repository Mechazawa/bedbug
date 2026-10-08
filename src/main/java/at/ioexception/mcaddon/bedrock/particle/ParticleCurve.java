package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A curve a pack names under `curves`, whose answer becomes a Molang variable of that name.
 *
 * The input is an expression rather than a clock, so a curve may be read against anything: a
 * particle's age, its speed, a variable another curve wrote. `horizontal_range` is the value the
 * input is measured against, so an input of half the range lands halfway along the nodes.
 */
public record ParticleCurve(Kind kind, MolangExpression input, MolangExpression horizontalRange,
    List<MolangExpression> nodes, List<Knot> knots) {

    public enum Kind {
        LINEAR,
        BEZIER,
        BEZIER_CHAIN,
        CATMULL_ROM
    }

    /**
     * One node of a bezier chain: where it sits, and the value and slope on each side of it.
     *
     * A node written with `value` and `slope` rather than a pair carries the same number on both
     * sides, which is a curve that does not jump where the two segments meet.
     */
    public record Knot(float at, MolangExpression leftValue, MolangExpression rightValue,
        MolangExpression leftSlope, MolangExpression rightSlope) {
    }

    /** Every curve a file declares, by the variable name its answer is written to. */
    public static Map<String, ParticleCurve> readAll(JsonObject curves) {
        Map<String, ParticleCurve> read = new LinkedHashMap<>();
        if (curves == null) return read;

        curves.entrySet().forEach(entry -> {
            ParticleCurve curve = tryFrom(entry.getValue());
            if (curve != null) read.put(entry.getKey().replaceFirst("^variable\\.", ""), curve);
        });
        return read;
    }

    /** Null for a curve with no nodes, which has no shape to read. */
    public static ParticleCurve tryFrom(JsonElement value) {
        if (!value.isJsonObject()) return null;

        JsonObject json = value.getAsJsonObject();
        Kind kind = kindOf(Json.text(json, "type", "linear"));
        MolangExpression input = ParticleJson.expression(json, "input", 0);
        MolangExpression range = ParticleJson.expression(json, "horizontal_range", 1);

        JsonElement nodes = json.get("nodes");
        if (kind == Kind.BEZIER_CHAIN) {
            List<Knot> knots = knots(nodes);
            return knots.size() < 2 ? null : new ParticleCurve(kind, input, range, List.of(), knots);
        }

        List<MolangExpression> values = new ArrayList<>();
        if (nodes != null && nodes.isJsonArray()) {
            nodes.getAsJsonArray().forEach(node -> values.add(ParticleJson.expression(node)));
        }
        return values.isEmpty() ? null
            : new ParticleCurve(kind, input, range, List.copyOf(values), List.of());
    }

    private static Kind kindOf(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "bezier" -> Kind.BEZIER;
            case "bezier_chain" -> Kind.BEZIER_CHAIN;
            case "catmull_rom" -> Kind.CATMULL_ROM;
            default -> Kind.LINEAR;
        };
    }

    /** The nodes of a chain, sorted by position, because a pack may write them in any order. */
    private static List<Knot> knots(JsonElement nodes) {
        List<Knot> knots = new ArrayList<>();
        if (nodes == null || !nodes.isJsonObject()) return knots;

        for (Map.Entry<String, JsonElement> node : nodes.getAsJsonObject().entrySet()) {
            if (!node.getValue().isJsonObject()) continue;

            JsonObject body = node.getValue().getAsJsonObject();
            MolangExpression value = ParticleJson.expression(body, "value", 0);
            MolangExpression slope = ParticleJson.expression(body, "slope", 0);
            knots.add(new Knot(position(node.getKey()),
                body.has("left_value") ? ParticleJson.expression(body, "left_value", 0) : value,
                body.has("right_value") ? ParticleJson.expression(body, "right_value", 0) : value,
                body.has("left_slope") ? ParticleJson.expression(body, "left_slope", 0) : slope,
                body.has("right_slope") ? ParticleJson.expression(body, "right_slope", 0) : slope));
        }
        knots.sort(Comparator.comparing(Knot::at));
        return List.copyOf(knots);
    }

    private static float position(String key) {
        try {
            return Float.parseFloat(key);
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    /**
     * The curve's answer for where its input stands right now.
     *
     * A chain's nodes are keyed by the input itself, so the range is not applied to it: Bedrock
     * ignores `horizontal_range` on a chain, and dividing by it would read every node somewhere else.
     */
    public float at(MolangEnvironment environment) {
        float read = input.asFloat(environment);
        return switch (kind) {
            case BEZIER_CHAIN -> chain(environment, read);
            case BEZIER -> bezier(environment, along(read, environment));
            case CATMULL_ROM -> catmullRom(environment, along(read, environment));
            case LINEAR -> linear(environment, along(read, environment));
        };
    }

    /** How far along the nodes an input lands, measured against the range and kept inside it. */
    private float along(float read, MolangEnvironment environment) {
        float range = horizontalRange.asFloat(environment);
        return clamp(read / (range == 0 ? 1 : range));
    }

    private static float clamp(float along) {
        return Math.clamp(along, 0f, 1f);
    }

    private float value(MolangEnvironment environment, int index) {
        return nodes.get(Math.clamp(index, 0, nodes.size() - 1)).asFloat(environment);
    }

    /** Nodes at even distances along the whole range, joined by straight lines. */
    private float linear(MolangEnvironment environment, float along) {
        if (nodes.size() == 1) return value(environment, 0);

        float step = along * (nodes.size() - 1);
        int first = (int) step;
        return mix(value(environment, first), value(environment, first + 1), step - first);
    }

    /** Four control values, the middle two pulling the line towards themselves without touching it. */
    private float bezier(MolangEnvironment environment, float along) {
        float rest = 1 - along;
        return rest * rest * rest * value(environment, 0)
            + 3 * rest * rest * along * value(environment, 1)
            + 3 * rest * along * along * value(environment, 2)
            + along * along * along * value(environment, 3);
    }

    /**
     * A curve through every node but the first and the last, which set the slope at the ends.
     *
     * Fewer than four nodes leave no segment to draw, and the curve is read as a straight line
     * between them instead.
     */
    private float catmullRom(MolangEnvironment environment, float along) {
        if (nodes.size() < 4) return linear(environment, along);

        float step = along * (nodes.size() - 3);
        int first = Math.min((int) step, nodes.size() - 4);
        float local = step - first;

        float before = value(environment, first);
        float from = value(environment, first + 1);
        float to = value(environment, first + 2);
        float after = value(environment, first + 3);
        return hermite(from, to, (to - before) / 2f, (after - from) / 2f, local);
    }

    /** A chain of segments, each shaped by the value and the slope at the node on either side. */
    private float chain(MolangEnvironment environment, float along) {
        Knot first = knots.get(0);
        if (along <= first.at()) return first.rightValue().asFloat(environment);

        Knot last = knots.get(knots.size() - 1);
        if (along >= last.at()) return last.leftValue().asFloat(environment);

        for (int i = 0; i < knots.size() - 1; i++) {
            Knot from = knots.get(i);
            Knot to = knots.get(i + 1);
            if (along > to.at()) continue;

            float span = to.at() - from.at();
            float local = span == 0 ? 0 : (along - from.at()) / span;
            return hermite(from.rightValue().asFloat(environment), to.leftValue().asFloat(environment),
                from.rightSlope().asFloat(environment) * span,
                to.leftSlope().asFloat(environment) * span, local);
        }
        return last.leftValue().asFloat(environment);
    }

    /** The cubic through two values with a slope given at each of them. */
    private static float hermite(float from, float to, float fromSlope, float toSlope, float along) {
        float squared = along * along;
        float cubed = squared * along;
        return (2 * cubed - 3 * squared + 1) * from
            + (cubed - 2 * squared + along) * fromSlope
            + (-2 * cubed + 3 * squared) * to
            + (cubed - squared) * toSlope;
    }

    private static float mix(float from, float to, float along) {
        return from + (to - from) * along;
    }
}
