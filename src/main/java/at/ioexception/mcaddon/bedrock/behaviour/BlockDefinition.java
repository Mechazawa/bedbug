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
 *
 * The components are kept as the pack wrote them, because Bedrock allows most of them in two
 * shapes: the bare value, and an object wrapping it. {@link at.ioexception.mcaddon.block.BlockComponents}
 * is what reads them.
 */
public record BlockDefinition(String identifier, Map<String, String> faceTextures, String geometry,
    boolean transparent, JsonObject components, Map<String, List<JsonElement>> states,
    List<Permutation> permutations, JsonObject events) {

    /** One `permutations` entry: the components that apply while its Molang condition holds. */
    public record Permutation(String condition, JsonObject components) {
    }

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

        JsonObject components = object(body, "components");

        Map<String, String> faces = new LinkedHashMap<>();
        boolean transparent = false;
        for (Map.Entry<String, JsonElement> entry
            : object(components, "minecraft:material_instances").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject instance = entry.getValue().getAsJsonObject();
            String texture = Json.text(instance, "texture", null);
            if (texture != null) faces.put(entry.getKey(), texture);
            transparent |= !"opaque".equals(Json.text(instance, "render_method", "opaque"));
        }

        Map<String, List<JsonElement>> states = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object(description, "states").entrySet()) {
            List<JsonElement> values = new ArrayList<>();
            if (entry.getValue().isJsonArray()) {
                entry.getValue().getAsJsonArray().forEach(values::add);
            } else if (entry.getValue().isJsonObject()) {
                // A range is written as `{"values": {"min": 0, "max": 3}}`.
                JsonObject range = object(entry.getValue().getAsJsonObject(), "values");
                if (range.has("min") && range.has("max")) {
                    for (int value = range.get("min").getAsInt(); value <= range.get("max").getAsInt();
                        value++) {
                        values.add(new com.google.gson.JsonPrimitive(value));
                    }
                }
            }
            if (!values.isEmpty()) states.put(entry.getKey(), values);
        }

        List<Permutation> permutations = new ArrayList<>();
        for (JsonElement element : Json.list(body, "permutations")) {
            if (!element.isJsonObject()) continue;
            JsonObject permutation = element.getAsJsonObject();
            permutations.add(new Permutation(Json.text(permutation, "condition", "1"),
                object(permutation, "components")));
        }

        return new BlockDefinition(identifier, faces, geometry(components), transparent,
            components, states, permutations, object(body, "events"));
    }

    /** The named member, or an empty object when the pack left it out or wrote something else. */
    private static JsonObject object(JsonObject json, String name) {
        JsonElement value = json == null ? null : json.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    /** The geometry is written either as the bare identifier or as an object carrying it. */
    private static String geometry(JsonObject components) {
        JsonElement value = components.get("minecraft:geometry");
        if (value == null) return null;
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.isJsonObject() ? Json.text(value.getAsJsonObject(), "identifier", null) : null;
    }
}
