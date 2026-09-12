package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The colour a particle's texture is multiplied by.
 *
 * A pack writes it three ways: four Molang values, a hexadecimal string, or a gradient read at a
 * position an expression gives. Every channel here runs from 0 to 1, which is the range the four
 * value form uses, so a hexadecimal string is divided by 255 on the way in and the three forms
 * compare.
 */
public sealed interface ParticleTint {
    record Solid(MolangExpression red, MolangExpression green, MolangExpression blue,
        MolangExpression alpha) implements ParticleTint {
    }

    /**
     * Colours at positions between 0 and 1, and the expression that says where to read.
     *
     * The stops are sorted by position, because a pack is free to write them in any order and an
     * unsorted list interpolates backwards.
     */
    record Gradient(List<Stop> stops, MolangExpression interpolant) implements ParticleTint {
        public record Stop(float position, Solid colour) {
        }
    }

    static ParticleTint read(JsonObject components) {
        JsonObject tinting = ParticleJson.object(components, "minecraft:particle_appearance_tinting");
        return tinting == null ? null : colour(tinting.get("color"));
    }

    static ParticleTint colour(JsonElement value) {
        if (value == null) return null;
        if (value.isJsonArray()) return solid(value.getAsJsonArray());
        if (value.isJsonPrimitive()) return hex(value.getAsString());
        return gradient(value.getAsJsonObject());
    }

    private static Gradient gradient(JsonObject json) {
        JsonElement stops = json.get("gradient");
        if (stops == null) return null;

        MolangExpression interpolant = ParticleJson.expression(json, "interpolant", 0);
        if (stops.isJsonArray()) return new Gradient(spread(stops.getAsJsonArray()), interpolant);
        if (!stops.isJsonObject()) return null;

        List<Gradient.Stop> placed = new ArrayList<>();
        for (Map.Entry<String, JsonElement> stop : stops.getAsJsonObject().entrySet()) {
            Solid colour = solid(stop.getValue());
            if (colour != null) placed.add(new Gradient.Stop(position(stop.getKey()), colour));
        }
        return new Gradient(sorted(placed), interpolant);
    }

    /** A gradient written as a plain list puts its colours at even distances along the whole range. */
    private static List<Gradient.Stop> spread(JsonArray colours) {
        List<Gradient.Stop> stops = new ArrayList<>();
        for (int i = 0; i < colours.size(); i++) {
            Solid colour = solid(colours.get(i));
            if (colour == null) continue;
            stops.add(new Gradient.Stop(colours.size() < 2 ? 0 : (float) i / (colours.size() - 1), colour));
        }
        return List.copyOf(stops);
    }

    private static List<Gradient.Stop> sorted(List<Gradient.Stop> stops) {
        return stops.stream().sorted(Comparator.comparing(Gradient.Stop::position)).toList();
    }

    private static float position(String key) {
        try {
            return Float.parseFloat(key);
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    private static Solid solid(JsonElement value) {
        if (value.isJsonArray()) return solid(value.getAsJsonArray());
        return value.isJsonPrimitive() ? hex(value.getAsString()) : null;
    }

    private static Solid solid(JsonArray channels) {
        if (channels.size() < 3) return null;

        return new Solid(ParticleJson.expression(channels.get(0)),
            ParticleJson.expression(channels.get(1)),
            ParticleJson.expression(channels.get(2)),
            channels.size() > 3 ? ParticleJson.expression(channels.get(3))
                : MolangExpression.constant(1));
    }

    /** Eight digits carry the alpha in front of the colour, which is the order Bedrock writes. */
    private static Solid hex(String text) {
        String digits = text.startsWith("#") ? text.substring(1) : text;
        if (!digits.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return null;

        long packed = Long.parseLong(digits, 16);
        return new Solid(channel(packed >> 16), channel(packed >> 8), channel(packed),
            digits.length() == 8 ? channel(packed >> 24) : MolangExpression.constant(1));
    }

    private static MolangExpression channel(long packed) {
        return MolangExpression.constant((packed & 0xFF) / 255.0);
    }
}
