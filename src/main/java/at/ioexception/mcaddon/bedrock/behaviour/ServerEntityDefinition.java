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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a behaviour pack says one entity is.
 *
 * The components are kept as parsed JSON rather than as fields, because a pack uses far more of them
 * than this mod implements and the ones it does not are still worth reporting. What is lifted out
 * here is only what deciding the entity's shape needs before the registries freeze.
 */
public record ServerEntityDefinition(
    String identifier,
    boolean spawnable,
    boolean summonable,
    float width,
    float height,
    float maxHealth,
    float movementSpeed,
    float scale,
    Set<String> families,
    Map<String, Property> properties,
    Map<String, JsonObject> components,
    Map<String, JsonObject> componentGroups,
    Map<String, EntityEvent> events,
    Set<String> behaviours
) {
    /** A data-driven entity property, which is how a behaviour pack tells a resource pack what to draw. */
    public record Property(String name, String type, double min, double max, List<String> values,
        Object initial, boolean clientSync) {
    }

    public static List<ServerEntityDefinition> readAll(BedrockPack pack) {
        List<ServerEntityDefinition> definitions = new ArrayList<>();
        for (Path file : pack.list("entities", ".json")) {
            try {
                ServerEntityDefinition definition = read(Json.parseObject(file));
                if (definition != null) definitions.add(definition);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return definitions;
    }

    private static ServerEntityDefinition read(JsonObject root) {
        JsonObject body = root.has("minecraft:entity") && root.get("minecraft:entity").isJsonObject()
            ? root.getAsJsonObject("minecraft:entity") : null;
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        if (description == null) return null;
        String identifier = Json.text(description, "identifier", null);
        if (identifier == null) return null;

        Map<String, JsonObject> components = new LinkedHashMap<>();
        JsonObject declared = body.getAsJsonObject("components");
        if (declared != null) {
            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                components.put(entry.getKey(), entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject() : new JsonObject());
            }
        }

        Set<String> behaviours = new LinkedHashSet<>();
        collectBehaviours(declared, behaviours);

        Map<String, JsonObject> componentGroups = new LinkedHashMap<>();
        JsonObject groups = body.getAsJsonObject("component_groups");
        if (groups != null) {
            for (Map.Entry<String, JsonElement> group : groups.entrySet()) {
                if (!group.getValue().isJsonObject()) continue;
                componentGroups.put(group.getKey(), group.getValue().getAsJsonObject());
                collectBehaviours(group.getValue().getAsJsonObject(), behaviours);
            }
        }

        JsonObject collision = components.get("minecraft:collision_box");
        JsonObject health = components.get("minecraft:health");
        JsonObject movement = components.get("minecraft:movement");
        JsonObject scale = components.get("minecraft:scale");

        return new ServerEntityDefinition(
            identifier,
            bool(description, "is_spawnable", true),
            bool(description, "is_summonable", true),
            number(collision, "width", 0.6f),
            number(collision, "height", 1.8f),
            number(health, "value", 20f),
            number(movement, "value", 0.23f),
            number(scale, "value", 1f),
            families(components.get("minecraft:type_family")),
            properties(description.getAsJsonObject("properties")),
            components,
            componentGroups,
            EntityEvent.readAll(body.getAsJsonObject("events")),
            behaviours);
    }

    private static void collectBehaviours(JsonObject components, Set<String> into) {
        if (components == null) return;
        for (String key : components.keySet()) {
            if (key.startsWith("minecraft:behavior.")) into.add(key);
        }
    }

    private static Set<String> families(JsonObject component) {
        Set<String> families = new LinkedHashSet<>();
        if (component == null) return families;
        for (JsonElement family : Json.list(component, "family")) {
            if (family.isJsonPrimitive()) families.add(family.getAsString());
        }
        return families;
    }

    /**
     * Property declarations, which carry the shape of the value rather than the value itself.
     *
     * `client_sync` is the part that matters for drawing: a property the client is told about is one
     * a render controller can branch on.
     */
    private static Map<String, Property> properties(JsonObject declared) {
        Map<String, Property> properties = new LinkedHashMap<>();
        if (declared == null) return properties;

        for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject property = entry.getValue().getAsJsonObject();
            String type = Json.text(property, "type", "bool");

            List<String> values = new ArrayList<>();
            for (JsonElement value : Json.list(property, "values")) {
                if (value.isJsonPrimitive()) values.add(value.getAsString());
            }

            double min = 0;
            double max = type.equals("bool") ? 1 : 0;
            if (property.has("range") && property.get("range").isJsonArray()) {
                var range = property.getAsJsonArray("range");
                if (range.size() >= 2) {
                    min = range.get(0).getAsDouble();
                    max = range.get(1).getAsDouble();
                }
            }

            properties.put(entry.getKey(), new Property(entry.getKey(), type, min, max, values,
                initial(property.get("default"), type), bool(property, "client_sync", false)));
        }
        return properties;
    }

    private static Object initial(JsonElement value, String type) {
        if (value == null || !value.isJsonPrimitive()) {
            return switch (type) {
                case "bool" -> Boolean.FALSE;
                case "enum" -> "";
                default -> 0.0d;
            };
        }
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        if (value.getAsJsonPrimitive().isNumber()) return value.getAsDouble();
        return value.getAsString();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json != null && json.has(key) && json.get(key).isJsonPrimitive()
            ? json.get(key).getAsBoolean() : fallback;
    }

    private static float number(JsonObject json, String key, float fallback) {
        if (json == null) return fallback;
        JsonElement value = json.get(key);
        if (value != null && value.isJsonPrimitive()) return value.getAsFloat();
        // A component is often written as the bare value it would otherwise wrap.
        return fallback;
    }
}
