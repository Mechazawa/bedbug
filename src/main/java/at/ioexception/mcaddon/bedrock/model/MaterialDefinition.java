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
    Set<String> removedStates, Set<String> removedDefines) {

    /** Drawn with blending rather than an alpha test, so the texture's alpha is a real fade. */
    public boolean blends() {
        return states.contains("Blending") || parent.contains("alphablend") || parent.contains("blend");
    }

    /**
     * Whether a texel under half opacity is dropped, which Bedrock's alpha test does wherever
     * nothing else reads the alpha.
     *
     * A colour mask and a multiplicative tint read the alpha as how much of a texel to tint and
     * drop only a fully clear one, an emissive material reads it as how brightly the texel glows,
     * and a material without the test draws every texel whatever its alpha.
     */
    public boolean dropsFaintTexels() {
        boolean tested = defines.contains("ALPHA_TEST") || parent.contains("alphatest")
            || GAME_MASKED.contains(parent) && !removedDefines.contains("ALPHA_TEST");
        boolean readsAlpha = readsControllerColour() || defines.contains("MULTIPLICATIVE_TINT")
            || defines.contains("MULTI_COLOR_TINT") || parent.contains("change_color");
        return tested && !readsAlpha && !emissive() && !blends();
    }

    /**
     * Whether the pass is tinted by its render controller's `color`.
     *
     * Bedrock's entity shaders read that colour as `ChangeColor`, and only under `USE_COLOR_MASK`
     * over the whole texture: each texel is mixed toward itself times the colour by its own alpha,
     * and its alpha is multiplied by the colour's. Every other material leaves the colour unread,
     * `entity_alphatest` and `entity_alphablend` among them. The overlay groups are a different
     * uniform, which every entity material reads.
     */
    public boolean readsControllerColour() {
        return defines.contains("USE_COLOR_MASK")
            || GAME_COLOUR_MASKED.contains(parent) && !removedDefines.contains("USE_COLOR_MASK");
    }

    /**
     * Whether the material reads the controller's `color` in a way one colour over the pass cannot
     * carry: `MULTI_COLOR_TINT` tints by the texture's red channel alone, and `COLOR_SECOND_TEXTURE`
     * tints a third texture layered over the first.
     */
    public boolean readsControllerColourOtherwise() {
        return defines.contains("MULTI_COLOR_TINT") || defines.contains("COLOR_SECOND_TEXTURE")
            || GAME_COLOUR_LAYERED.contains(parent);
    }

    /** The game's own entity materials that define `USE_COLOR_MASK`, from its `entity.material`. */
    private static final Set<String> GAME_COLOUR_MASKED = Set.of(
        "entity_change_color", "entity_change_color_glint", "entity_alphatest_change_color",
        "entity_alphatest_change_color_glint", "entity_multitexture_multiplicative_blend", "guardian_ghost",
        "player_spectator", "warden_bioluminescent_layer", "breeze_eyes", "creaking_eyes", "armor_leather",
        "armor_leather_enchanted", "cat", "sheep", "wolf", "wolf_armor", "tropicalfish");

    /** The game's own entity materials that define `MULTI_COLOR_TINT` or `COLOR_SECOND_TEXTURE`. */
    private static final Set<String> GAME_COLOUR_LAYERED = Set.of("entity_alphatest_multicolor_tint",
        "entity_multitexture_color_mask", "entity_multitexture_alpha_test_color_mask", "horse_leather_armor");

    /**
     * Whether the pass draws its second texture over its first, with the second's clear but coloured
     * texels showing the first through.
     *
     * The game's villager outfits are drawn this way: the biome's clothes are the first texture and
     * the profession's are the second.
     */
    public boolean masksSecondTexture() {
        return defines.contains("MASKED_MULTITEXTURE")
            || GAME_MASKED.contains(parent) && !removedDefines.contains("MASKED_MULTITEXTURE");
    }

    /**
     * The game's own materials that define `MASKED_MULTITEXTURE`, which a pack names without a file.
     *
     * Each is `entity_alphatest` with the define added, so each also tests alpha.
     */
    private static final Set<String> GAME_MASKED =
        Set.of("entity_multitexture_masked", "villager_v2_masked", "zombie_villager_v2_masked");

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

    /**
     * One of the game's own materials, which a pack names without writing a file for it.
     *
     * Its behaviour is carried by its name the same way a parent's is, so `entity_alphablend`
     * blends and `entity_emissive_alpha` glows.
     */
    public static MaterialDefinition ofGame(String name) {
        return new MaterialDefinition(name, name, Set.of(), Set.of(), Set.of(), Set.of());
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

        // A removal is kept rather than applied here, because what it usually cancels is a define the
        // parent added, and the parent is not known until the chain is folded together.
        Set<String> removedDefines = new LinkedHashSet<>();
        for (JsonElement define : Json.list(body, "-defines")) {
            if (define.isJsonPrimitive()) removedDefines.add(define.getAsString());
        }
        defines.removeAll(removedDefines);
        states.removeAll(removed);
        return new MaterialDefinition(name, parent, defines, states, removed, removedDefines);
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
        defines.removeAll(material.removedDefines());

        Set<String> states = new LinkedHashSet<>(base.states());
        states.addAll(material.states());
        states.removeAll(material.removedStates());

        Set<String> removed = new LinkedHashSet<>(base.removedStates());
        removed.addAll(material.removedStates());

        Set<String> removedDefines = new LinkedHashSet<>(base.removedDefines());
        removedDefines.addAll(material.removedDefines());
        return new MaterialDefinition(material.name(), base.parent(), defines, states, removed,
            removedDefines);
    }
}
