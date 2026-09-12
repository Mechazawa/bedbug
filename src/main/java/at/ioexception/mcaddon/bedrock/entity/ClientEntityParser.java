package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
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
            strings(json, "animations"),
            gated(scripts.getAsJsonArray("animate")),
            gated(json.getAsJsonArray("render_controllers")),
            statements(scripts, "initialize"),
            statements(scripts, "pre_animation"),
            scripts.has("scale") ? MolangParser.parse(primitive(scripts.get("scale"))) : null);
    }

    private static Map<String, String> strings(JsonObject json, String field) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!json.has(field)) return Map.copyOf(values);

        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(field).entrySet()) {
            if (entry.getValue().isJsonPrimitive()) values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Map.copyOf(values);
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
