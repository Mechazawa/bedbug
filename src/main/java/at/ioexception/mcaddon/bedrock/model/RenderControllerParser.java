package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads one render controller out of a pack's `render_controllers` JSON. */
public final class RenderControllerParser {

    /**
     * How Bedrock's own renderer schedules and lights a pass.
     *
     * `rebuild_animation_matrices` asks it to pose the skeleton again before this pass,
     * `filter_lighting` puts the pass through its lighting filter and `light_color_multiplier`
     * tints the light reaching it. Java lights an entity per vertex from the light at its block and
     * poses it once, so none of the three has a counterpart. `ignore_lighting` does: full
     * brightness, which the render type carries.
     */
    private static final List<String> UNREAD = List.of(
        "rebuild_animation_matrices", "filter_lighting", "light_color_multiplier");

    private RenderControllerParser() {
    }

    public static RenderController read(String name, JsonObject json) {
        AddonEntities.unsupportedFields("render controller", json, UNREAD);

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
            uvAnim(json, "offset", 0),
            uvAnim(json, "offset", 1),
            uvAnim(json, "scale", 0),
            uvAnim(json, "scale", 1),
            json.has("ignore_lighting") && json.get("ignore_lighting").getAsBoolean(),
            readArrays(json),
            ControllerColour.tryFrom(json, "color"),
            ControllerColour.tryFrom(json, "overlay_color"),
            ControllerColour.tryFrom(json, "is_hurt_color"),
            ControllerColour.tryFrom(json, "on_fire_color"));
    }

    /**
     * One axis of a `uv_anim` field, which may be a number or a Molang expression.
     *
     * Bedrock writes an offset in texture widths, so a value of one slides the texture past itself
     * exactly once. A scale of one leaves the texture at the size the geometry declared.
     */
    private static MolangExpression uvAnim(JsonObject json, String field, int axis) {
        if (!json.has("uv_anim")) return null;

        JsonObject animation = json.getAsJsonObject("uv_anim");
        if (!animation.has(field)) return null;

        JsonElement value = animation.getAsJsonArray(field).get(axis);
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
