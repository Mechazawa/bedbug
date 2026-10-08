package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One Bedrock pack after conversion: a directory the game can read as a resource pack, plus the
 * lookups the rest of the mod needs.
 *
 * @param behaviours     the behaviour packs that came out of the same file as this one
 * @param namespace      the namespace the pack's own assets were written under
 * @param texturePaths   Bedrock texture path, extension dropped, to the Java resource id
 * @param emissiveLayers those of them that a physically based texture set said have glowing pixels
 * @param textureKeys    `item_texture.json` and `terrain_texture.json` keys to Java resource ids
 * @param soundEvents    every sound event the pack defines, as `namespace:path`
 * @param spawnEggs      how each entity's egg looks, by the entity's identifier
 */
public record ConvertedPack(BedrockPack pack, List<BedrockPack> behaviours, Path root,
    String namespace, Map<String, String> texturePaths, Map<String, String> emissiveLayers,
    Map<String, String> textureKeys, List<String> soundEvents,
    Map<String, SpawnEggAppearance> spawnEggs) {

    public String displayName() {
        return pack.displayName();
    }

    /** The image the conversion wrote for a Bedrock texture path, or null where it wrote none. */
    public Path textureFile(String bedrockPath) {
        String mapped = texturePaths.get(bedrockPath);
        if (mapped == null) return null;

        int colon = mapped.indexOf(':');
        Path file = root.resolve("assets").resolve(mapped.substring(0, colon)).resolve("textures")
            .resolve(mapped.substring(colon + 1) + ".png");
        return Files.isRegularFile(file) ? file : null;
    }
}
