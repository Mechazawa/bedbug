package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Which way a shape sends the particles it spawns.
 *
 * A shape writes this as either a vector or one of two words. The words are not vectors a reader
 * can work out: they mean along the line from the shape's centre to the point the particle appeared
 * at, which is a different direction for every particle.
 */
public sealed interface EmitterDirection {
    record Relative(boolean outwards) implements EmitterDirection {
    }

    record Vector(Vector3Expression direction) implements EmitterDirection {
    }

    /** A shape that says nothing about direction throws its particles outwards. */
    static EmitterDirection read(JsonObject shape) {
        JsonElement value = shape == null ? null : shape.get("direction");
        if (value != null && value.isJsonArray()) return new Vector(ParticleJson.vector(value));

        boolean inwards = value != null && value.isJsonPrimitive()
            && "inwards".equalsIgnoreCase(value.getAsString());
        return new Relative(!inwards);
    }
}
