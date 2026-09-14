package at.ioexception.mcaddon.bedrock.animation;

import org.joml.Vector3f;

/**
 * What an animation has done to one bone, on top of its resting pose.
 *
 * Rotation and position add up across the animations playing at once; scale multiplies, because
 * two clips each halving a bone should quarter it rather than zero it.
 */
public final class BonePose {
    public final Vector3f rotation = new Vector3f();
    public final Vector3f position = new Vector3f();
    public final Vector3f scale = new Vector3f(1, 1, 1);

    public boolean rotationRelativeToEntity;

    /** The first rotation offered this frame, which a later one takes the short way round to meet. */
    private final Vector3f firstRotation = new Vector3f();
    private boolean rotated;

    public void reset() {
        rotation.set(0);
        position.set(0);
        scale.set(1, 1, 1);
        rotationRelativeToEntity = false;
        rotated = false;
    }

    /**
     * Adds one clip's share of a bone's rotation.
     *
     * `shortestPath` is what a controller state declaring `blend_via_shortest_path` asks for. The
     * angles are summed per axis, so 350 and 10 degrees at equal weight otherwise average to 180,
     * which points a bone backwards halfway through a transition that should not move it at all.
     * Turning the later angle to within half a circle of the first one gives 350 and 370 instead,
     * whose average is the 0 the pack meant.
     */
    public void addRotation(Vector3f value, float weight, boolean shortestPath) {
        if (!rotated) {
            firstRotation.set(value);
            rotated = true;
        } else if (shortestPath) {
            rotation.add(near(value.x, firstRotation.x) * weight,
                near(value.y, firstRotation.y) * weight,
                near(value.z, firstRotation.z) * weight);
            return;
        }
        rotation.add(value.x * weight, value.y * weight, value.z * weight);
    }

    /** The angle equal to `degrees` that lies within half a circle of `reference`. */
    private static float near(float degrees, float reference) {
        return degrees - 360f * Math.round((degrees - reference) / 360f);
    }

    public void addPosition(Vector3f value, float weight) {
        position.add(value.x * weight, value.y * weight, value.z * weight);
    }

    public void multiplyScale(Vector3f value, float weight) {
        scale.mul(
            1 + (value.x - 1) * weight,
            1 + (value.y - 1) * weight,
            1 + (value.z - 1) * weight);
    }

    public boolean isIdentity() {
        return rotation.equals(0, 0, 0)
            && position.equals(0, 0, 0)
            && scale.equals(1, 1, 1);
    }
}
