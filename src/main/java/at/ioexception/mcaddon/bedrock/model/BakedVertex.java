package at.ioexception.mcaddon.bedrock.model;

/** A corner of a quad: a position in model pixels and a normalised texture coordinate. */
public record BakedVertex(float x, float y, float z, float u, float v) {
}
