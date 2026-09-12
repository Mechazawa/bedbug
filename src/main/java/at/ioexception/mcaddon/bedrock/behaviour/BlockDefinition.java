package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A block a behaviour pack declares.
 *
 * `minecraft:material_instances` names a face or `*` for all of them, and the texture it names is a
 * key in the resource pack's `terrain_texture.json` rather than a path, the same indirection items
 * use.
 */
public record BlockDefinition(String identifier, Map<String, String> faceTextures, String geometry,
    float hardness, float resistance, int lightEmission, boolean transparent,
    Map<String, JsonObject> components) {

    public static List<BlockDefinition> readAll(BedrockPack pack) {
        List<BlockDefinition> blocks = new ArrayList<>();
        for (Path file : pack.list("blocks", ".json")) {
            try {
                BlockDefinition block = read(Json.parseObject(file));
                if (block != null) blocks.add(block);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return blocks;
    }

    private static BlockDefinition read(JsonObject root) {
        JsonObject body = root.has("minecraft:block") && root.get("minecraft:block").isJsonObject()
            ? root.getAsJsonObject("minecraft:block") : null;
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        Map<String, JsonObject> components = new LinkedHashMap<>();
        JsonObject declared = body.getAsJsonObject("components");
        if (declared != null) {
            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                components.put(entry.getKey(), entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject() : new JsonObject());
            }
        }

        Map<String, String> faces = new LinkedHashMap<>();
        boolean transparent = false;
        JsonObject instances = components.get("minecraft:material_instances");
        if (instances != null) {
            for (Map.Entry<String, JsonElement> entry : instances.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject instance = entry.getValue().getAsJsonObject();
                String texture = Json.text(instance, "texture", null);
                if (texture != null) faces.put(entry.getKey(), texture);
                transparent |= !"opaque".equals(Json.text(instance, "render_method", "opaque"));
            }
        }

        return new BlockDefinition(identifier, faces,
            text(declared, "minecraft:geometry"),
            number(components.get("minecraft:destructible_by_mining"), "seconds_to_destroy", 1f),
            number(components.get("minecraft:destructible_by_explosion"), "explosion_resistance", 1f),
            (int) number(declared, "minecraft:light_emission", 0),
            transparent,
            components);
    }

    /** A component is written either as the bare value or as an object wrapping it. */
    private static String text(JsonObject components, String name) {
        JsonElement value = components == null ? null : components.get(name);
        if (value == null) return null;
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.isJsonObject() ? Json.text(value.getAsJsonObject(), "identifier", null) : null;
    }

    private static float number(JsonObject json, String key, float fallback) {
        JsonElement value = json == null ? null : json.get(key);
        if (value == null) return fallback;
        if (value.isJsonPrimitive()) return value.getAsFloat();

        JsonElement inner = value.isJsonObject() ? value.getAsJsonObject().get(key) : null;
        return inner != null && inner.isJsonPrimitive() ? inner.getAsFloat() : fallback;
    }
}
