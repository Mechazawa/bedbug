package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How one entity's spawn egg looks, as its client definition describes it.
 *
 * Bedrock allows two forms and a pack picks one: an item texture of its own, or a pair of colours
 * painting the vanilla egg. Java can show both, but by different means, so which one the pack chose
 * has to survive conversion.
 *
 * @param textureKey a key into `item_texture.json`, or null when the pack gave colours instead
 */
public record SpawnEggAppearance(String identifier, String textureKey, int baseColour, int overlayColour) {
    /** Bedrock's own colours for an egg whose pack named none, so an egg always has some. */
    private static final int DEFAULT_BASE = 0x4C6B32;
    private static final int DEFAULT_OVERLAY = 0xA0A0A0;

    public static Map<String, SpawnEggAppearance> readAll(BedrockPack pack) {
        Map<String, SpawnEggAppearance> eggs = new LinkedHashMap<>();
        for (Path file : pack.list("entity", ".json")) {
            try {
                JsonObject entity = Json.parseObject(file).getAsJsonObject("minecraft:client_entity");
                JsonObject description = entity == null ? null : entity.getAsJsonObject("description");
                JsonObject egg = description == null ? null : description.getAsJsonObject("spawn_egg");
                if (egg == null) continue;

                String identifier = Json.text(description, "identifier", null);
                if (identifier == null) continue;

                eggs.put(identifier, new SpawnEggAppearance(identifier,
                    Json.text(egg, "texture", null),
                    colour(Json.text(egg, "base_color", null), DEFAULT_BASE),
                    colour(Json.text(egg, "overlay_color", null), DEFAULT_OVERLAY)));
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read the spawn egg in {}.", file.getFileName());
            }
        }
        return Map.copyOf(eggs);
    }

    private static int colour(String hex, int fallback) {
        if (hex == null) return fallback;

        try {
            return Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
