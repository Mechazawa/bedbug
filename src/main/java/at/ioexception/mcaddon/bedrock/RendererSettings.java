package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * A kind of settings file for Bedrock's deferred renderer whose every setting is named against the
 * pack rather than converted.
 *
 * `water/` sets the concentration of three optical particles, the caustics texture and the shape of
 * the waves, all of them terms of a water surface Java draws from a texture and a biome colour; the
 * surface colour itself is `minecraft:water_appearance` on the client biome, which is read.
 *
 * Naming the directory would say a pack shipped one of these and stop there. Naming each setting
 * says which of them the author wrote and will not see, which is what they need to know.
 */
public record RendererSettings(String directory, String root, String label) {

    public static final RendererSettings WATER =
        new RendererSettings("water", "minecraft:water_settings", "water");

    /** What every settings file carries about itself rather than about the renderer. */
    private static final Set<String> ABOUT_ITSELF = Set.of("format_version", "description");

    /** Names every setting the pack's files of this kind carry. */
    public void report(BedrockPack pack) {
        for (Path file : pack.list(directory, ".json")) {
            try {
                report(Json.parseObject(file).getAsJsonObject(root));
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
    }

    /**
     * Names what one file carries, one level down through any group of settings.
     *
     * A block holding only blocks is a group, so its contents are named and the block itself is not.
     * A block whose own values are settings, such as the caustics of a water file, is the setting.
     */
    private void report(JsonObject body) {
        if (body == null) return;

        body.entrySet().stream().filter(entry -> !ABOUT_ITSELF.contains(entry.getKey()))
            .forEach(entry -> {
                if (grouping(entry.getValue())) {
                    entry.getValue().getAsJsonObject().keySet().forEach(this::name);
                } else {
                    name(entry.getKey());
                }
            });
    }

    private void name(String setting) {
        AddonEntities.unsupported(label + " " + setting);
    }

    private static boolean grouping(JsonElement value) {
        if (!value.isJsonObject()) return false;

        Map<String, JsonElement> members = value.getAsJsonObject().asMap();
        return !members.isEmpty() && members.values().stream().allMatch(JsonElement::isJsonObject);
    }
}
