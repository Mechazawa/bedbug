package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The value shapes a particle file is written in.
 *
 * Bedrock takes a Molang string anywhere it takes a number, so every number here becomes an
 * expression and `"radius": "v.size * 2"` is read the same way as `"radius": 2`.
 */
final class ParticleJson {
    private ParticleJson() {
    }

    /** The fallback stands in for a field the component leaves out, at the value Bedrock gives it. */
    static MolangExpression expression(JsonObject json, String key, double fallback) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || !value.isJsonPrimitive()) return MolangExpression.constant(fallback);
        return expression(value);
    }

    static MolangExpression expression(JsonElement value) {
        if (!value.isJsonPrimitive()) return MolangExpression.ZERO;

        JsonPrimitive primitive = value.getAsJsonPrimitive();
        Object raw = primitive.isNumber() ? primitive.getAsDouble() : primitive.getAsString();
        return MolangParser.parse(raw);
    }

    static Vector3Expression vector(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value == null ? Vector3Expression.ZERO : vector(value);
    }

    /** Anything but three components is a value nothing can be drawn from, and reads as zero. */
    static Vector3Expression vector(JsonElement value) {
        if (!value.isJsonArray()) return Vector3Expression.ZERO;

        JsonArray array = value.getAsJsonArray();
        if (array.size() < 3) return Vector3Expression.ZERO;
        return new Vector3Expression(expression(array.get(0)), expression(array.get(1)),
            expression(array.get(2)));
    }

    static boolean flag(JsonObject json, String key, boolean fallback) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;

        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isNumber() ? primitive.getAsDouble() != 0 : primitive.getAsBoolean();
    }

    static float number(JsonObject json, String key, float fallback) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return value.getAsFloat();
    }

    /** Null for a key a pack wrote as something other than an object, which has nothing to read. */
    static JsonObject object(JsonObject json, String key) {
        JsonElement value = json == null ? null : json.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
}
