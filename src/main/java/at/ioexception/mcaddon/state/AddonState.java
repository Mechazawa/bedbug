package at.ioexception.mcaddon.state;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a pack has stored: the dynamic properties of the world, of an entity and of a single block,
 * and the entity properties a behaviour pack declares.
 *
 * This lives in a file beside the world rather than in entity NBT, which keeps it out of the way of
 * the save format and lets the same code run on all three Minecraft versions. Entity properties are
 * here too because they are the channel a behaviour pack uses to tell the resource pack what to
 * draw, so the server owns them and the client is told.
 */
public final class AddonState {
    private static final Gson GSON = new Gson();

    private final Path file;
    private final Map<String, Object> world = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Object>> entityDynamic = new HashMap<>();
    private final Map<UUID, Map<String, Object>> entityProperties = new HashMap<>();
    private final Map<String, Map<String, Object>> blockDynamic = new LinkedHashMap<>();
    private boolean dirty;

    private AddonState(Path file) {
        this.file = file;
    }

    public static AddonState load(Path file) {
        AddonState state = new AddonState(file);
        if (!Files.isRegularFile(file)) return state;

        try {
            JsonObject root = Json.parseObject(file);
            readInto(root.getAsJsonObject("world"), state.world);
            readEntities(root.getAsJsonObject("entityDynamic"), state.entityDynamic);
            readEntities(root.getAsJsonObject("entityProperties"), state.entityProperties);
            readBlocks(root.getAsJsonObject("blockDynamic"), state.blockDynamic);
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.error("Could not read {}; starting from empty add-on state.", file, error);
        }
        return state;
    }

    public void save() {
        if (!dirty) return;
        JsonObject root = new JsonObject();
        root.add("world", GSON.toJsonTree(world));
        root.add("entityDynamic", writeGroups(entityDynamic));
        root.add("entityProperties", writeGroups(entityProperties));
        root.add("blockDynamic", writeGroups(blockDynamic));

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException error) {
            McAddon.LOGGER.error("Could not write {}.", file, error);
        }
    }

    public Object worldProperty(String name) {
        return world.get(name);
    }

    public void setWorldProperty(String name, Object value) {
        put(world, name, value);
    }

    public Set<String> worldPropertyNames() {
        return world.keySet();
    }

    public Object dynamicProperty(UUID entity, String name) {
        return entityDynamic.getOrDefault(entity, Map.of()).get(name);
    }

    public void setDynamicProperty(UUID entity, String name, Object value) {
        put(entityDynamic.computeIfAbsent(entity, key -> new LinkedHashMap<>()), name, value);
    }

    public Set<String> dynamicPropertyNames(UUID entity) {
        return entityDynamic.getOrDefault(entity, Map.of()).keySet();
    }

    public Object property(UUID entity, String name) {
        return entityProperties.getOrDefault(entity, Map.of()).get(name);
    }

    public void setProperty(UUID entity, String name, Object value) {
        put(entityProperties.computeIfAbsent(entity, key -> new LinkedHashMap<>()), name, value);
    }

    public Map<String, Object> properties(UUID entity) {
        return entityProperties.getOrDefault(entity, Map.of());
    }

    /** Called when an entity is gone for good, so neither map grows for the life of the world. */
    public void forget(UUID entity) {
        dirty |= entityDynamic.remove(entity) != null;
        dirty |= entityProperties.remove(entity) != null;
    }

    /** Where one block's own store is filed: the dimension it stands in and the position it holds. */
    public static String blockKey(String dimension, int x, int y, int z) {
        return dimension + " " + x + " " + y + " " + z;
    }

    public Object blockProperty(String block, String name) {
        return blockDynamic.getOrDefault(block, Map.of()).get(name);
    }

    public void setBlockProperty(String block, String name, Object value) {
        put(blockDynamic.computeIfAbsent(block, key -> new LinkedHashMap<>()), name, value);
    }

    public Set<String> blockPropertyNames(String block) {
        return blockDynamic.getOrDefault(block, Map.of()).keySet();
    }

    /** Called when the block standing there is gone, which is where Bedrock ends its store too. */
    public void forgetBlock(String block) {
        dirty |= blockDynamic.remove(block) != null;
    }

    public int worldPropertyBytes() {
        return world.entrySet().stream()
            .mapToInt(entry -> entry.getKey().length() + String.valueOf(entry.getValue()).length())
            .sum();
    }

    private void put(Map<String, Object> target, String name, Object value) {
        if (value == null) {
            dirty |= target.remove(name) != null;
            return;
        }
        dirty |= !value.equals(target.put(name, value));
    }

    private static void readInto(JsonObject source, Map<String, Object> target) {
        if (source == null) return;
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            Object value = value(entry.getValue());
            if (value != null) target.put(entry.getKey(), value);
        }
    }

    private static void readEntities(JsonObject source, Map<UUID, Map<String, Object>> target) {
        if (source == null) return;
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            readInto(entry.getValue().getAsJsonObject(), values);
            try {
                target.put(UUID.fromString(entry.getKey()), values);
            } catch (IllegalArgumentException ignored) {
                McAddon.LOGGER.warn("Dropping add-on state under a key that is not a uuid: {}", entry.getKey());
            }
        }
    }

    private static void readBlocks(JsonObject source, Map<String, Map<String, Object>> target) {
        if (source == null) return;
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            readInto(entry.getValue().getAsJsonObject(), values);
            target.put(entry.getKey(), values);
        }
    }

    private static <K> JsonObject writeGroups(Map<K, Map<String, Object>> source) {
        JsonObject result = new JsonObject();
        source.forEach((owner, values) -> {
            if (!values.isEmpty()) result.add(String.valueOf(owner), GSON.toJsonTree(values));
        });
        return result;
    }

    private static Object value(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        if (element.getAsJsonPrimitive().isBoolean()) return element.getAsBoolean();
        if (element.getAsJsonPrimitive().isNumber()) return element.getAsDouble();
        return element.getAsString();
    }
}
