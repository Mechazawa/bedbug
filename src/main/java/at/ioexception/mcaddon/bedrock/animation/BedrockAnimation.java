package at.ioexception.mcaddon.bedrock.animation;

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
    float loopDelay,
    MolangExpression blendWeight,
    /** Replaces the default "advance by the frame time" rule, for a clip driven by something else. */
    MolangExpression animTimeUpdate,
    boolean overridePrevious,
    Map<String, BoneAnimation> bones,
    List<TimelineEntry> timeline
) {
    public enum Loop {
        ONCE,
        LOOP,
        /** Stops on the final frame and stays there, which is what a spoken line does. */
        HOLD_ON_LAST_FRAME
    }

    /** Statements to run once the animation passes `time`. */
    public record TimelineEntry(float time, List<MolangExpression> statements) {
    }

    public boolean hasBones() {
        return !bones.isEmpty();
    }

    /** Where in the animation a given elapsed time lands, honouring the loop mode. */
    public float positionAt(float elapsed) {
        if (length <= 0) return 0;
        return switch (loop) {
            case LOOP -> elapsed % (length + loopDelay);
            case HOLD_ON_LAST_FRAME -> Math.min(elapsed, length);
            case ONCE -> Math.min(elapsed, length);
        };
    }

    public boolean finished(float elapsed) {
        return loop != Loop.LOOP && elapsed >= length;
    }
}
