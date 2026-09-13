package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.animation.AnimationAction;
import at.ioexception.mcaddon.bedrock.animation.AnimationController;
import at.ioexception.mcaddon.bedrock.animation.AnimationPlayer;
import at.ioexception.mcaddon.bedrock.behaviour.AnimationScript;
import at.ioexception.mcaddon.bedrock.entity.EntityQueries;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Runs one entity's behaviour pack animation script.
 *
 * A behaviour pack's clip animates what the entity is rather than where its bones are: each moment
 * on the timeline raises one of the entity's own events, runs a command as the entity, or assigns a
 * Molang variable the controllers read back. The state machine picking those moments is the same one
 * a resource pack uses to pick a walk cycle, which is why both sides are read and run by the same
 * code.
 *
 * Only the entries the entity's `scripts.animate` lists run, and an entry written with a condition
 * runs only while that condition holds.
 */
public final class ServerAnimator {
    private static final float SECONDS_PER_TICK = 1f / 20f;

    private final Entity entity;
    private final BedrockAssets clips;
    private final AnimationScript script;
    private final AnimationPlayer player;

    /** Which state each controller sits in, and how long it has been there. */
    private final Map<String, StateOf> controllers = new HashMap<>();

    private static final class StateOf {
        String name;
        float seconds;
    }

    public ServerAnimator(Entity entity, BedrockAssets clips, AnimationScript script) {
        this.entity = entity;
        this.clips = clips;
        this.script = script;
        this.player = new AnimationPlayer(clips, entity);
        player.environment().setQueries(new EntityQueries(entity, player, EntityQueries.Drawn.NONE,
            () -> ServerHooks.state() == null ? Map.of() : ServerHooks.state().properties(entity.getUUID()),
            false));
    }

    public void tick() {
        MolangEnvironment environment = player.environment();
        environment.setLifeTime(entity.tickCount * SECONDS_PER_TICK);
        environment.setDeltaTime(SECONDS_PER_TICK);

        Set<String> wanted = new HashSet<>();
        for (AnimationScript.Gated entry : script.animate()) {
            if (entry.condition() != null && !entry.condition().evaluate(environment).truthy()) continue;
            collect(script.resolve(entry.name()), wanted);
        }

        // A clip the script stopped asking for this tick has been switched off rather than left.
        for (String playing : player.playing()) {
            if (!wanted.contains(playing)) player.stop(playing);
        }
        player.tick(SECONDS_PER_TICK);
    }

    private void collect(String resolved, Set<String> wanted) {
        AnimationController controller = clips.animationController(resolved);
        if (controller == null) {
            player.ensurePlaying(resolved, 1f);
            wanted.add(resolved);
            return;
        }
        runController(controller, wanted);
    }

    /**
     * Advances one controller: takes the first transition whose condition holds, then asks for the
     * clips its state names.
     *
     * A state's `on_entry` runs as it is reached and its `on_exit` as it is left, which is where a
     * behaviour pack puts the command or the event the state stands for. Blending has nothing to
     * blend here, so a state is left rather than crossed out of.
     */
    private void runController(AnimationController controller, Set<String> wanted) {
        MolangEnvironment environment = player.environment();
        boolean firstRun = !controllers.containsKey(controller.name());
        StateOf current = controllers.computeIfAbsent(controller.name(), name -> {
            StateOf fresh = new StateOf();
            fresh.name = controller.initialState();
            return fresh;
        });

        AnimationController.State state = controller.states().get(current.name);
        if (state == null) return;

        if (firstRun) run(state.onEntry(), environment);

        current.seconds += SECONDS_PER_TICK;
        environment.setStateTime(current.seconds);

        for (AnimationController.Transition transition : state.transitions()) {
            if (!transition.condition().evaluate(environment).truthy()) continue;
            if (!controller.states().containsKey(transition.target())) continue;

            run(state.onExit(), environment);
            current.name = transition.target();
            current.seconds = 0f;
            state = controller.states().get(current.name);
            run(state.onEntry(), environment);
            break;
        }

        for (AnimationController.Entry entry : state.animations()) {
            if (entry.weight() != null && !entry.weight().evaluate(environment).truthy()) continue;
            collect(script.resolve(entry.animation()), wanted);
        }
    }

    private void run(Iterable<AnimationAction> actions, MolangEnvironment environment) {
        for (AnimationAction action : actions) action.run(entity, environment);
    }
}
