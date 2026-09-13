package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.animation.AnimationController;
import at.ioexception.mcaddon.bedrock.animation.AnimationPlayer;
import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
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
    private final ClientEntityDefinition definition;
    private final AnimationPlayer player;
    private final Map<String, ControllerState> controllers = new HashMap<>();

    /** Clips started outside the animate list, which the list must not switch off again. */
    private final Set<String> direct = new HashSet<>();

    private boolean initialised;
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

    /** Where one controller currently sits, and how long it has been there. */
    private static final class ControllerState {
        String state;
        float timeInState;
    }

    public EntityAnimator(BedrockAssets assets, ClientEntityDefinition definition) {
        this.assets = assets;
        this.definition = definition;
        this.player = new AnimationPlayer(assets);
    }

    public MolangEnvironment environment() {
        return player.environment();
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
            collect(controller, deltaSeconds, wanted);
        }
        for (ClientEntityDefinition.Gated entry : definition.animate()) {
            if (entry.condition() != null && !entry.condition().evaluate(environment).truthy()) continue;
            collect(entry.name(), deltaSeconds, wanted);
        }

        // A clip nobody asked for this frame has been switched off, so it stops rather than lingers.
        for (String playing : player.playing()) {
            if (!wanted.contains(playing) && !direct.contains(playing)) player.stop(playing);
        }
        direct.removeIf(name -> !player.isPlaying(name));
        player.tick(deltaSeconds);
    }

    private void collect(String shortName, float deltaSeconds, Set<String> wanted) {
        String resolved = definition.resolveAnimation(shortName);

        AnimationController controller = assets.animationController(resolved);
        if (controller == null) {
            player.ensurePlaying(resolved);
            wanted.add(resolved);
            return;
        }
        runController(controller, deltaSeconds, wanted);
    }

    /**
     * Advances one controller: takes the first transition whose condition holds, then asks for the
     * clips its current state names.
     */
    private void runController(AnimationController controller, float deltaSeconds, Set<String> wanted) {
        MolangEnvironment environment = player.environment();
        boolean firstRun = !controllers.containsKey(controller.name());
        ControllerState current = controllers.computeIfAbsent(controller.name(), name -> {
            ControllerState fresh = new ControllerState();
            fresh.state = controller.initialState();
            return fresh;
        });

        AnimationController.State state = controller.states().get(current.state);
        if (state == null) return;

        if (firstRun) fireStateEffects(state);

        current.timeInState += deltaSeconds;
        environment.setStateTime(current.timeInState);

        for (AnimationController.Transition transition : state.transitions()) {
            if (!transition.condition().evaluate(environment).truthy()) continue;
            if (!controller.states().containsKey(transition.target())) continue;

            for (MolangExpression statement : state.onExit()) statement.evaluate(environment);
            current.state = transition.target();
            current.timeInState = 0f;
            state = controller.states().get(current.state);
            for (MolangExpression statement : state.onEntry()) statement.evaluate(environment);
            fireStateEffects(state);
            break;
        }

        for (AnimationController.Entry entry : state.animations()) {
            if (entry.weight() != null && !entry.weight().evaluate(environment).truthy()) continue;
            collect(entry.animation(), deltaSeconds, wanted);
        }
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
