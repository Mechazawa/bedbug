package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * The components a pack's own script registered that a block or an item names, with what each is
 * given.
 *
 * Bedrock spells this two ways. A definition written before 1.21.90 lists the names under
 * `minecraft:custom_components` and gives them nothing. From then on each is a component of its own,
 * keyed by the pack's name for it and carrying its parameters as the body, which the callback reads
 * as `params`. A name in the `minecraft` namespace is one of Bedrock's own and never a script's.
 *
 * @param params what each component was given, by its name, in the order the definition names them
 */
public record CustomComponents(Map<String, JsonElement> params) {
    public static final CustomComponents NONE = new CustomComponents(Map.of());

    /** The name an older definition lists its custom components under. */
    public static final String LISTED = "minecraft:custom_components";

    public static CustomComponents of(JsonObject components) {
        if (components == null) return NONE;

        Map<String, JsonElement> found = new LinkedHashMap<>();
        JsonElement listed = components.get(LISTED);
        if (listed != null && listed.isJsonArray()) {
            listed.getAsJsonArray().forEach(name -> {
                if (name.isJsonPrimitive()) found.put(name.getAsString(), new JsonObject());
            });
        }
        components.entrySet().stream()
            .filter(component -> isCustom(component.getKey()))
            .forEach(component -> found.put(component.getKey(), component.getValue()));
        return found.isEmpty() ? NONE : new CustomComponents(Collections.unmodifiableMap(found));
    }

    /** Whether a component name is a pack's own rather than one of Bedrock's. */
    public static boolean isCustom(String name) {
        int colon = name.indexOf(':');
        return colon > 0 && !name.substring(0, colon).equals("minecraft");
    }

    public boolean isEmpty() {
        return params.isEmpty();
    }

    /**
     * Runs every component with the moment's fields and its own parameters beside them, as the JSON
     * a callback is run with, and answers whether any of them said yes.
     *
     * Every component runs whatever the others answered, because each is the pack's own and Bedrock
     * calls all of them.
     */
    public boolean run(JsonObject fields, BiPredicate<String, String> callback) {
        boolean any = false;
        for (Map.Entry<String, JsonElement> component : params.entrySet()) {
            JsonObject arguments = fields.deepCopy();
            arguments.add("params", component.getValue());
            any |= callback.test(component.getKey(), arguments.toString());
        }
        return any;
    }
}
