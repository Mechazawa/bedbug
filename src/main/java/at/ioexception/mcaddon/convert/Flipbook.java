package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry of `textures/flipbook_textures.json`, as the animation metadata Java reads.
 *
 * Both editions animate a texture from a vertical strip of square frames, so the file that says
 * which textures are strips is the whole difference between an animated texture and a stretched
 * one. Bedrock states it centrally and Java states it in a `.mcmeta` beside each texture.
 */
public record Flipbook(String texture, JsonObject animation) {
    /** What Bedrock's flipbook entry says that Java's animation metadata has no form of. */
    private static final Map<String, String> NO_JAVA_FORM = Map.of(
        "replicate", "each pixel of the strip drawn more than once",
        "atlas_index", "one variant of an atlas tile picked by number",
        "atlas_tile_variant", "one variant of an atlas tile picked by name");

    /** Null where the entry names no texture, which leaves nothing to animate. */
    public static Flipbook tryFrom(JsonObject entry) {
        String texture = Json.text(entry, "flipbook_texture", null);
        if (texture == null) return null;

        JsonObject animation = new JsonObject();
        if (entry.has("ticks_per_frame")) {
            animation.addProperty("frametime", (int) Json.number(entry, "ticks_per_frame", 1));
        }
        if (entry.has("blend_frames")) {
            animation.addProperty("interpolate", entry.get("blend_frames").getAsBoolean());
        }

        JsonElement frames = entry.get("frames");
        if (frames != null && frames.isJsonArray()) {
            JsonArray order = new JsonArray();
            frames.getAsJsonArray().forEach(order::add);
            animation.add("frames", order);
        }

        NO_JAVA_FORM.forEach((field, describes) -> {
            if (entry.has(field)) AddonEntities.unsupported("flipbook " + field + ", " + describes);
        });
        return new Flipbook(texture, animation);
    }

    /** Every flipbook the pack declares, by the pack-relative texture path each animates. */
    public static Map<String, Flipbook> readAll(BedrockPack pack) {
        Map<String, Flipbook> found = new LinkedHashMap<>();
        Path file = pack.file("textures/flipbook_textures.json");
        if (!Files.isRegularFile(file)) return found;

        try {
            for (JsonElement element : Json.parseArray(file)) {
                if (!element.isJsonObject()) continue;
                Flipbook flipbook = tryFrom(element.getAsJsonObject());
                if (flipbook != null) found.put(flipbook.texture(), flipbook);
            }
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read flipbook_textures.json: {}", error.toString());
        }
        return found;
    }

    /**
     * Writes the metadata beside the converted texture, where the image is a strip of square frames.
     *
     * Java reads the frame height off the image's own width and refuses an image whose height is
     * not a whole number of frames, and it refuses the whole texture rather than the animation. A
     * pack naming a texture that is not a strip gets the still image it shipped.
     */
    public void write(Path texture) throws IOException {
        if (!isStrip(texture)) {
            AddonEntities.unsupported("flipbook on a texture that is not a strip of square frames");
            return;
        }

        JsonObject meta = new JsonObject();
        meta.add("animation", animation);
        Files.writeString(texture.resolveSibling(texture.getFileName() + ".mcmeta"), meta.toString());
    }

    /** Reads the header alone, because a pack's whole texture directory goes past here. */
    private static boolean isStrip(Path texture) {
        try (javax.imageio.stream.ImageInputStream stream =
                 javax.imageio.ImageIO.createImageInputStream(texture.toFile())) {
            java.util.Iterator<javax.imageio.ImageReader> readers =
                javax.imageio.ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return false;

            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > width && height % width == 0;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}
