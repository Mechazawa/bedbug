package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.model.BedrockGeometry;
import at.ioexception.mcaddon.bedrock.model.GeometryBaker;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.bedrock.model.RenderController;
import at.ioexception.mcaddon.bedrock.model.UvShift;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one pack's render controllers into the passes to draw.
 *
 * A Bedrock entity is drawn several times over, each pass choosing its own geometry, texture and set
 * of hidden bones from Molang. Everything in a controller is an expression, so a pass cannot be
 * worked out once and kept: it is resolved from the entity's current variables each frame.
 */
public final class AddonModels {
    /**
     * One pass: a baked model, the texture to draw it with, and the bones it leaves out.
     *
     * A hidden entry may be a glob, because a costume hides a whole family of bones at once.
     */
    public record Pass(BakedGeometry geometry, Identifier texture, Set<String> hiddenBones, UvShift shift,
        MaterialDefinition material) {
        public boolean hides(String bone) {
            for (String pattern : hiddenBones) {
                if (pattern.endsWith("*")
                    ? bone.startsWith(pattern.substring(0, pattern.length() - 1))
                    : pattern.equals(bone)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final BedrockAssets assets;
    private final ConvertedPack converted;
    private final Map<String, BakedGeometry> baked = new HashMap<>();

    public AddonModels(BedrockAssets assets, ConvertedPack converted) {
        this.assets = assets;
        this.converted = converted;
    }

    public BedrockAssets assets() {
        return assets;
    }

    public ConvertedPack converted() {
        return converted;
    }

    public List<Pass> resolve(EntityAnimator animator) {
        ClientEntityDefinition definition = animator.definition();
        MolangEnvironment environment = animator.environment();
        List<Pass> passes = new ArrayList<>();

        for (ClientEntityDefinition.Gated entry : definition.renderControllers()) {
            if (entry.condition() != null && !entry.condition().evaluate(environment).truthy()) continue;

            RenderController controller = assets.renderController(entry.name());
            if (controller == null) continue;

            Pass pass = resolvePass(controller, definition, environment);
            if (pass == null) continue;

            passes.add(pass);
            Pass glow = emissivePass(pass, definition, environment, controller);
            if (glow != null) passes.add(glow);
        }
        return passes;
    }

    private Pass resolvePass(RenderController controller, ClientEntityDefinition definition,
        MolangEnvironment environment) {
        environment.setArrays(controller.arrays());

        String geometryKey = controller.geometry() == null
            ? "default" : controller.geometry().evaluate(environment).text();
        if (geometryKey == null) return null;

        String geometryId = definition.geometry().get(geometryKey);
        BakedGeometry geometry = geometryId == null ? null : bake(geometryId);
        if (geometry == null) return null;

        Identifier texture = null;
        for (MolangExpression expression : controller.textures()) {
            MolangValue value = expression.evaluate(environment);
            String path = value.isText() ? definition.textures().get(value.text()) : null;
            if (path != null) {
                texture = textureId(path);
                break;
            }
        }
        if (texture == null) return null;

        // Later rules override earlier ones, which is how a costume hides a family of bones at once.
        Set<String> hidden = new HashSet<>();
        for (Map<String, MolangExpression> rule : controller.partVisibility()) {
            for (Map.Entry<String, MolangExpression> bone : rule.entrySet()) {
                if (bone.getValue().evaluate(environment).truthy()) hidden.remove(bone.getKey());
                else hidden.add(bone.getKey());
            }
        }
        MaterialDefinition material = resolveMaterial(controller, definition, environment);
        if (material != null && material.invisible()) return null;

        return new Pass(geometry, texture, hidden,
            new UvShift(offset(controller.uvOffsetU(), environment), offset(controller.uvOffsetV(), environment)),
            material);
    }

    /**
     * A second, fully lit pass over the glowing pixels of a physically based texture.
     *
     * Bedrock lights those pixels through a deferred pipeline Java does not have. Drawing the same
     * model again with a texture masked to them, at full brightness, is what Java can do instead, and
     * a material that already declares itself emissive needs no second pass.
     */
    private Pass emissivePass(Pass pass, ClientEntityDefinition definition,
        MolangEnvironment environment, RenderController controller) {
        if (pass.material() != null && pass.material().emissive()) return null;

        String bedrockPath = null;
        for (MolangExpression expression : controller.textures()) {
            MolangValue value = expression.evaluate(environment);
            String path = value.isText() ? definition.textures().get(value.text()) : null;
            if (path != null) {
                bedrockPath = path;
                break;
            }
        }

        String glow = bedrockPath == null ? null : converted.emissiveLayers().get(bedrockPath);
        if (glow == null) return null;

        int colon = glow.indexOf(':');
        Identifier texture = Platform.id(glow.substring(0, colon),
            "textures/" + glow.substring(colon + 1) + ".png");
        return new Pass(pass.geometry(), texture, pass.hiddenBones(), pass.shift(), EMISSIVE);
    }

    /** The material an extracted emissive layer is drawn with: lit, and blended over what is below. */
    private static final MaterialDefinition EMISSIVE = new MaterialDefinition("emissive", "emissive",
        Set.of("USE_EMISSIVE"), Set.of(), Set.of(), Set.of());

    /**
     * The material a pass draws with, which decides blending, culling and whether it glows.
     *
     * A controller lists one material per bone pattern; the entry for every bone is the one that
     * describes the pass as a whole, so that is the one taken.
     */
    private MaterialDefinition resolveMaterial(RenderController controller,
        ClientEntityDefinition definition, MolangEnvironment environment) {
        for (Map<String, MolangExpression> rule : controller.materials()) {
            MolangExpression expression = rule.get("*");
            if (expression == null) expression = rule.values().stream().findFirst().orElse(null);
            if (expression == null) continue;

            MolangValue value = expression.evaluate(environment);
            String key = value.isText() ? value.text() : null;
            String name = key == null ? null : definition.materials().get(key);
            if (name != null) return assets.material(name);
        }
        return null;
    }

    private BakedGeometry bake(String identifier) {
        return baked.computeIfAbsent(identifier, id -> {
            BedrockGeometry geometry = assets.geometry(id);
            return geometry == null ? null : GeometryBaker.bake(geometry);
        });
    }

    private static float offset(MolangExpression expression, MolangEnvironment environment) {
        return expression == null ? 0f : (float) expression.evaluate(environment).number();
    }

    /**
     * The Bedrock path as the definition wrote it, mapped to where the conversion filed it.
     *
     * The map is consulted rather than the path rebuilt, because two packs can hold textures with the
     * same name and the conversion is what kept them apart.
     */
    private Identifier textureId(String path) {
        String mapped = converted.texturePaths().get(path);
        if (mapped == null) return Platform.id("minecraft", "textures/misc/white.png");

        int colon = mapped.indexOf(':');
        return Platform.id(mapped.substring(0, colon), "textures/" + mapped.substring(colon + 1) + ".png");
    }
}
