package at.ioexception.mcaddon.client.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * The entity properties the server has told this client about.
 *
 * A behaviour pack sets these and a resource pack draws from them, so on a server the two halves of a
 * pack are on different machines and this is what joins them. Keyed by network id because that is
 * what a render state has to hand.
 */
public final class EntityProperties {
    private static final Map<Integer, Map<String, Object>> BY_ENTITY = new HashMap<>();

    private EntityProperties() {
    }

    public static void accept(int entityId, String json) {
        Map<String, Object> values = new HashMap<>();
        JsonElement parsed = JsonParser.parseString(json);
        if (parsed.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                Object value = value(entry.getValue());
                if (value != null) values.put(entry.getKey(), value);
            }
        }
        BY_ENTITY.put(entityId, values);
    }

    public static Map<String, Object> of(int entityId) {
        return BY_ENTITY.getOrDefault(entityId, Map.of());
    }

    /** Called when an entity stops being tracked, so the map does not grow for the whole session. */
    public static void forget(int entityId) {
        BY_ENTITY.remove(entityId);
    }

    public static void clear() {
        BY_ENTITY.clear();
    }

    /** Writes the shape the payload carries, which the server side uses to fill it. */
    public static String encode(Map<String, Object> values) {
        JsonObject json = new JsonObject();
        values.forEach((name, value) -> {
            if (value instanceof Boolean flag) {
                json.addProperty(name, flag);
            } else if (value instanceof Number number) {
                json.addProperty(name, number);
            } else {
                json.addProperty(name, String.valueOf(value));
            }
        });
        return json.toString();
    }

    private static Object value(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        if (element.getAsJsonPrimitive().isBoolean()) return element.getAsBoolean();
        if (element.getAsJsonPrimitive().isNumber()) return element.getAsDouble();
        return element.getAsString();
    }
}
