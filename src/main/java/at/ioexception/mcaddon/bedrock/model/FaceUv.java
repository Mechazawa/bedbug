package at.ioexception.mcaddon.bedrock.model;

/**
 * One face's patch of the texture.
 *
 * `size` may be negative, which flips the face. Bedrock uses that instead of a mirror flag on the
 * individual face.
 *
 * @param materialInstance the material instance a block's own `minecraft:material_instances` names
 *                         this face's texture under, or null for the one its direction picks
 */
public record FaceUv(float u, float v, float width, float height, int rotation,
    String materialInstance) {
}
