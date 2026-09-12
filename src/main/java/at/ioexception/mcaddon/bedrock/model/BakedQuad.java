package at.ioexception.mcaddon.bedrock.model;

import org.joml.Vector3f;

/** One face, ready to draw: four corners wound anticlockwise seen from outside, plus its normal. */
public record BakedQuad(BakedVertex[] vertices, Vector3f normal) {
}
