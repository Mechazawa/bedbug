package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.convert.SpawnEggAppearance;
import at.ioexception.mcaddon.pack.SemVer;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads one client entity definition, which is the `description` of a `minecraft:client_entity`. */
public final class ClientEntityParser {

    /**
     * The two description fields about the item an engine puts in an entity's hand.
     *
     * `held_item_scale` and `held_item_ignores_lighting` size and light it, and the only thing
     * drawn in a hand here is the attachable a pack describes for it, which carries its own scale
     * and its own material.
     */
    private static final List<String> UNREAD = List.of(
        "held_item_scale", "held_item_ignores_lighting");

    /**
     * A script field with nothing to decide here.
     *
     * `hide_held_items` hides the item the engine puts in an entity's hand, and the only thing
     * drawn in a hand here is the attachable a pack describes for it.
     */
    private static final List<String> UNREAD_SCRIPTS = List.of("hide_held_items");

    private ClientEntityParser() {
    }

    public static ClientEntityDefinition read(String name, JsonObject json) {
        JsonObject scripts = json.has("scripts") ? json.getAsJsonObject("scripts") : new JsonObject();
        AddonEntities.unsupportedFields("client entity", json, UNREAD);
        AddonEntities.unsupportedFields("client entity", scripts, UNREAD_SCRIPTS);

        // A definition naming no engine is as old as a pack goes, so it yields to any that names one.
        SemVer engine = SemVer.tryFrom(json.get("min_engine_version"));

        return new ClientEntityDefinition(
            name,
            engine == null ? SemVer.ZERO : engine,
            json.has("enable_attachables") && json.get("enable_attachables").getAsBoolean(),
            json.has("hide_armor") && json.get("hide_armor").getAsBoolean(),
            scripts.has("should_update_effects_offscreen")
                && scripts.get("should_update_effects_offscreen").getAsBoolean(),
            scripts.has("should_update_bones_and_effects_offscreen")
                && scripts.get("should_update_bones_and_effects_offscreen").getAsBoolean(),
            strings(json, "geometry"),
            strings(json, "textures"),
            strings(json, "materials"),
            strings(json, "animations"),
            named(json.getAsJsonArray("animation_controllers")),
            strings(json, "sound_effects"),
            strings(json, "particle_effects"),
            strings(json, "particle_emitters"),
            conditions(json.get("item")),
            gated(scripts.getAsJsonArray("animate")),
            gated(json.getAsJsonArray("render_controllers")),
            statements(scripts, "initialize"),
            statements(scripts, "pre_animation"),
            statements(scripts, "parent_setup"),
            published(scripts.get("variables")),
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

    /**
     * A map of names against the Molang test that has to hold for each, kept in the pack's order.
     *
     * An attachable's `item` is written this way: the item's own identifier against a condition,
     * which is what picks one of several attachables offered for the same item.
     */
    private static Map<String, MolangExpression> conditions(JsonElement json) {
        if (json == null || !json.isJsonObject()) return Map.of();

        Map<String, MolangExpression> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
            values.put(entry.getKey(), MolangParser.parse(primitive(entry.getValue())));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * The variables a pack lets whatever this entity carries read back.
     *
     * A pack writes the whole `variable.name` against the one scope Bedrock offers, so every name
     * the block carries is published. The prefix and the case are dropped, because that is how a
     * variable is keyed once an expression has been parsed.
     */
    private static Set<String> published(JsonElement json) {
        if (json == null || !json.isJsonObject()) return Set.of();

        Set<String> names = new LinkedHashSet<>();
        for (String name : json.getAsJsonObject().keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);
            names.add(lower.startsWith("variable.") ? lower.substring("variable.".length())
                : lower.startsWith("v.") ? lower.substring("v.".length()) : lower);
        }
        return Set.copyOf(names);
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

        JsonElement body = json.get(field);
        // One statement may be written on its own rather than in a list, which `parent_setup` is.
        if (!body.isJsonArray()) return List.of(MolangParser.parse(body.getAsString()));

        List<MolangExpression> parsed = new ArrayList<>();
        for (JsonElement entry : body.getAsJsonArray()) parsed.add(MolangParser.parse(entry.getAsString()));
        return List.copyOf(parsed);
    }

    private static Object primitive(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) return json.getAsDouble();
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) return json.getAsBoolean() ? 1d : 0d;
        return json.getAsString();
    }

}
