package at.ioexception.mcaddon.bedrock.model;

import org.joml.Vector3f;

/**
 * Geometry a bone builds out of a texture rather than out of boxes: a voxel for every texel the
 * texture draws, one layer deep.
 *
 * The texture lies flat with its columns along X, its rows along Z and its depth running down,
 * opposite to Bedrock's Y. It is placed by scaling it, moving `localPivot` to the origin, turning it
 * the way a cube with the same rotation is turned and moving it to `position`, which measures X and
 * Z in the model and Y down from the bone's pivot.
 *
 * @param texture    the name the entity's own `textures` map gives the texture, not a path
 * @param pixelDepth whether the layer is one model unit deep, rather than as deep as a texel is wide
 */
public record BedrockTextureMesh(
    String texture,
    Vector3f position,
    Vector3f localPivot,
    Vector3f rotation,
    Vector3f scale,
    boolean pixelDepth
) {
}
