package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.bedrock.particle.EmitterLifetime;
import at.ioexception.mcaddon.bedrock.particle.EmitterRate;
import at.ioexception.mcaddon.bedrock.particle.ParticleCurve;
import at.ioexception.mcaddon.bedrock.particle.ParticleDefinition;
import at.ioexception.mcaddon.bedrock.particle.ParticleEvent;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

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

    /** How deep a chain of effects firing effects is followed before it is left alone. */
    private static final int MAXIMUM_DEPTH = 4;

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
        private final int depth;

        private float age;
        private float owed;
        private int made;
        private boolean sleeping;

        /** How far the emitter has moved since it started, which its travel events count in. */
        private float travelled;
        private Vec3 was;

        private Running(ParticleDefinition definition, SimpleParticleType type, Entity following,
            Vec3 at, int depth) {
            this.definition = definition;
            this.type = type;
            this.following = following;
            this.at = at;
            this.depth = depth;

            randomise(environment, "emitter_random_");
            ParticleDefinition.Emitter.Initialization start = definition.emitter().initialization();
            if (start != null) start.creation().evaluate(environment);
        }

        private Vec3 where() {
            return following == null ? at : following.position().add(at);
        }
    }

    /** Starts an effect at a point, or following an entity when one is given. */
    public static void start(String identifier, Entity following, Vec3 at) {
        start(identifier, following, at, 0);
    }

    private static void start(String identifier, Entity following, Vec3 at, int depth) {
        SimpleParticleType type = AddonParticles.typeOf(identifier);
        ParticleDefinition definition = AddonParticles.definitionOf(identifier);
        if (type == null || definition == null || depth > MAXIMUM_DEPTH) return;

        Running effect = new Running(definition, type, following, at, depth);
        RUNNING.add(effect);
        raise(effect, definition.emitter().events().creation());
    }

    /** Advances every running effect by one tick, dropping the ones that are over. */
    public static void tick() {
        if (RUNNING.isEmpty() || Minecraft.getInstance().level == null) return;

        Iterator<Running> running = RUNNING.iterator();
        while (running.hasNext()) {
            Running effect = running.next();
            if (!advance(effect)) {
                raise(effect, effect.definition.emitter().events().expiration());
                running.remove();
            }
        }
    }

    public static void clear() {
        RUNNING.clear();
    }

    /**
     * The four random numbers Bedrock hands an emitter and a particle when it is created.
     *
     * They keep their values for as long as the thing lives, which is what lets a pack give every
     * particle of one effect a size or a colour of its own.
     */
    public static void randomise(MolangEnvironment environment, String prefix) {
        for (int i = 1; i <= 4; i++) {
            environment.setVariable(prefix + i,
                MolangValue.of(ThreadLocalRandom.current().nextFloat()));
        }
    }

    /** Writes each curve's answer to the Molang variable it is named after. */
    public static void evaluateCurves(ParticleDefinition definition, MolangEnvironment environment) {
        for (Map.Entry<String, ParticleCurve> curve : definition.curves().entrySet()) {
            environment.setVariable(curve.getKey(), MolangValue.of(curve.getValue().at(environment)));
        }
    }

    /** Whether the effect is still going after this tick. */
    private static boolean advance(Running effect) {
        float was = effect.age;
        effect.age += 1f / 20f;
        if (effect.age > MAXIMUM_SECONDS) return false;
        if (effect.following != null && effect.following.isRemoved()) return false;

        effect.environment.setVariable("emitter_age", MolangValue.of(effect.age));
        effect.environment.setVariable("emitter_lifetime", MolangValue.of(lifetime(effect)));
        effect.environment.setVariable("entity_scale", MolangValue.of(scale(effect.following)));
        evaluateCurves(effect.definition, effect.environment);

        ParticleDefinition.Emitter.Initialization start = effect.definition.emitter().initialization();
        if (start != null) start.perUpdate().evaluate(effect.environment);

        timeline(effect, was);
        travelled(effect);
        if (active(effect)) emit(effect);
        return !over(effect);
    }

    /** The emitter's own length, which an expression emitter decides tick by tick and does not know. */
    private static float lifetime(Running effect) {
        EmitterLifetime lifetime = effect.definition.emitter().lifetime();
        return switch (lifetime) {
            case null -> 1f;
            case EmitterLifetime.Once once -> once.activeTime().asFloat(effect.environment);
            case EmitterLifetime.Looping looping -> looping.activeTime().asFloat(effect.environment);
            case EmitterLifetime.Expression ignored -> 0f;
        };
    }

    /** How much bigger than usual the entity the effect hangs off is drawn. */
    private static float scale(Entity following) {
        return following instanceof LivingEntity living ? living.getScale() : 1f;
    }

    /** Raises the timeline events the emitter passed over during this tick. */
    private static void timeline(Running effect, float was) {
        for (ParticleEvent.Moment moment : effect.definition.emitter().events().timeline()) {
            if (moment.at() > was && moment.at() <= effect.age) raise(effect, moment.events());
        }
    }

    /**
     * Raises the travel events the emitter has now moved far enough for.
     *
     * A looping distance comes round again every time the emitter covers it, so the count of whole
     * distances behind it is what says whether this tick crossed another one.
     */
    private static void travelled(Running effect) {
        Vec3 here = effect.where();
        float before = effect.travelled;
        if (effect.was != null) effect.travelled += (float) effect.was.distanceTo(here);
        effect.was = here;

        for (ParticleEvent.Travel travel : effect.definition.emitter().events().travel()) {
            boolean reached = travel.looping()
                ? (int) (effect.travelled / travel.distance()) > (int) (before / travel.distance())
                : before < travel.distance() && effect.travelled >= travel.distance();
            if (reached) raise(effect, travel.events());
        }
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

    private static void raise(Running effect, List<String> names) {
        fire(effect.definition, names, effect.environment, effect.where(), effect.following,
            effect.depth);
    }

    /** Raises the named events of an effect, wherever the thing raising them stands. */
    public static void fire(ParticleDefinition definition, List<String> names,
        MolangEnvironment environment, Vec3 at, Entity following) {
        fire(definition, names, environment, at, following, 0);
    }

    private static void fire(ParticleDefinition definition, List<String> names,
        MolangEnvironment environment, Vec3 at, Entity following, int depth) {
        for (String name : names) {
            ParticleEvent event = definition.event(name);
            if (event != null) run(definition, event, environment, at, following, depth);
        }
    }

    /**
     * One event node, which may do several things at once.
     *
     * `sequence` runs each of its nodes and `randomize` picks one of them by weight, so a node is a
     * small tree and this walks it.
     */
    private static void run(ParticleDefinition definition, ParticleEvent event,
        MolangEnvironment environment, Vec3 at, Entity following, int depth) {
        if (depth > MAXIMUM_DEPTH) return;
        if (event.expression() != null) event.expression().evaluate(environment);
        if (event.log() != null) McAddon.LOGGER.debug("Particle event: {}", event.log());
        if (event.sound() != null) play(definition, event.sound(), at);
        if (event.particle() != null) show(event.particle(), environment, at, following, depth);

        event.sequence().forEach(node -> run(definition, node, environment, at, following, depth));
        chosen(event, environment)
            .ifPresent(node -> run(definition, node, environment, at, following, depth));
    }

    /** The one node a weighted choice landed on, or nothing when there were none to choose from. */
    private static Optional<ParticleEvent> chosen(ParticleEvent event,
        MolangEnvironment environment) {
        float total = 0;
        for (ParticleEvent.Chance chance : event.randomize()) total += Math.max(0f, chance.weight());
        if (total <= 0) return Optional.empty();

        float wanted = environment.variable("particle_random_1").asFloat() * total;
        for (ParticleEvent.Chance chance : event.randomize()) {
            wanted -= Math.max(0f, chance.weight());
            if (wanted <= 0) return Optional.of(chance.event());
        }
        return Optional.of(event.randomize().get(event.randomize().size() - 1).event());
    }

    /**
     * Starts the effect an event names, at the point it was raised from.
     *
     * A bound effect follows the entity the raising one was hanging off; the rest are left standing
     * where they were made.
     */
    private static void show(ParticleEvent.Visual visual, MolangEnvironment environment, Vec3 at,
        Entity following, int depth) {
        if (visual.before() != null) visual.before().evaluate(environment);

        boolean bound = "emitter_bound".equals(visual.type());
        Entity carrier = bound ? following : null;
        Vec3 where = bound && following != null ? at.subtract(following.position()) : at;
        start(visual.effect(), carrier, where, depth + 1);
    }

    /** Plays one of the pack's own sound events, under the namespace the effect itself carries. */
    private static void play(ParticleDefinition definition, String soundId, Vec3 at) {
        if (Minecraft.getInstance().level == null) return;

        String namespace = Names.split(definition.identifier(), "mcaddon").namespace();
        Names.Identifier name = Names.soundEvent(soundId, namespace);
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT
            .getOptional(Platform.id(name.namespace(), name.path())).orElse(null);
        if (sound == null) return;

        Minecraft.getInstance().level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.AMBIENT,
            1f, 1f, false);
    }
}
