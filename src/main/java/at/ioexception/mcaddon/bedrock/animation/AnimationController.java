package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;

import java.util.List;
import java.util.Map;

/**
 * A state machine over animations.
 *
 * Each state names the clips it runs and the conditions that move it elsewhere. This is how the
 * add-on picks a death clip by a rolled variable, or fires a script event on a timer.
 */
public record AnimationController(
    String name,
    String initialState,
    Map<String, State> states
) {
    /** One entry in a state's animation list: a clip, and how strongly it applies. */
    public record Entry(String animation, MolangExpression weight) {
    }

    public record Transition(String target, MolangExpression condition) {
    }

    public record Effect(String effect, String locator) {
    }

    public record State(
        List<Entry> animations,
        List<Transition> transitions,
        List<AnimationAction> onEntry,
        List<AnimationAction> onExit,
        List<String> soundEffects,
        /** Each entry is an effect name and, where the state names one, the bone it is born at. */
        List<Effect> particleEffects,
        float blendTransition,
        /** Whether a rotation this state contributes takes the short way round to meet the others. */
        boolean blendViaShortestPath
    ) {
    }
}
