package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import com.google.gson.JsonObject;

/** How long an emitter runs before it sleeps or stops. The three forms are exclusive. */
public sealed interface EmitterLifetime {
    record Looping(MolangExpression activeTime, MolangExpression sleepTime) implements EmitterLifetime {
    }

    record Once(MolangExpression activeTime) implements EmitterLifetime {
    }

    /** Both sides are tested every update, and a non-zero answer starts or ends the emitter. */
    record Expression(MolangExpression activation, MolangExpression expiration) implements EmitterLifetime {
    }

    static EmitterLifetime read(JsonObject components) {
        JsonObject looping = ParticleJson.object(components, "minecraft:emitter_lifetime_looping");
        if (looping != null) {
            return new Looping(ParticleJson.expression(looping, "active_time", 10),
                ParticleJson.expression(looping, "sleep_time", 0));
        }

        JsonObject once = ParticleJson.object(components, "minecraft:emitter_lifetime_once");
        if (once != null) return new Once(ParticleJson.expression(once, "active_time", 10));

        JsonObject expression = ParticleJson.object(components, "minecraft:emitter_lifetime_expression");
        if (expression != null) {
            return new Expression(ParticleJson.expression(expression, "activation_expression", 1),
                ParticleJson.expression(expression, "expiration_expression", 0));
        }
        return null;
    }
}
