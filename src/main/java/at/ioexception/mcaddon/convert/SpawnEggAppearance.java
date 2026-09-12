package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

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
    public static final SpawnEggAppearance DEFAULT =
        new SpawnEggAppearance("", null, 0x4C6B32, 0xA0A0A0);

    /** Null for an entity whose pack gave it no egg, which is most of them. */
    public static SpawnEggAppearance tryFrom(String identifier, JsonObject description) {
        JsonObject egg = description.getAsJsonObject("spawn_egg");
        if (egg == null) return null;

        return new SpawnEggAppearance(identifier,
            Json.text(egg, "texture", null),
            colour(Json.text(egg, "base_color", null), DEFAULT.baseColour()),
            colour(Json.text(egg, "overlay_color", null), DEFAULT.overlayColour()));
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
