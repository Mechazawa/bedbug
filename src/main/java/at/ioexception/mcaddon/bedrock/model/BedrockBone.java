package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * A bone of a Bedrock model: a pivot, an optional resting rotation, and the boxes and texture meshes
 * hung off it.
 *
 * The bone's own `mirror` and `inflate` are resolved onto each cube while reading, because a cube
 * declaring either replaces the bone's value rather than adding to it. `neverRender` keeps the bone
 * as a place to hang children and animations off without drawing it.
 */
public record BedrockBone(
    String name,
    String parent,
    Vector3f pivot,
    Vector3f rotation,
    boolean neverRender,
    /** Names a bone of whoever is carrying this model, which this bone hangs off instead. */
    MolangExpression binding,
    List<BedrockCube> cubes,
    List<BedrockTextureMesh> textureMeshes,
    Map<String, Vector3f> locators
) {
}
