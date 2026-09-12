package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.animation.AnimationController;
import at.ioexception.mcaddon.bedrock.animation.AnimationPlayer;
import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Runs one entity's whole animation script.
 *
 * Each frame the entity's `pre_animation` statements set up its variables, then the `animate` list
 * runs in order. An entry is either a clip, which contributes to the pose directly, or a controller,
 * which is a state machine that decides which clips to run.
 */
public final class EntityAnimator {
    private final BedrockAssets assets;
    private final ClientEntityDefinition definition;
    private final AnimationPlayer player;
    private final Map<String, ControllerState> controllers = new HashMap<>();

    /** Clips started outside the animate list, which the list must not switch off again. */
    private final Set<String> direct = new HashSet<>();

    private boolean initialised;

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
        ControllerState current = controllers.computeIfAbsent(controller.name(), name -> {
            ControllerState fresh = new ControllerState();
            fresh.state = controller.initialState();
            return fresh;
        });

        AnimationController.State state = controller.states().get(current.state);
        if (state == null) return;

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
            break;
        }

        for (AnimationController.Entry entry : state.animations()) {
            if (entry.weight() != null && !entry.weight().evaluate(environment).truthy()) continue;
            collect(entry.animation(), deltaSeconds, wanted);
        }
    }

    /** Plays a clip outside the controller graph, which is how a spoken line is delivered. */
    public void playDirect(String animationName) {
        player.play(animationName);
        direct.add(animationName);
    }

    public BonePose poseOf(String bone) {
        return player.poseOf(bone);
    }

    public static float secondsPerTick() {
        return AnimationPlayer.secondsPerTick();
    }

    public float scale() {
        MolangExpression scale = definition.scale();
        return scale == null ? 1f : scale.asFloat(player.environment());
    }
}
