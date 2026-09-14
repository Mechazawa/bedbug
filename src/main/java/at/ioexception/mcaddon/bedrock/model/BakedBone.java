package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import org.joml.Vector3f;

import java.util.List;

/**
 * A bone with its geometry already converted to Java model space.
 *
 * Positions are relative to the bone's own pivot, so drawing is translate to pivot, rotate, draw,
 * then recurse into the children.
 */
public record BakedBone(
    String name,
    Vector3f pivot,
    Vector3f rotation,
    /** Names a bone of whoever is carrying this model, which this bone hangs off instead. */
    MolangExpression binding,
    List<BakedQuad> quads,
    List<BakedBone> children
) {
}
