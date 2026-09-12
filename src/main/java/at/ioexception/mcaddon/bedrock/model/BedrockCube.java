package at.ioexception.mcaddon.bedrock.model;

import org.joml.Vector3f;

/**
 * One box of a bone.
 *
 * `inflate` grows the box on every axis without moving its origin, which is how Bedrock layers a
 * second skin over a body. A cube may also carry its own pivot and rotation, independent of the
 * bone it belongs to.
 */
public record BedrockCube(
    Vector3f origin,
    Vector3f size,
    float inflate,
    boolean mirror,
    Vector3f pivot,
    Vector3f rotation,
    CubeUv uv
) {
    public boolean isRotated() {
        return rotation.x != 0 || rotation.y != 0 || rotation.z != 0;
    }
}
