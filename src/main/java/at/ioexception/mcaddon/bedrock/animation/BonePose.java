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

    public void reset() {
        rotation.set(0);
        position.set(0);
        scale.set(1, 1, 1);
        rotationRelativeToEntity = false;
    }

    public void addRotation(Vector3f value, float weight) {
        rotation.add(value.x * weight, value.y * weight, value.z * weight);
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
