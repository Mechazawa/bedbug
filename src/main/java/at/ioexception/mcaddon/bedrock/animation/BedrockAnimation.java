package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;

import java.util.List;
import java.util.Map;

/**
 * One animation: what each bone does over its length, plus the statements fired at given moments.
 *
 * Most of this pack's animations have no bones at all. They exist only for their timeline, which
 * assigns the variables that drive a talking villager's face.
 */
public record BedrockAnimation(
    String name,
    float length,
    Loop loop,
    /** Evaluated after each loop, so a clip may wait a different amount before each repeat. */
    MolangExpression loopDelay,
    /** Evaluated once as the clip is started, and again whenever it is restarted from the top. */
    MolangExpression startDelay,
    MolangExpression blendWeight,
    /** Replaces the default "advance by the frame time" rule, for a clip driven by something else. */
    MolangExpression animTimeUpdate,
    boolean overridePrevious,
    Map<String, BoneAnimation> bones,
    List<TimelineEntry> timeline,
    List<EffectEntry> soundEffects,
    List<EffectEntry> particleEffects
) {
    public enum Loop {
        ONCE,
        LOOP,
        /** Stops on the final frame and stays there, which is what a spoken line does. */
        HOLD_ON_LAST_FRAME
    }

    /** Statements to run once the animation passes `time`. */
    public record TimelineEntry(float time, List<AnimationAction> statements) {
    }

    /**
     * A sound or a particle the animation asks for at a given moment.
     *
     * The name is a short one the entity's own `sound_effects` or `particle_effects` map turns into
     * an id, and `locator` names the bone a particle is born at. A sound has no locator.
     */
    public record EffectEntry(float time, String effect, String locator,
        List<MolangExpression> preEffectScript) {
    }

    public boolean hasBones() {
        return !bones.isEmpty();
    }

    /**
     * Where in the animation a given elapsed time lands, honouring the loop mode.
     *
     * A loop delay cannot be worked out from the elapsed time, because it is evaluated once per
     * loop, so holding a clip through one belongs to whoever runs its clock.
     */
    public float positionAt(float elapsed) {
        if (length <= 0) return 0;
        if (loop != Loop.LOOP) return Math.min(Math.max(elapsed, 0f), length);
        return elapsed - length * (float) Math.floor(elapsed / length);
    }

    public float loopDelaySeconds(MolangEnvironment environment) {
        return loopDelay == null ? 0f : loopDelay.asFloat(environment);
    }

    public float startDelaySeconds(MolangEnvironment environment) {
        return startDelay == null ? 0f : startDelay.asFloat(environment);
    }

    public boolean finished(float elapsed) {
        return loop != Loop.LOOP && elapsed >= length;
    }
}
