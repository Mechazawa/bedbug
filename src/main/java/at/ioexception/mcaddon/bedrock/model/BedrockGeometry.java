package at.ioexception.mcaddon.bedrock.model;

import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/** A parsed `geometry.*` entry: its texture size and its bones, in declaration order. */
public record BedrockGeometry(
    String identifier,
    int textureWidth,
    int textureHeight,
    /**
     * The box the model fills, with the entity's feet at the origin. Null where the pack declared
     * none, which leaves the entity culled against the box the server gave it.
     */
    AABB visibleBounds,
    List<BedrockBone> bones
) {
    public Map<String, BedrockBone> byName() {
        return bones.stream().collect(java.util.stream.Collectors.toMap(BedrockBone::name, bone -> bone));
    }
}
