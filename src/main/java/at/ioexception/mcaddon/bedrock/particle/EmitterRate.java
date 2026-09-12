package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import com.google.gson.JsonObject;

/**
 * How many particles an emitter spawns, and when.
 *
 * The three forms are exclusive: an emitter declares one of them, or none at all and spawns
 * nothing.
 */
public sealed interface EmitterRate {
    /** Every particle at once, at the moment the emitter starts. */
    record Instant(MolangExpression numParticles) implements EmitterRate {
    }

    record Steady(MolangExpression spawnRate, MolangExpression maxParticles) implements EmitterRate {
    }

    /** Nothing spawns on its own: a script or an event asks for each particle. */
    record Manual(MolangExpression maxParticles) implements EmitterRate {
    }

    static EmitterRate read(JsonObject components) {
        JsonObject instant = ParticleJson.object(components, "minecraft:emitter_rate_instant");
        if (instant != null) return new Instant(ParticleJson.expression(instant, "num_particles", 10));

        JsonObject steady = ParticleJson.object(components, "minecraft:emitter_rate_steady");
        if (steady != null) {
            return new Steady(ParticleJson.expression(steady, "spawn_rate", 1),
                ParticleJson.expression(steady, "max_particles", 50));
        }

        JsonObject manual = ParticleJson.object(components, "minecraft:emitter_rate_manual");
        if (manual != null) return new Manual(ParticleJson.expression(manual, "max_particles", 50));
        return null;
    }
}
