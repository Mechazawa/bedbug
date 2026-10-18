package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.animation.AnimationAction;
import at.ioexception.mcaddon.bedrock.animation.AnimationController;
import at.ioexception.mcaddon.bedrock.animation.AnimationPlayer;
import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.bedrock.model.BedrockBone;
import at.ioexception.mcaddon.bedrock.model.BedrockGeometry;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one entity's whole animation script.
 *
 * Each frame the entity's `pre_animation` statements set up its variables, then its controllers and
 * its `animate` list run in order. An entry is either a clip, which contributes to the pose
 * directly, or a controller, which is a state machine that decides which clips to run.
 */
public final class EntityAnimator {
    private final BedrockAssets assets;
    private final Entity entity;
    private final ClientEntityDefinition definition;
    private final AnimationPlayer player;
    private final Map<String, ControllerState> controllers = new HashMap<>();

    /** The model a query reads, flattened once, because a query is asked again on every frame. */
    private final Map<String, BedrockBone> bonesByName = new HashMap<>();
    private final Map<String, Vector3f> locators = new HashMap<>();

    /** Clips started outside the animate list, which the list must not switch off again. */
    private final Set<String> direct = new HashSet<>();

    private boolean initialised;
    private MolangEnvironment published;
    private AnimationPlayer.EffectSink listener = AnimationPlayer.EffectSink.NONE;

    /**
     * Names every effect through this entity's own definition before passing it on.
     *
     * A pack writes short names that only the entity's definition turns into a sound event or a
     * particle identifier, so whoever listens is handed real ids.
     */
    public void setEffects(AnimationPlayer.EffectSink effects) {
        listener = effects;
        player.setEffects(new AnimationPlayer.EffectSink() {
            @Override
            public void sound(String effect) {
                listener.sound(definition.resolveSound(effect));
            }

            @Override
            public void particle(String effect, String locator) {
                listener.particle(definition.resolveParticle(effect), locator);
            }
        });
    }

    /** Where one controller currently sits, how long it has been there, and what it is leaving. */
    private static final class ControllerState {
        String state;
        float timeInState;

        /** The state being crossed out of, kept running until its own blend transition is over. */
        AnimationController.State leaving;
        float crossFadeSeconds;
        float crossFadeLength;

        /** How strongly the state being left still applies, which falls to zero over its length. */
        float weightOfLeaving() {
            return crossFadeLength <= 0f ? 0f : crossFadeSeconds / crossFadeLength;
        }
    }

    public EntityAnimator(BedrockAssets assets, Entity entity, ClientEntityDefinition definition) {
        this.assets = assets;
        this.entity = entity;
        this.definition = definition;
        this.player = new AnimationPlayer(assets, entity);

        // A render controller picks a geometry per pass out of the same list, so a query asked before
        // any pass is resolved reads the one the entity names `default`, or its first when it names
        // none. Bedrock calls that the queryable geometry.
        String named = definition.geometry().getOrDefault("default",
            definition.geometry().values().stream().findFirst().orElse(null));
        BedrockGeometry geometry = named == null ? null : assets.geometry(named);
        if (geometry == null) return;

        for (BedrockBone bone : geometry.bones()) {
            bonesByName.putIfAbsent(bone.name(), bone);
            bone.locators().forEach(locators::putIfAbsent);
        }
    }

    public MolangEnvironment environment() {
        return player.environment();
    }

    /**
     * This entity's scope as whatever it is carrying may read it.
     *
     * Built once, because a link is followed on every frame and the view behind it holds no state
     * of its own.
     */
    public MolangEnvironment published() {
        if (published == null) published = environment().publishedAs(definition.publishedVariables());
        return published;
    }

    public AnimationPlayer player() {
        return player;
    }

    public ClientEntityDefinition definition() {
        return definition;
    }

    public void tick(float deltaSeconds, float lifeTimeSeconds) {
        MolangEnvironment environment = player.environment();
        environment.setLifeTime(lifeTimeSeconds);
        environment.setDeltaTime(deltaSeconds);

        if (!initialised) {
            for (MolangExpression statement : definition.initialize()) statement.evaluate(environment);
            initialised = true;
        }
        for (MolangExpression statement : definition.preAnimation()) statement.evaluate(environment);

        Set<String> wanted = new HashSet<>();
        // A controller named in the definition itself runs on every frame, ahead of the animate list.
        for (String controller : definition.animationControllers().values()) {
            collect(controller, deltaSeconds, 1f, false, wanted);
        }
        for (ClientEntityDefinition.Gated entry : definition.animate()) {
            float blend = blendOf(entry.condition());
            if (blend > 0f) collect(entry.name(), deltaSeconds, blend, false, wanted);
        }

        // A clip nobody asked for this frame has been switched off, so it stops rather than lingers.
        for (String playing : player.playing()) {
            if (!wanted.contains(playing) && !direct.contains(playing)) player.stop(playing);
        }
        direct.removeIf(name -> !player.isPlaying(name));
        player.tick(deltaSeconds);
    }

    private void collect(String shortName, float deltaSeconds, float weight, boolean shortestPath,
        Set<String> wanted) {
        String resolved = definition.resolveAnimation(shortName);

        AnimationController controller = assets.animationController(resolved);
        if (controller == null) {
            player.ensurePlaying(resolved, weight, shortestPath);
            wanted.add(resolved);
            return;
        }
        runController(controller, deltaSeconds, weight, wanted);
    }

