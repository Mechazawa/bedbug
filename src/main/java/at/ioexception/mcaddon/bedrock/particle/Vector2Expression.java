package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Two Molang expressions, which is what a particle's size and every one of its UV pairs is. */
public record Vector2Expression(MolangExpression x, MolangExpression y) {
    public static final Vector2Expression ZERO = of(0, 0);
    public static final Vector2Expression ONE = of(1, 1);

    public static Vector2Expression of(double x, double y) {
        return new Vector2Expression(MolangExpression.constant(x), MolangExpression.constant(y));
    }

    /** Anything but two components reads as the fallback, there being no pair to take. */
    public static Vector2Expression read(JsonObject json, String key, Vector2Expression fallback) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null || !value.isJsonArray()) return fallback;

        JsonArray array = value.getAsJsonArray();
        if (array.size() < 2) return fallback;
        return new Vector2Expression(ParticleJson.expression(array.get(0)),
            ParticleJson.expression(array.get(1)));
    }
}
