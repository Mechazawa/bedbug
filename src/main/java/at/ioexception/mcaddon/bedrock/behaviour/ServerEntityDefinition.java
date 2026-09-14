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
    float startingHealth,
    float movementSpeed,
    float scale,
    Set<String> families,
    Map<String, Property> properties,
    Map<String, JsonObject> components,
    Map<String, JsonObject> componentGroups,
    Map<String, EntityEvent> events,
    Set<String> behaviours,
    /** The clips and controllers this entity runs server side, which drive its own events. */
    AnimationScript script
) {
    /** A data-driven entity property, which is how a behaviour pack tells a resource pack what to draw. */
    public record Property(String name, String type, double min, double max, List<String> values,
        Object initial, boolean clientSync) {
    }

    public boolean has(String component) {
        return components.containsKey(component);
    }

    /** Null when the pack never declared it, at the top level or in one of its component groups. */
    public JsonObject component(String component) {
        return components.get(component);
    }

    public double number(String component, String field, double fallback) {
        return number(components.get(component), field, fallback);
    }

    public boolean flag(String component, String field, boolean fallback) {
        return flag(components.get(component), field, fallback);
    }

    public String text(String component, String field, String fallback) {
        JsonObject body = components.get(component);
        return body == null ? fallback : Json.text(body, field, fallback);
    }

    /**
     * A number a component's field carries, whatever shape the pack wrote it in.
     *
     * A pack writes `true` where a number is expected and a Molang expression where a constant is,
     * and Gson answers either with a thrown exception rather than a value, which would cost the
     * entity the whole read. A range is answered by its lower bound, because a caller wanting the
     * spread asks for both ends by name.
     */
    public static double number(JsonObject body, String field, double fallback) {
        JsonElement value = body == null ? null : body.get(field);
        if (value == null) return fallback;
        if (value.isJsonArray()) {
            return value.getAsJsonArray().isEmpty()
                ? fallback : number(value.getAsJsonArray().get(0), fallback);
        }
        // A field that varies per entity is written as an object naming both ends of its range, and
        // a caller asking for one number wants the middle of it.
        if (value.isJsonObject()) {
            JsonObject range = value.getAsJsonObject();
            if (!range.has("range_min") || !range.has("range_max")) return fallback;
            return (number(range.get("range_min"), fallback)
                + number(range.get("range_max"), fallback)) / 2;
        }
        return number(value, fallback);
    }

    private static double number(JsonElement value, double fallback) {
        if (!value.isJsonPrimitive()) return fallback;
        if (value.getAsJsonPrimitive().isNumber()) return value.getAsDouble();
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean() ? 1 : 0;
        try {
            return Double.parseDouble(value.getAsString().trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * What a successful breeding pays out, which Bedrock states as Molang beside the death reward.
     *
     * The expression the field carries asks about the kill rather than the birth, so the queries
     * answer as they would for one, and a bare number is the case the parser also covers.
     */
    public int experienceWhenBred() {
        JsonObject reward = components.get("minecraft:experience_reward");
        return amount(reward == null ? null : reward.get("on_bred"), 0);
    }

    private static int amount(JsonElement written, int fallback) {
        if (written == null || !written.isJsonPrimitive()) return fallback;
        if (written.getAsJsonPrimitive().isNumber()) return written.getAsInt();

        at.ioexception.mcaddon.bedrock.molang.MolangEnvironment environment =
            new at.ioexception.mcaddon.bedrock.molang.MolangEnvironment();
        environment.setQueries((name, arguments) ->
            at.ioexception.mcaddon.bedrock.molang.MolangValue.of(
                name.equals("last_hit_by_player") || name.equals("is_alive")));
        return (int) at.ioexception.mcaddon.bedrock.molang.MolangParser
            .parse(written.getAsString()).asFloat(environment);
    }

    /**
     * The event a trigger field names.
     *
     * A pack writes a trigger either as the event's bare name or as an object carrying it under
     * `event` beside the target and the filters, and the two forms sit side by side in Mojang's own
     * entities, so a reader taking only the string form finds nothing in half of them.
     */
    public static String event(JsonObject body, String field) {
        JsonElement value = body == null ? null : body.get(field);
        if (value == null) return null;
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.isJsonObject() ? Json.text(value.getAsJsonObject(), "event", null) : null;
    }

    public static boolean flag(JsonObject body, String field, boolean fallback) {
        JsonElement value = body == null ? null : body.get(field);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        return number(value, fallback ? 1 : 0) != 0;
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

        // A collision box a pack leaves out is one block each way, which is Bedrock's own default
        // rather than the player-shaped box Java falls back to.
        JsonObject collision = components.get("minecraft:collision_box");
        JsonObject health = components.get("minecraft:health");
        JsonObject movement = components.get("minecraft:movement");
        JsonObject scale = components.get("minecraft:scale");

        // Bedrock states a starting health and a ceiling; Java's attribute is the ceiling and the
        // entity is spawned at it, so an entity declaring only one of the two gets it for both.
        double value = number(health, "value", 20);
        double ceiling = number(health, "max", value);

        return new ServerEntityDefinition(
            identifier,
            bool(description, "is_spawnable", true),
            bool(description, "is_summonable", true),
            (float) number(collision, "width", 1),
            (float) number(collision, "height", 1),
            (float) Math.max(1, ceiling),
            (float) Math.min(value, Math.max(1, ceiling)),
            (float) number(movement, "value", 0.23),
            (float) number(scale, "value", 1),
            families(components.get("minecraft:type_family")),
            properties(description.getAsJsonObject("properties")),
            components,
            componentGroups,
            EntityEvent.readAll(body.getAsJsonObject("events")),
            behaviours,
            AnimationScript.read(description));
    }

    /**
     * The component names that stand for something the entity decides to do.
     *
     * Bedrock spells a goal `minecraft:behavior.*` and a goal driven by a memory
     * `minecraft:memory_behavior.*`, and both carry a priority and become a goal here. The body each
     * one carries is read back by name where the goal is built, because a group declares a goal as
     * readily as the top level does.
     */
    private static void collectBehaviours(JsonObject components, Set<String> into) {
        if (components == null) return;
        for (String key : components.keySet()) {
            if (key.startsWith("minecraft:behavior.") || key.startsWith("minecraft:memory_behavior.")) {
                into.add(key);
            }
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
}
