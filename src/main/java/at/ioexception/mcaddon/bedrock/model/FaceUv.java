package at.ioexception.mcaddon.bedrock.model;

/**
 * One face's patch of the texture.
 *
 * `size` may be negative, which flips the face. Bedrock uses that instead of a mirror flag on the
 * individual face.
 */
public record FaceUv(float u, float v, float width, float height, int rotation) {
}