    /**
     * Advances one controller: takes the first transition whose condition holds, then asks for the
     * clips its current state names.
     *
     * A state declaring a `blend_transition` is crossed out of rather than left, so its clips keep
     * running at a falling weight while the state it moved to comes up at a rising one.
     *
     * A controller moves at most once a frame, and entering its first state is that frame's move: the
     * first state's `on_entry` runs and its clips play before any of its transitions is asked, so a
     * first state that leaves on `1` still sets what its `on_entry` sets and starts its clips once.
     */
    private void runController(AnimationController controller, float deltaSeconds, float weight,
        Set<String> wanted) {
        MolangEnvironment environment = player.environment();
        boolean firstRun = !controllers.containsKey(controller.name());
        ControllerState current = controllers.computeIfAbsent(controller.name(), name -> {
            ControllerState fresh = new ControllerState();
            fresh.state = controller.initialState();
            return fresh;
        });

        AnimationController.State state = controller.states().get(current.state);
        if (state == null) return;

        if (firstRun) {
            for (AnimationAction statement : state.onEntry()) statement.run(entity, environment);
            fireStateEffects(state);
        }

        current.timeInState += deltaSeconds;
        current.crossFadeSeconds = Math.max(0f, current.crossFadeSeconds - deltaSeconds);
        environment.setStateTime(current.timeInState);

        // Whether the clips have finished is asked of the state's own clips rather than of every
        // clip the entity runs, so a looping idle beside them cannot hold a state forever.
        player.scopeFinishedTo(state.animations().stream()
            .map(entry -> definition.resolveAnimation(entry.animation()))
            .filter(name -> assets.animationController(name) == null).toList());
        for (AnimationController.Transition transition : firstRun ? List.<AnimationController.Transition>of()
            : state.transitions()) {
            if (!transition.condition().evaluate(environment).truthy()) continue;
            if (!controller.states().containsKey(transition.target())) continue;

            for (AnimationAction statement : state.onExit()) statement.run(entity, environment);
            current.leaving = state;
            current.crossFadeSeconds = state.blendTransition();
            current.crossFadeLength = state.blendTransition();
            current.state = transition.target();
            current.timeInState = 0f;
            state = controller.states().get(current.state);
            for (AnimationAction statement : state.onEntry()) statement.run(entity, environment);
            fireStateEffects(state);
            break;
        }
        player.scopeFinishedTo(null);

        // A crossing is shaped by the state being left, whose blend_transition decides how long it
        // takes, so both sides of it take the short way round when that state asks for it.
        float leaving = current.weightOfLeaving();
        if (leaving <= 0f) current.leaving = null;
        boolean shortestPath = current.leaving == null
            ? state.blendViaShortestPath() : current.leaving.blendViaShortestPath();
        if (current.leaving != null) {
            playState(current.leaving, deltaSeconds, weight * leaving, shortestPath, wanted);
        }
        playState(state, deltaSeconds, weight * (1f - leaving), shortestPath, wanted);
    }

    /** The clips one controller state asks for, each at its own blend value times the state's weight. */
    private void playState(AnimationController.State state, float deltaSeconds, float weight,
        boolean shortestPath, Set<String> wanted) {
        for (AnimationController.Entry entry : state.animations()) {
            float blend = blendOf(entry.weight());
            if (blend > 0f) collect(entry.animation(), deltaSeconds, weight * blend, shortestPath, wanted);
        }
    }

    /**
     * How strongly an entry of an animate list or of a controller state applies.
     *
     * The expression beside a name is a blend value rather than a condition: Mojang's own walk
     * clips are written `{"walk": "query.modified_move_speed"}` so a slow mob only half walks. A
     * value at or below zero leaves the clip off, and a string counts as the condition it reads as.
     */
    private float blendOf(MolangExpression expression) {
        if (expression == null) return 1f;

        MolangValue value = expression.evaluate(player.environment());
        if (value.isText()) return value.truthy() ? 1f : 0f;
        return Math.max(0f, value.asFloat());
    }

    /** A controller state's own effects, which fire once as the state is entered. */
    private void fireStateEffects(AnimationController.State state) {
        for (String effect : state.soundEffects()) listener.sound(definition.resolveSound(effect));
        for (AnimationController.Effect effect : state.particleEffects()) {
            listener.particle(definition.resolveParticle(effect.effect()), effect.locator());
        }
    }

    /**
     * Plays a clip outside the controller graph, which is how a spoken line is delivered.
     *
     * `/playanimation` is where the other two arguments come from: the pack says when the clip ends
     * and how long it takes to let go.
     */
    public void playDirect(String animationName, String stopExpression, float blendOutSeconds) {
        MolangExpression stop = stopExpression == null || stopExpression.isBlank()
            ? null : MolangParser.parse(stopExpression);
        String resolved = definition.resolveAnimation(animationName);
        player.play(resolved, stop, blendOutSeconds);
        direct.add(resolved);
    }

    public BonePose poseOf(String bone) {
        return player.poseOf(bone);
    }

    /** One bone of the model as the pack modelled it, or null when the model has no such bone. */
    public BedrockBone bone(String name) {
        return bonesByName.get(name);
    }

    /** Where a named locator sits in the model, whichever bone carries it. */
    public Vector3f locator(String name) {
        return locators.get(name);
    }

    public static float secondsPerTick() {
        return AnimationPlayer.secondsPerTick();
    }

    /**
     * How far the whole model is grown on each axis.
     *
     * A pack may say it once for all three or once per axis, and the two multiply, so a slime that
     * grows with age and squashes as it lands does both at the same time.
     */
    public Vector3f scale() {
        MolangEnvironment environment = player.environment();
        float uniform = number(definition.scale(), environment);
        return new Vector3f(
            uniform * number(definition.scaleX(), environment),
            uniform * number(definition.scaleY(), environment),
            uniform * number(definition.scaleZ(), environment));
    }

    private static float number(MolangExpression expression, MolangEnvironment environment) {
        return expression == null ? 1f : expression.asFloat(environment);
    }
}
