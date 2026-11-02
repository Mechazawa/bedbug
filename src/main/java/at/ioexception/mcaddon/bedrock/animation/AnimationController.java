package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;

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

    /**
     * A particle effect a state starts as it is entered, the script run as its emitter starts, and
     * whether it follows the entity.
     */
    public record Effect(String effect, String locator, List<MolangExpression> preEffectScript, boolean bound) {
    }

    /**
     * A variable a state sets on every frame it is current, before its blend values are read: its
     * input, remapped through a curve of sorted points by linear interpolation and held at either end.
     * A variable with no curve takes its input as it is.
     */
    public record Variable(String name, MolangExpression input, float[] inputs, float[] outputs) {
        public void apply(MolangEnvironment environment) {
            float value = input.asFloat(environment);
            environment.setVariable(name, MolangValue.of(remap(value)));
        }

        private float remap(float value) {
            if (inputs.length == 0) return value;
            if (value <= inputs[0]) return outputs[0];

            for (int i = 1; i < inputs.length; i++) {
                if (value > inputs[i]) continue;
                float span = inputs[i] - inputs[i - 1];
                float progress = span <= 0f ? 1f : (value - inputs[i - 1]) / span;
                return outputs[i - 1] + (outputs[i] - outputs[i - 1]) * progress;
            }
            return outputs[outputs.length - 1];
        }
    }

    public record State(
        List<Variable> variables,
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
