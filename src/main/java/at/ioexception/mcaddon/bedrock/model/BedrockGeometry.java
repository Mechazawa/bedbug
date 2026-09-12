package at.ioexception.mcaddon.bedrock.model;

import java.util.List;
import java.util.Map;

/** A parsed `geometry.*` entry: its texture size and its bones, in declaration order. */
public record BedrockGeometry(
    String identifier,
    int textureWidth,
    int textureHeight,
    List<BedrockBone> bones
) {
    public Map<String, BedrockBone> byName() {
        return bones.stream().collect(java.util.stream.Collectors.toMap(BedrockBone::name, bone -> bone));
    }
}
