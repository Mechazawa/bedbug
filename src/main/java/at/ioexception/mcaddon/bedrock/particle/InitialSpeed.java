package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import com.google.gson.JsonElement;

/**
 * How fast a particle leaves the emitter, which a pack writes as either one value or three.
 *
 * The two forms are not the same value written differently: one value is a speed along whatever
 * direction the emitter shape gave the particle, and three are a velocity of their own that
 * ignores that direction.
 */
public sealed interface InitialSpeed {
    record Scalar(MolangExpression speed) implements InitialSpeed {
    }

    record Vector(Vector3Expression velocity) implements InitialSpeed {
    }

    static InitialSpeed read(JsonElement value) {
        if (value == null) return null;
        if (value.isJsonArray()) return new Vector(ParticleJson.vector(value));
        return new Scalar(ParticleJson.expression(value));
    }
}
