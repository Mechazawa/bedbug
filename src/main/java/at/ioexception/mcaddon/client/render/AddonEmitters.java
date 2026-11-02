package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
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

    /** The emitter making a particle at this moment, whose variables the particle is born with. */
    private static Running emitting;

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

        /** Seconds since the emitter started, and since its current loop started. */
        private float age;
        private float loopStart;

        /** How long this loop makes particles and then rests, evaluated as the loop starts. */
        private float awake;
        private float asleep;

        private float owed;
        private int made;

        /** The particles this emitter made that are still alive, which a steady rate is capped by. */
        private final List<AddonParticle> alive = new ArrayList<>();
        private int cap;

        /** How far the emitter has moved since it started, which its travel events count in. */
        private float travelled;
        private Vec3 was;

        private Running(ParticleDefinition definition, SimpleParticleType type, Entity following,
            Vec3 at, int depth, MolangEnvironment.Queries queries, List<MolangExpression> preEffectScript) {
            this.definition = definition;
            this.type = type;
            this.following = following;
            this.at = at;
            this.depth = depth;

            if (queries != null) environment.setQueries(queries);
            randomise(environment, "emitter_random_");
            preEffectScript.forEach(statement -> statement.evaluate(environment));
            ParticleDefinition.Emitter.Initialization start = definition.emitter().initialization();
            if (start != null) start.creation().evaluate(environment);
            beginLoop(0f);
        }

        private float loopAge() {
            return age - loopStart;
        }

        /**
         * Starts a loop: its times are evaluated once here, its randoms are rolled again for every
         * loop after the first, and an instant emitter owes its burst again.
         */
        private void beginLoop(float start) {
            if (start > 0f) randomise(environment, "emitter_random_");
            loopStart = start;
            made = 0;
            environment.setVariable("emitter_age", MolangValue.ZERO);
            if (definition.emitter().rate() instanceof EmitterRate.Steady steady) {
                cap = (int) steady.maxParticles().asFloat(environment);
            }

            switch (definition.emitter().lifetime()) {
                case null -> awake = 1f;
                case EmitterLifetime.Once once -> awake = once.activeTime().asFloat(environment);
                case EmitterLifetime.Looping looping -> {
                    awake = Math.max(0.05f, looping.activeTime().asFloat(environment));
                    asleep = Math.max(0f, looping.sleepTime().asFloat(environment));
                }
                case EmitterLifetime.Expression ignored -> awake = 0f;
            }
        }

        /** Moves on to the next loop once a looping emitter has made particles and rested. */
        private boolean looped() {
            if (!(definition.emitter().lifetime() instanceof EmitterLifetime.Looping)
                || loopAge() < awake + asleep) {
                return false;
            }
            beginLoop(loopStart + awake + asleep);
            return true;
        }

        private Vec3 where() {
            return following == null ? at : following.position().add(at);
        }
    }

    /** Starts an effect at a point, or following an entity when one is given. */
    public static void start(String identifier, Entity following, Vec3 at) {
        start(identifier, following, at, 0, null, List.of());
    }

    /**
     * Starts an effect an entity's animation asked for: its emitter answers the entity's queries,
     * and the animation's `pre_effect_script` runs in the emitter as it starts.
     */
    public static void start(String identifier, Entity following, Vec3 at, MolangEnvironment.Queries queries,
        List<MolangExpression> preEffectScript) {
        start(identifier, following, at, 0, queries, preEffectScript);
    }

    private static void start(String identifier, Entity following, Vec3 at, int depth,
        MolangEnvironment.Queries queries, List<MolangExpression> preEffectScript) {
        SimpleParticleType type = AddonParticles.typeOf(identifier);
        ParticleDefinition definition = AddonParticles.definitionOf(identifier);
        if (type == null || definition == null || depth > MAXIMUM_DEPTH) return;

        Running effect = new Running(definition, type, following, at, depth, queries, preEffectScript);
        RUNNING.add(effect);
        raise(effect, definition.emitter().events().creation());
    }

    /**
     * Advances every running effect by one tick, dropping the ones that are over.
     *
     * An effect's events may start other effects as it advances, so the effects of this tick are
     * walked from a copy and anything started meanwhile first moves on the tick after.
     */
    public static void tick() {
        if (RUNNING.isEmpty() || Minecraft.getInstance().level == null) return;

        for (Running effect : List.copyOf(RUNNING)) {
            if (advance(effect)) continue;

            raise(effect, effect.definition.emitter().events().expiration());
            RUNNING.remove(effect);
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

    /**
     * The variables a particle starts with: the emitter's as they stand at its birth, so it reads
     * what the emitter's own expressions set and `v.emitter_age` as of that moment. A particle made
     * by no emitter of this mod starts with none.
     */
    public static MolangEnvironment birthScope() {
        return emitting == null ? new MolangEnvironment() : emitting.environment.copy();
    }

    /** Counts a particle the emitter making particles at this moment just made among its own. */
    public static void adopt(AddonParticle particle) {
        if (emitting != null) emitting.alive.add(particle);
    }

    /** Writes each curve's answer to the Molang variable it is named after. */
    public static void evaluateCurves(ParticleDefinition definition, MolangEnvironment environment) {
        for (Map.Entry<String, ParticleCurve> curve : definition.curves().entrySet()) {
            environment.setVariable(curve.getKey(), MolangValue.of(curve.getValue().at(environment)));
        }
    }

    /** Whether the effect is still going after this tick. */
    private static boolean advance(Running effect) {
        float was = effect.loopAge();
        effect.age += 1f / 20f;
        if (effect.age > MAXIMUM_SECONDS) return false;
        if (effect.following != null && effect.following.isRemoved()) return false;
        if (effect.looped()) was = 0f;

        effect.environment.setVariable("emitter_age", MolangValue.of(effect.loopAge()));
        effect.environment.setVariable("emitter_lifetime", MolangValue.of(effect.awake));
        effect.environment.setVariable("entity_scale", MolangValue.of(scale(effect.following)));
        evaluateCurves(effect.definition, effect.environment);

        ParticleDefinition.Emitter.Initialization start = effect.definition.emitter().initialization();
        if (start != null) start.perUpdate().evaluate(effect.environment);

        timeline(effect, was);
        travelled(effect);
        if (active(effect)) emit(effect);
        return !over(effect);
    }

    /** How much bigger than usual the entity the effect hangs off is drawn. */
    private static float scale(Entity following) {
        return following instanceof LivingEntity living ? living.getScale() : 1f;
    }

    /** Raises the timeline events the emitter passed over during this tick, on every loop. */
    private static void timeline(Running effect, float was) {
        for (ParticleEvent.Moment moment : effect.definition.emitter().events().timeline()) {
            if (moment.at() > was && moment.at() <= effect.loopAge()) raise(effect, moment.events());
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
     * A looping emitter makes particles for the start of each loop and sleeps for the rest, and an
     * expression emitter is told directly when to start and stop.
     */
    private static boolean active(Running effect) {
        return switch (effect.definition.emitter().lifetime()) {
            case null -> effect.loopAge() <= effect.awake;
            case EmitterLifetime.Once ignored -> effect.loopAge() <= effect.awake;
            case EmitterLifetime.Looping ignored -> effect.loopAge() < effect.awake;
            case EmitterLifetime.Expression expression ->
                expression.activation().evaluate(effect.environment).truthy();
        };
    }

    /** Whether the effect has finished for good, rather than merely pausing. */
    private static boolean over(Running effect) {
        return switch (effect.definition.emitter().lifetime()) {
            case null -> effect.age > effect.awake;
            case EmitterLifetime.Once ignored -> effect.age > effect.awake;
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

                effect.alive.removeIf(particle -> !particle.isAlive());
                yield effect.cap > 0 ? Math.min(count, Math.max(0, effect.cap - effect.alive.size())) : count;
            }
        };
        if (wanted <= 0) return;

        Vec3 where = effect.where();
        emitting = effect;
        try {
            for (int i = 0; i < Math.min(wanted, 256); i++) {
                Minecraft.getInstance().level.addParticle(effect.type, where.x, where.y, where.z, 0, 0, 0);
            }
        } finally {
            emitting = null;
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
        start(visual.effect(), carrier, where, depth + 1, null, List.of());
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
