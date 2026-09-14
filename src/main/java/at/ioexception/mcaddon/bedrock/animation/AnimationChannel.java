package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import org.joml.Vector3f;

import java.util.List;

/**
 * One animated property of one bone over time.
 *
 * A channel is either a single value that holds for the whole animation, or keyframes sorted by
 * time. A keyframe's lerp mode governs the segment leaving it, which is how Blockbench authors it.
 */
public record AnimationChannel(List<Keyframe> keyframes) {
    public boolean isEmpty() {
        return keyframes.isEmpty();
    }

    /** The time of the last keyframe, which is zero for one value held for the whole animation. */
    public float end() {
        return keyframes.isEmpty() ? 0f : keyframes.get(keyframes.size() - 1).time();
    }

    public Vector3f evaluate(float time, MolangEnvironment environment, Vector3f current, Vector3f into) {
        environment.setKeyFrameLerpTime(0);
        if (keyframes.isEmpty()) return into.set(0, 0, 0);
        if (keyframes.size() == 1) return keyframes.get(0).post().evaluate(environment, current, into);

        int next = 0;
        while (next < keyframes.size() && keyframes.get(next).time() <= time) next += 1;

        if (next == 0) return keyframes.get(0).pre().evaluate(environment, current, into);
        if (next >= keyframes.size()) {
            return keyframes.get(keyframes.size() - 1).post().evaluate(environment, current, into);
        }

        Keyframe from = keyframes.get(next - 1);
        Keyframe to = keyframes.get(next);
        float span = to.time() - from.time();
        float progress = span <= 0 ? 0 : (time - from.time()) / span;
        // A keyframe's own expression may ask how far the segment leaving it has run.
        environment.setKeyFrameLerpTime(progress);

        if (from.lerpMode() == LerpMode.CATMULLROM) {
            return catmullRom(next - 1, next, progress, environment, current, into);
        }

        Vector3f start = from.post().evaluate(environment, current, new Vector3f());
        Vector3f end = to.pre().evaluate(environment, current, new Vector3f());
        return into.set(start).lerp(end, progress);
    }

    /**
     * A Catmull-Rom spline needs a keyframe either side of the pair being crossed. At the ends of
     * the channel the nearest keyframe stands in for the missing one.
     */
    private Vector3f catmullRom(
        int fromIndex, int toIndex, float progress, MolangEnvironment environment, Vector3f current, Vector3f into
    ) {
        Vector3f before = keyframes.get(Math.max(0, fromIndex - 1)).post()
            .evaluate(environment, current, new Vector3f());
        Vector3f start = keyframes.get(fromIndex).post().evaluate(environment, current, new Vector3f());
        Vector3f end = keyframes.get(toIndex).pre().evaluate(environment, current, new Vector3f());
        Vector3f after = keyframes.get(Math.min(keyframes.size() - 1, toIndex + 1))
            .pre().evaluate(environment, current, new Vector3f());

        return into.set(
            spline(before.x, start.x, end.x, after.x, progress),
            spline(before.y, start.y, end.y, after.y, progress),
            spline(before.z, start.z, end.z, after.z, progress));
    }

    private static float spline(float before, float start, float end, float after, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2 * start)
            + (-before + end) * t
            + (2 * before - 5 * start + 4 * end - after) * t2
            + (-before + 3 * start - 3 * end + after) * t3);
    }
}
