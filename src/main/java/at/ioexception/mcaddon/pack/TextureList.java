package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.util.stream.StreamSupport;

/** A resource pack's `textures/texture_list.json`, whose every path has to name an image the pack ships. */
public final class TextureList {
    private static final String PATH = "textures/texture_list.json";

    private TextureList() {
    }

    public static void report(BedrockPack pack) {
        if (pack.role() != PackRole.RESOURCE || !pack.has(PATH)) return;

        try {
            StreamSupport.stream(Json.parseArray(pack.file(PATH)).spliterator(), false)
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .filter(texture -> !pack.has(texture + ".png") && !pack.has(texture + ".tga"))
                .forEach(texture -> AddonEntities.unsupported(
                    "resource pack " + PATH + ", " + texture + " is listed and the pack ships no image for it"));
        } catch (IOException | IllegalStateException failure) {
            AddonEntities.unsupported("resource pack " + PATH + ", the file is not a JSON array of paths");
        }
    }
}
