package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads one render controller out of a pack's `render_controllers` JSON. */
public final class RenderControllerParser {

    private RenderControllerParser() {
    }

    public static RenderController read(String name, JsonObject json) {
        List<MolangExpression> textures = new ArrayList<>();
        if (json.has("textures")) {
            for (JsonElement texture : json.getAsJsonArray("textures")) {
                textures.add(MolangParser.parse(texture.getAsString()));
            }
        }

        List<Map<String, MolangExpression>> visibility = new ArrayList<>();
        if (json.has("part_visibility")) {
            for (JsonElement rule : json.getAsJsonArray("part_visibility")) {
                Map<String, MolangExpression> parsed = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> bone : rule.getAsJsonObject().entrySet()) {
                    parsed.put(bone.getKey(), MolangParser.parse(bone.getValue().getAsString()));
                }
                visibility.add(Map.copyOf(parsed));
            }
        }

        List<Map<String, MolangExpression>> materials = new ArrayList<>();
        if (json.has("materials")) {
            for (JsonElement rule : json.getAsJsonArray("materials")) {
                Map<String, MolangExpression> parsed = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> bone : rule.getAsJsonObject().entrySet()) {
                    parsed.put(bone.getKey(), MolangParser.parse(bone.getValue().getAsString()));
                }
                materials.add(Map.copyOf(parsed));
            }
        }

        return new RenderController(
            name,
            json.has("geometry") ? MolangParser.parse(json.get("geometry").getAsString()) : null,
            List.copyOf(textures),
            List.copyOf(visibility),
            List.copyOf(materials),
            uvOffset(json, 0),
            uvOffset(json, 1),
            readArrays(json));
    }

    /**
     * One axis of `uv_anim.offset`, which may be a number or a Molang expression.
     *
     * Bedrock writes it in texture widths, so a value of one slides the texture past itself exactly
     * once. Nothing in the add-on animates it; it picks a fixed row out of a tall strip.
     */
    private static MolangExpression uvOffset(JsonObject json, int axis) {
        if (!json.has("uv_anim")) return null;

        JsonObject animation = json.getAsJsonObject("uv_anim");
        if (!animation.has("offset")) return null;

        JsonElement value = animation.getAsJsonArray("offset").get(axis);
        return value.getAsJsonPrimitive().isString()
            ? MolangParser.parse(value.getAsString())
            : MolangExpression.constant(value.getAsFloat());
    }

    /** Named lists a controller indexes into, such as one texture per villager profession. */
    private static Map<String, List<MolangValue>> readArrays(JsonObject json) {
        if (!json.has("arrays")) return Map.of();

        Map<String, List<MolangValue>> arrays = new HashMap<>();
        for (Map.Entry<String, JsonElement> kind : json.getAsJsonObject("arrays").entrySet()) {
            for (Map.Entry<String, JsonElement> array : kind.getValue().getAsJsonObject().entrySet()) {
                List<MolangValue> values = new ArrayList<>();
                for (JsonElement value : array.getValue().getAsJsonArray()) {
                    // Entries read as "Texture.name" or "Geometry.name"; only the name matters.
                    values.add(MolangValue.of(value.getAsString().replaceFirst("^[A-Za-z]+\\.", "")));
                }
                arrays.put(array.getKey().replaceFirst("^Array\\.", ""), List.copyOf(values));
            }
        }
        return Map.copyOf(arrays);
    }

}
