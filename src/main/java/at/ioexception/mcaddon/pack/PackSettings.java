package at.ioexception.mcaddon.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The sliders, toggles and dropdowns the active packs declare, and the values each side holds for
 * them.
 *
 * Bedrock keeps two kinds apart. A behaviour pack's settings belong to the world and only its owner
 * changes them; a resource pack's belong to each player. The server holds the world's values in
 * {@link #WORLD}. A client holds what the server told it about the world in {@link #TOLD} and what
 * its own player chose for the resource packs in {@link #CHOSEN}. A value nobody set is the one the
 * pack declared.
 *
 * A setting's name carries the pack's own namespace, which is what the schema requires of it and
 * what lets one index hold every pack's settings at once.
 */
public final class PackSettings {
    /** A setting no pack declares, which answers with nothing set, as Bedrock does for one. */
    private static final PackManifest.Setting NONE =
        new PackManifest.Setting("", "", "", null, 0, 0, 0, List.of());

    public static final PackSettings WORLD = new PackSettings();
    public static final PackSettings TOLD = new PackSettings();
    public static final PackSettings CHOSEN = new PackSettings();

    private static volatile Map<String, PackManifest.Setting> declared = Map.of();
    private static volatile Set<String> behaviour = Set.of();

    /** Read by the render thread while the server thread or the network thread replaces it. */
    private volatile Map<String, JsonElement> values = Map.of();

    private PackSettings() {
    }

    /** Declares the settings of a set of packs, the first declaration of a name winning. */
    public static void read(PackSet packs) {
        Map<String, PackManifest.Setting> found = new LinkedHashMap<>();
        packs.packs().stream()
            .flatMap(pack -> pack.manifest().settings().stream())
            .filter(setting -> setting.name() != null)
            .forEach(setting -> found.putIfAbsent(setting.name(), setting));
        declared = Map.copyOf(found);
        behaviour = packs.withRole(PackRole.BEHAVIOUR).stream()
            .flatMap(pack -> pack.manifest().settings().stream())
            .map(PackManifest.Setting::name)
            .filter(name -> name != null)
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether a behaviour pack declares the setting, which makes it the world's and not a player's. */
    public static boolean belongsToWorld(String name) {
        return behaviour.contains(name);
    }

    /** A setting as a client answers it: the world's from what the server said, a player's from their choice. */
    public static PackManifest.Setting onClient(String name) {
        return (belongsToWorld(name) ? TOLD : CHOSEN).of(name);
    }

    public PackManifest.Setting of(String name) {
        PackManifest.Setting setting = declared.get(name);
        return setting == null ? NONE : setting.with(values.get(name));
    }

    /**
     * Sets one value, brought inside its control, and answers the setting it left, or null for a
     * name no active pack declares.
     */
    public synchronized PackManifest.Setting set(String name, JsonElement value) {
        PackManifest.Setting setting = declared.get(name);
        if (setting == null) return null;

        PackManifest.Setting moved = setting.with(value);
        Map<String, JsonElement> next = new LinkedHashMap<>(values);
        next.put(name, moved.value());
        values = Map.copyOf(next);
        return moved;
    }

    /** Replaces every value with the ones written in `saved`, as {@link #toJson} wrote them. */
    public void replace(JsonElement saved) {
        Map<String, JsonElement> found = new LinkedHashMap<>();
        if (saved != null && saved.isJsonObject()) {
            saved.getAsJsonObject().entrySet().stream()
                .filter(entry -> entry.getValue().isJsonPrimitive())
                .forEach(entry -> found.put(entry.getKey(), entry.getValue()));
        }
        values = Map.copyOf(found);
    }

    /**
     * Every value set on this side, including those of packs that are not active now, so a set
     * that comes back finds them again.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        values.forEach((name, value) -> json.add(name, value.deepCopy()));
        return json;
    }

    /** Every declared setting at its value, in the shape `World.getPackSettings` answers with. */
    public Map<String, Object> values() {
        Map<String, Object> found = new LinkedHashMap<>();
        declared.keySet().forEach(name -> {
            Object plain = of(name).plain();
            if (plain != null) found.put(name, plain);
        });
        return found;
    }
}
