package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.convert.SpawnEggAppearance;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads one client entity definition, which is the `description` of a `minecraft:client_entity`. */
public final class ClientEntityParser {

    private ClientEntityParser() {
    }

    public static ClientEntityDefinition read(String name, JsonObject json) {
        JsonObject scripts = json.has("scripts") ? json.getAsJsonObject("scripts") : new JsonObject();

        return new ClientEntityDefinition(
            name,
            strings(json, "geometry"),
            strings(json, "textures"),
            strings(json, "materials"),
            strings(json, "animations"),
            named(json.getAsJsonArray("animation_controllers")),
            strings(json, "sound_effects"),
            strings(json, "particle_effects"),
            gated(scripts.getAsJsonArray("animate")),
            gated(json.getAsJsonArray("render_controllers")),
            statements(scripts, "initialize"),
            statements(scripts, "pre_animation"),
            expression(scripts, "scale"),
            expression(scripts, "scaleX", "scalex"),
            expression(scripts, "scaleY", "scaley"),
            expression(scripts, "scaleZ", "scalez"),
            SpawnEggAppearance.tryFrom(name, json));
    }

    /**
     * The first of these Molang fields of the scripts block, null where the pack wrote none.
     *
     * Packs write the per axis scales in either case, which is why each is looked for twice.
     */
    private static MolangExpression expression(JsonObject scripts, String... fields) {
        for (String field : fields) {
            JsonElement value = scripts.get(field);
            if (value != null && !value.isJsonNull()) return MolangParser.parse(primitive(value));
        }
        return null;
    }

    /**
     * One of the definition's name maps, kept in the order the pack wrote it.
     *
     * The order decides which geometry and texture an entity falls back on when its controllers pick
     * a name it does not carry, so the first entry has to be the pack's first entry.
     */
    private static Map<String, String> strings(JsonObject json, String field) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!json.has(field)) return Map.of();

        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(field).entrySet()) {
            if (entry.getValue().isJsonPrimitive()) values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Collections.unmodifiableMap(values);
    }

    /** A list of one-entry objects, which is how a pack writes a map it wants kept in order. */
    private static Map<String, String> named(JsonArray array) {
        Map<String, String> values = new LinkedHashMap<>();
        if (array == null) return Map.of();

        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) values.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static List<ClientEntityDefinition.Gated> gated(JsonArray array) {
        List<ClientEntityDefinition.Gated> entries = new ArrayList<>();
        if (array == null) return List.copyOf(entries);

        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                Map.Entry<String, JsonElement> single = element.getAsJsonObject().entrySet().iterator().next();
                entries.add(new ClientEntityDefinition.Gated(
                    single.getKey(), MolangParser.parse(primitive(single.getValue()))));
            } else {
                entries.add(new ClientEntityDefinition.Gated(element.getAsString(), null));
            }
        }
        return List.copyOf(entries);
    }

    private static List<MolangExpression> statements(JsonObject json, String field) {
        if (!json.has(field)) return List.of();

        List<MolangExpression> parsed = new ArrayList<>();
        for (JsonElement entry : json.getAsJsonArray(field)) parsed.add(MolangParser.parse(entry.getAsString()));
        return List.copyOf(parsed);
    }

    private static Object primitive(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) return json.getAsDouble();
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) return json.getAsBoolean() ? 1d : 0d;
        return json.getAsString();
    }

}
