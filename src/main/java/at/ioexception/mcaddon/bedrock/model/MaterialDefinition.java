package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A `.material` entry, reduced to the parts Java can draw.
 *
 * A Bedrock material is a shader variant: a list of defines and render states layered onto a parent.
 * Most of what it says has a Java counterpart, because alpha testing, blending, culling and depth
 * behaviour are what a render type is. What has none is the stencil buffer programming some
 * materials use to mask one pass against another.
 *
 * @param parent the material this one is written on top of, which carries the base behaviour
 */
public record MaterialDefinition(String name, String parent, Set<String> defines, Set<String> states,
    Set<String> removedStates) {

    /** Drawn with blending rather than an alpha test, so the texture's alpha is a real fade. */
    public boolean blends() {
        return states.contains("Blending") || parent.contains("alphablend") || parent.contains("blend");
    }

    /** Lit at full brightness, which is what Bedrock's emissive materials look like. */
    public boolean emissive() {
        return defines.contains("USE_EMISSIVE") || defines.contains("USE_ONLY_EMISSIVE")
            || parent.contains("emissive");
    }

    /**
     * Whether back faces are dropped.
     *
     * Bedrock's entity materials disable culling in their base, so a pack turns it back on by
     * removing that state rather than by adding one. An absent state and a removed one mean
     * different things here, which is why both are kept.
     */
    public boolean culls() {
        return removedStates.contains("DisableCulling");
    }

    /**
     * A pass that writes no colour is a stencil prepass and has nothing to show.
     *
     * Java cannot mask a later pass against it, so the choice is drawing it as an opaque shape that
     * does not belong there or leaving it out. Leaving it out is the closer of the two.
     */
    public boolean invisible() {
        return states.contains("DisableColorWrite");
    }

    /** Whether the material asks for something only a stencil buffer can do. */
    public boolean needsStencil() {
        return states.contains("EnableStencilTest") || states.contains("StencilWrite");
    }

    public static Map<String, MaterialDefinition> readAll(BedrockPack pack) {
        Map<String, MaterialDefinition> materials = new LinkedHashMap<>();
        for (Path file : pack.list("materials", ".material")) {
            try {
                JsonObject body = Json.parseObject(file).getAsJsonObject("materials");
                if (body == null) continue;

                for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;

                    String[] halves = entry.getKey().split(":", 2);
                    MaterialDefinition material = read(halves[0],
                        halves.length > 1 ? halves[1] : "", entry.getValue().getAsJsonObject());
                    materials.put(material.name(), material);
                }
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }

        Map<String, MaterialDefinition> flattened = new LinkedHashMap<>();
        materials.forEach((name, material) -> flattened.put(name, resolved(material, materials, 0)));
        return flattened;
    }

    private static MaterialDefinition read(String name, String parent, JsonObject body) {
        Set<String> defines = new LinkedHashSet<>();
        Set<String> states = new LinkedHashSet<>();

        for (JsonElement define : Json.list(body, "+defines")) {
            if (define.isJsonPrimitive()) defines.add(define.getAsString());
        }
        for (JsonElement state : Json.list(body, "+states")) {
            if (state.isJsonPrimitive()) states.add(state.getAsString());
        }
        Set<String> removed = new LinkedHashSet<>();
        for (JsonElement state : Json.list(body, "-states")) {
            if (state.isJsonPrimitive()) removed.add(state.getAsString());
        }
        for (JsonElement define : Json.list(body, "-defines")) {
            if (define.isJsonPrimitive()) defines.remove(define.getAsString());
        }
        states.removeAll(removed);
        return new MaterialDefinition(name, parent, defines, states, removed);
    }

    /**
     * Folds a material's parents into it, for the chains written inside one pack.
     *
     * A parent that is not in the file is one of the game's own, and its name is what carries the
     * base behaviour.
     */
    private static MaterialDefinition resolved(MaterialDefinition material,
        Map<String, MaterialDefinition> materials, int depth) {
        MaterialDefinition parent = depth > 8 ? null : materials.get(material.parent());
        if (parent == null) return material;

        MaterialDefinition base = resolved(parent, materials, depth + 1);
        Set<String> defines = new LinkedHashSet<>(base.defines());
        defines.addAll(material.defines());

        Set<String> states = new LinkedHashSet<>(base.states());
        states.addAll(material.states());
        states.removeAll(material.removedStates());

        Set<String> removed = new LinkedHashSet<>(base.removedStates());
        removed.addAll(material.removedStates());
        return new MaterialDefinition(material.name(), base.parent(), defines, states, removed);
    }
}
