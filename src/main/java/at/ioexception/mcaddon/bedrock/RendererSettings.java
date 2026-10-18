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
 * Two of the four kinds a resource pack may ship state nothing Java's renderer takes. `water/` is
 * read by Bedrock under Vibrant Visuals alone. Its engine turns the three particle concentrations
 * into the per-channel extinction and albedo its shaders take (`WaterExtinctionCoefficients`,
 * `WaterAlbedoExtinction`) by a model Mojang does not publish, its waves are surface normals only
 * its deferred lighting reads, and its default caustics texture is built into the game. Every
 * other graphics mode, which is what Java draws like, colours water by
 * `minecraft:water_appearance` on the client biome, and that is read. `color_grading/`
 * sets the contrast, gamma, gain, saturation and colour temperature of the finished frame and the
 * tonemapping operator that maps it back into range, which is a post-process Java has no
 * data-driven form of at all.
 *
 * Naming the directory would say a pack shipped one of these and stop there. Naming each setting
 * says which of them the author wrote and will not see, which is what they need to know.
 */
public record RendererSettings(String directory, String root, String label) {

    public static final RendererSettings WATER =
        new RendererSettings("water", "minecraft:water_settings", "water");

    public static final RendererSettings COLOR_GRADING =
        new RendererSettings("color_grading", "minecraft:color_grading_settings", "color grading");

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
     * A colour grading file puts its four groups under a `color_grading` block, so the block itself
     * is no setting and its contents are. A block whose own values are settings rather than groups,
     * such as the caustics of a water file, is the setting.
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
