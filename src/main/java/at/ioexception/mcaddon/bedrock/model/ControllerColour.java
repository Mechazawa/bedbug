package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * One `{r, g, b, a}` group of a render controller, each channel a Molang expression.
 *
 * `this` inside a channel means whatever that channel would otherwise hold, so each is evaluated
 * against its own default. A pack uses that to switch one tint on under a condition and leave the
 * engine's own value alone the rest of the time.
 */
public record ControllerColour(
    MolangExpression red,
    MolangExpression green,
    MolangExpression blue,
    MolangExpression alpha
) {
    /** Null for a controller that declares no group of this name, which is most of them. */
    public static ControllerColour tryFrom(JsonObject controller, String field) {
        JsonElement group = controller.get(field);
        if (group == null || !group.isJsonObject()) return null;

        JsonObject body = group.getAsJsonObject();
        return new ControllerColour(channel(body, "r"), channel(body, "g"),
            channel(body, "b"), channel(body, "a"));
    }

    private static MolangExpression channel(JsonObject body, String name) {
        JsonElement value = body.get(name);
        // An absent channel leaves whatever the engine would have used, which is what `this` reads.
        if (value == null || !value.isJsonPrimitive()) return MolangEnvironment::self;

        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isNumber()
            ? MolangExpression.constant(primitive.getAsFloat())
            : MolangParser.parse(primitive.getAsString());
    }

    /**
     * The four channels as an ARGB integer, each starting from the matching byte of `defaults`.
     *
     * A channel reads zero to one where the packed colour holds zero to 255, so the default is
     * handed over in the Molang scale and the answer is scaled back. The channels are evaluated in
     * the order r, g, b, a, so a temporary set in one is seen by the ones after it.
     */
    public int evaluate(MolangEnvironment environment, int defaults) {
        float redValue = channel(environment, red, defaults >> 16 & 0xFF);
        float greenValue = channel(environment, green, defaults >> 8 & 0xFF);
        float blueValue = channel(environment, blue, defaults & 0xFF);
        float alphaValue = channel(environment, alpha, defaults >>> 24);
        return pack(alphaValue, 24) | pack(redValue, 16) | pack(greenValue, 8) | pack(blueValue, 0);
    }

    private static float channel(MolangEnvironment environment, MolangExpression expression, int fallback) {
        environment.setSelf(fallback / 255f);
        return expression.asFloat(environment);
    }

    private static int pack(float value, int shift) {
        return Math.round(Math.min(Math.max(value, 0f), 1f) * 255f) << shift;
    }
}
