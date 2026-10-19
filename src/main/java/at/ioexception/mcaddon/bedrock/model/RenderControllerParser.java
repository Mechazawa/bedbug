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
     * Fields Mojang documents nowhere, so what they do to a pass cannot be known.
     *
     * Neither name appears in the published schemas, the doc modules, the entity documentation or
     * the reconstructed RenderDragon shader source; only Mojang's own render controllers declare
     * them. `rebuild_animation_matrices` sits on second passes over another geometry, which every
     * pass here already gets, since `BedrockModelRenderer` poses each pass from its own geometry.
     */
    private static final List<String> UNREAD = List.of(
        "rebuild_animation_matrices", "filter_lighting");

    private RenderControllerParser() {
    }

    /**
     * A list of bone rules, each rule an expression per bone or family of them.
     *
     * The order is what a rule means: `part_visibility` and `materials` both let a later rule answer
     * for a bone an earlier one already matched, and one rule object can hold the wildcard and the
     * bones overriding it together. So the declaration order is kept both between rules and inside
     * one, which a map ordered any other way would lose.
     */
    private static List<Map<String, MolangExpression>> rules(JsonObject json, String field) {
        List<Map<String, MolangExpression>> rules = new ArrayList<>();
        if (!json.has(field)) return List.copyOf(rules);

        for (JsonElement rule : json.getAsJsonArray(field)) {
            Map<String, MolangExpression> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> bone : rule.getAsJsonObject().entrySet()) {
                parsed.put(bone.getKey(), MolangParser.parse(bone.getValue().getAsString()));
            }
            rules.add(java.util.Collections.unmodifiableMap(parsed));
        }
        return List.copyOf(rules);
    }

    public static RenderController read(String name, JsonObject json) {
        AddonEntities.unsupportedFields("render controller", json, UNREAD);

        List<MolangExpression> textures = new ArrayList<>();
        if (json.has("textures")) {
            for (JsonElement texture : json.getAsJsonArray("textures")) {
                textures.add(MolangParser.parse(texture.getAsString()));
            }
        }

        List<Map<String, MolangExpression>> visibility = rules(json, "part_visibility");
        List<Map<String, MolangExpression>> materials = rules(json, "materials");

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
            molang(json.get("light_color_multiplier")),
            readArrays(json),
            ControllerColour.tryFrom(json, "color"),
            ControllerColour.tryFrom(json, "overlay_color"),
            ControllerColour.tryFrom(json, "is_hurt_color"),
            ControllerColour.tryFrom(json, "on_fire_color"));
    }

    /** A field Mojang types as Molang and vanilla writes as a plain number. Null where absent. */
    private static MolangExpression molang(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;

        return value.getAsJsonPrimitive().isString()
            ? MolangParser.parse(value.getAsString())
            : MolangExpression.constant(value.getAsFloat());
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

        return molang(animation.getAsJsonArray(field).get(axis));
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
