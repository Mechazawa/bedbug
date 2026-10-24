package at.ioexception.mcaddon.state;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
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
 * the entity properties a behaviour pack declares, the dialogue scene each NPC is showing, and the
 * sections a Bedrock command keeps about the world, such as its ticking areas.
 *
 * This lives in a file beside the world rather than in entity NBT, which keeps it out of the way of
 * the save format and lets the same code run on every supported Minecraft version. Entity properties
 * are here too because they are the channel a behaviour pack uses to tell the resource pack what to
 * draw, so the server owns them and the client is told.
 */
public final class AddonState {
    private static final Gson GSON = new Gson();

    /** The one thing filed per NPC: which of its pack's scenes the next interaction opens. */
    private static final String SCENE = "scene";

    private final Path file;
    private final Map<String, Object> world = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Object>> entityDynamic = new HashMap<>();
    private final Map<UUID, Map<String, Object>> entityProperties = new HashMap<>();
    private final Map<String, Map<String, Object>> blockDynamic = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Object>> dialogue = new HashMap<>();
    private final Map<String, JsonElement> sections = new LinkedHashMap<>();
    private final Map<UUID, JsonObject> trading = new HashMap<>();
    private boolean dirty;

    /**
     * The `temp.` values every Molang expression the server runs reads and writes, which nothing
     * saves. A world reopened starts from none, as Bedrock's does.
     */
    private final Map<String, MolangValue> temporaries = new HashMap<>();

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
            readEntities(root.getAsJsonObject("dialogue"), state.dialogue);
            JsonObject trading = root.getAsJsonObject("trading");
            if (trading != null) {
                trading.entrySet().stream().filter(entry -> entry.getValue().isJsonObject()).forEach(entry -> {
                    try {
                        state.trading.put(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonObject());
                    } catch (IllegalArgumentException ignored) {
                        McAddon.LOGGER.warn("Dropping trade state under a key that is not a uuid: {}",
                            entry.getKey());
                    }
                });
            }
            JsonObject sections = root.getAsJsonObject("sections");
            if (sections != null) {
                sections.entrySet().forEach(entry -> state.sections.put(entry.getKey(), entry.getValue()));
            }
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
        root.add("dialogue", writeGroups(dialogue));
        JsonObject traders = new JsonObject();
        trading.forEach((owner, saved) -> traders.add(owner.toString(), saved));
        root.add("trading", traders);
        JsonObject written = new JsonObject();
        sections.forEach(written::add);
        root.add("sections", written);

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

    public Map<String, MolangValue> temporaries() {
        return temporaries;
    }

    public Map<String, Object> properties(UUID entity) {
        return entityProperties.getOrDefault(entity, Map.of());
    }

    /**
     * The scene an NPC shows whoever interacts with it, which `/dialogue change` moves.
     *
     * It is filed here rather than among the entity's properties because it belongs to this mod
     * and not to the pack: a pack reading its own entity's properties back must see what it wrote
     * there and nothing else.
     */
    public String dialogueScene(UUID entity) {
        Object scene = dialogue.getOrDefault(entity, Map.of()).get(SCENE);
        return scene instanceof String named ? named : null;
    }

    public void setDialogueScene(UUID entity, String scene) {
        put(dialogue.computeIfAbsent(entity, key -> new LinkedHashMap<>()), SCENE, scene);
    }

    /**
     * A whole part of the state one owner reads and writes as a unit, such as the ticking areas a
     * pack added. Null when the owner has written nothing.
     */
    public JsonElement section(String name) {
        JsonElement found = sections.get(name);
        return found == null ? null : found.deepCopy();
    }

    public void setSection(String name, JsonElement value) {
        JsonElement previous = value == null ? sections.remove(name) : sections.put(name, value.deepCopy());
        dirty |= value == null ? previous != null : !value.equals(previous);
    }

    /** What a trader keeps between sessions: its offers, uses, experience, spent triggers and stock. */
    public JsonObject trading(UUID entity) {
        JsonObject saved = trading.get(entity);
        return saved == null ? null : saved.deepCopy();
    }

    public void setTrading(UUID entity, JsonObject saved) {
        JsonObject previous = trading.put(entity, saved.deepCopy());
        dirty |= !saved.equals(previous);
    }

    /** Called when an entity is gone for good, so no map grows for the life of the world. */
    public void forget(UUID entity) {
        dirty |= entityDynamic.remove(entity) != null;
        dirty |= entityProperties.remove(entity) != null;
        dirty |= dialogue.remove(entity) != null;
        dirty |= trading.remove(entity) != null;
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
        if (element != null && element.isJsonObject() && element.getAsJsonObject().has("z")) {
            return GSON.fromJson(element, PropertyVector.class);
        }
        if (element == null || !element.isJsonPrimitive()) return null;
        if (element.getAsJsonPrimitive().isBoolean()) return element.getAsBoolean();
        if (element.getAsJsonPrimitive().isNumber()) return element.getAsDouble();
        return element.getAsString();
    }
}
