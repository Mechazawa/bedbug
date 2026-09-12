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
 * An item a behaviour pack declares.
 *
 * `icon` names a key in the resource pack's `item_texture.json` rather than a path, so the two halves
 * of an add-on have to be read together before an item can be drawn.
 */
public record ItemDefinition(String identifier, String iconKey, String displayNameKey,
    int maxStackSize, Map<String, JsonObject> components) {

    public static List<ItemDefinition> readAll(BedrockPack pack) {
        List<ItemDefinition> items = new ArrayList<>();
        for (Path file : pack.list("items", ".json")) {
            try {
                ItemDefinition item = read(Json.parseObject(file));
                if (item != null) items.add(item);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return items;
    }

    private static ItemDefinition read(JsonObject root) {
        JsonObject body = root.has("minecraft:item") && root.get("minecraft:item").isJsonObject()
            ? root.getAsJsonObject("minecraft:item") : null;
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        Map<String, JsonObject> components = new LinkedHashMap<>();
        JsonObject declared = body.getAsJsonObject("components");
        if (declared != null) {
            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                components.put(entry.getKey(), component(entry.getValue()));
            }
        }

        return new ItemDefinition(identifier, icon(components.get("minecraft:icon")),
            displayName(components.get("minecraft:display_name")),
            stackSize(declared), components);
    }

    /** A component written as a bare value keeps it under `value`, where the object form puts it. */
    private static JsonObject component(JsonElement declared) {
        if (declared.isJsonObject()) return declared.getAsJsonObject();

        JsonObject wrapped = new JsonObject();
        if (declared.isJsonPrimitive()) wrapped.add("value", declared);
        return wrapped;
    }

    /**
     * The texture key `minecraft:icon` names, in any of the three shapes Bedrock has used for it.
     *
     * The oldest is a bare string, then came an object with `texture`, and from format 1.21.20 a map
     * of states to textures under `textures`. A pack written against any of them is still shipped.
     */
    private static String icon(JsonObject component) {
        if (component == null) return null;

        String texture = Json.text(component, "texture", null);
        if (texture == null) texture = Json.text(component, "value", null);
        if (texture != null) return texture;

        JsonObject textures = component.getAsJsonObject("textures");
        if (textures == null) return null;

        String byState = Json.text(textures, "default", null);
        if (byState != null) return byState;
        return textures.entrySet().stream()
            .filter(entry -> entry.getValue().isJsonPrimitive())
            .map(entry -> entry.getValue().getAsString())
            .findFirst().orElse(null);
    }

    private static String displayName(JsonObject component) {
        return component == null ? null : Json.text(component, "value", null);
    }

    /** A Bedrock component is written either as the bare value or as an object wrapping it. */
    private static int stackSize(JsonObject components) {
        JsonElement value = components == null ? null : components.get("minecraft:max_stack_size");
        if (value == null) return 64;
        if (value.isJsonPrimitive()) return value.getAsInt();

        JsonElement inner = value.isJsonObject() ? value.getAsJsonObject().get("value") : null;
        return inner != null && inner.isJsonPrimitive() ? inner.getAsInt() : 64;
    }
}
