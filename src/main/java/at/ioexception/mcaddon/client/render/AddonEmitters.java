package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.particle.EmitterLifetime;
import at.ioexception.mcaddon.bedrock.particle.EmitterRate;
import at.ioexception.mcaddon.bedrock.particle.ParticleDefinition;
import at.ioexception.mcaddon.registry.AddonParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The effects currently running, each still deciding when to make its next particle.
 *
 * A Bedrock effect is an emitter rather than a single particle: it has a lifetime of its own and
 * makes particles at the rate the pack chose for as long as it is active. One firing of an effect
 * therefore starts an emitter here and the emitter keeps going until its own clock says otherwise.
 * An effect that emits everything at once still goes through here, and simply finishes in one tick.
 */
public final class AddonEmitters {
    /** A running effect cannot outlive this, however its expressions behave. */
    private static final float MAXIMUM_SECONDS = 120f;

    private static final List<Running> RUNNING = new ArrayList<>();

    private AddonEmitters() {
    }

    /** One effect in flight, at a place or following something. */
    private static final class Running {
        private final ParticleDefinition definition;
        private final SimpleParticleType type;
        private final MolangEnvironment environment = new MolangEnvironment();

        /** Null for an effect standing still, which is how a one-off burst is left behind. */
        private final Entity following;
        private final Vec3 at;

        private float age;
        private float owed;
        private int made;
        private boolean sleeping;

        private Running(ParticleDefinition definition, SimpleParticleType type, Entity following, Vec3 at) {
            this.definition = definition;
            this.type = type;
            this.following = following;
            this.at = at;

            ParticleDefinition.Emitter.Initialization start = definition.emitter().initialization();
            if (start != null) start.creation().evaluate(environment);
        }

        private Vec3 where() {
            return following == null ? at : following.position().add(at);
        }
    }

    /** Starts an effect at a point, or following an entity when one is given. */
    public static void start(String identifier, Entity following, Vec3 at) {
        SimpleParticleType type = AddonParticles.typeOf(identifier);
        ParticleDefinition definition = AddonParticles.definitionOf(identifier);
        if (type == null || definition == null) return;

        RUNNING.add(new Running(definition, type, following, at));
    }

    /** Advances every running effect by one tick, dropping the ones that are over. */
    public static void tick() {
        if (RUNNING.isEmpty() || Minecraft.getInstance().level == null) return;

        Iterator<Running> running = RUNNING.iterator();
        while (running.hasNext()) {
            Running effect = running.next();
            if (!advance(effect)) running.remove();
        }
    }

    public static void clear() {
        RUNNING.clear();
    }

    /**
     * How many effects are in flight.
     *
     * Java has no emitter of its own: everything it draws is a particle placed one at a time, so
     * what is running here is every emitter in the world.
     */
    public static int running() {
        return RUNNING.size();
    }

    /** Whether the effect is still going after this tick. */
    private static boolean advance(Running effect) {
        effect.age += 1f / 20f;
        if (effect.age > MAXIMUM_SECONDS) return false;
        if (effect.following != null && effect.following.isRemoved()) return false;

        effect.environment.setVariable("emitter_age",
            at.ioexception.mcaddon.bedrock.molang.MolangValue.of(effect.age));
        ParticleDefinition.Emitter.Initialization start = effect.definition.emitter().initialization();
        if (start != null) start.perUpdate().evaluate(effect.environment);

        if (!active(effect)) return !over(effect);

        emit(effect);
        return !over(effect);
    }

    /**
     * Whether the emitter is making particles at this moment.
     *
     * A looping emitter alternates between making particles and sleeping, and an expression emitter
     * is told directly when to start and stop.
     */
    private static boolean active(Running effect) {
        EmitterLifetime lifetime = effect.definition.emitter().lifetime();
        return switch (lifetime) {
            case null -> effect.age <= 1f;
            case EmitterLifetime.Once once -> effect.age <= once.activeTime().asFloat(effect.environment);
            case EmitterLifetime.Looping looping -> {
                float awake = Math.max(0.05f, looping.activeTime().asFloat(effect.environment));
                float asleep = Math.max(0f, looping.sleepTime().asFloat(effect.environment));
                float round = awake + asleep;
                boolean making = asleep <= 0f || effect.age % round < awake;

                // A round starting again is a fresh burst for an emitter that makes them all at once.
                if (making && effect.sleeping) effect.made = 0;
                effect.sleeping = !making;
                yield making;
            }
            case EmitterLifetime.Expression expression -> {
                boolean on = expression.activation().evaluate(effect.environment).truthy();
                yield on;
            }
        };
    }

    /** Whether the effect has finished for good, rather than merely pausing. */
    private static boolean over(Running effect) {
        EmitterLifetime lifetime = effect.definition.emitter().lifetime();
        return switch (lifetime) {
            case null -> effect.age > 1f;
            case EmitterLifetime.Once once ->
                effect.age > once.activeTime().asFloat(effect.environment);
            case EmitterLifetime.Looping ignored -> false;
            case EmitterLifetime.Expression expression ->
                expression.expiration().evaluate(effect.environment).truthy();
        };
    }

    /**
     * Makes this tick's particles.
     *
     * A steady rate is particles per second, so a tick is owed a fraction of one and the fractions
     * are carried until they add up. An instant rate makes its whole count the first time it runs.
     */
    private static void emit(Running effect) {
        EmitterRate rate = effect.definition.emitter().rate();
        int wanted = switch (rate) {
            case null -> effect.made == 0 ? 1 : 0;
            case EmitterRate.Instant instant -> {
                int count = (int) instant.numParticles().asFloat(effect.environment);
                yield effect.made == 0 ? count : 0;
            }
            case EmitterRate.Manual ignored -> 0;
            case EmitterRate.Steady steady -> {
                effect.owed += steady.spawnRate().asFloat(effect.environment) / 20f;
                int count = (int) effect.owed;
                effect.owed -= count;

                int most = (int) steady.maxParticles().asFloat(effect.environment);
                yield most > 0 ? Math.min(count, Math.max(0, most - effect.made)) : count;
            }
        };
        if (wanted <= 0) return;

        Vec3 where = effect.where();
        for (int i = 0; i < Math.min(wanted, 256); i++) {
            Minecraft.getInstance().level.addParticle(effect.type, where.x, where.y, where.z, 0, 0, 0);
        }
        effect.made += wanted;
    }
}
