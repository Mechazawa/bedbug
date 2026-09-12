package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One Bedrock pack after conversion: a directory the game can read as a resource pack, plus the
 * lookups the rest of the mod needs.
 *
 * @param namespace      the namespace the pack's own assets were written under
 * @param texturePaths   Bedrock texture path, extension dropped, to the Java resource id
 * @param emissiveLayers those of them that a physically based texture set said have glowing pixels
 * @param textureKeys    `item_texture.json` and `terrain_texture.json` keys to Java resource ids
 * @param soundEvents    every sound event the pack defines, as `namespace:path`
 */
public record ConvertedPack(BedrockPack pack, Path root, String namespace,
    Map<String, String> texturePaths, Map<String, String> emissiveLayers,
    Map<String, String> textureKeys, List<String> soundEvents) {

    public String displayName() {
        return pack.displayName();
    }
}
