package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.model.BedrockGeometry;
import at.ioexception.mcaddon.bedrock.model.ControllerColour;
import at.ioexception.mcaddon.bedrock.model.GeometryBaker;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.bedrock.model.PartVisibility;
import at.ioexception.mcaddon.bedrock.model.RenderController;
import at.ioexception.mcaddon.bedrock.model.UvShift;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

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
     * One pass: a baked model, the texture to draw it with, the bones it draws and its tint.
     *
     * `colour` is packed ARGB and multiplies the texture, white leaving it as it is.
     */
    public record Pass(BakedGeometry geometry, Identifier texture, PartVisibility visibility, UvShift shift,
        MaterialDefinition material, int colour, boolean ignoresLighting, float lightMultiplier) {
        /**
         * What this pass draws under: the light around the entity, or none of it, scaled by what
         * the controller asked for.
         *
         * Java carries the two light channels as levels rather than as a colour, so a multiplier
         * moves both of them and stops at the brightest level there is.
         */
        public int light(int light) {
            int lit = ignoresLighting ? FULL_BRIGHT : light;
            if (lightMultiplier == 1f) return lit;

            return scale(lit >>> 16 & 0xFFFF) << 16 | scale(lit & 0xFFFF);
        }

        private int scale(int channel) {
            return Math.min(FULL_BRIGHT & 0xFFFF, Math.round(channel * lightMultiplier));
        }

    }

    /** Both light channels at their maximum, which is how Minecraft says "lit as if by the sun". */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * The entities already named as drawing nothing and the texture paths already named as missing.
     *
     * A pass is resolved once per frame, so each of these would otherwise be counted thousands of
     * times over for one thing being wrong once.
     */
    private static final Set<String> REPORTED = new HashSet<>();

    private final BedrockAssets assets;
    private final ConvertedPack converted;
    private final Map<String, BakedGeometry> baked = new HashMap<>();
    private final Map<String, AABB> bounds = new HashMap<>();

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
        return passes.isEmpty() ? fallback(definition) : passes;
    }

    /**
     * What to draw for an entity whose own controllers chose nothing.
     *
     * A controller picks its geometry and texture by name, and a name it works out that the entity
     * does not carry leaves the pass with nothing to draw. Drawing nothing at all makes the entity
     * invisible while whatever is worn over it stays in the air, so the first geometry and texture
     * the entity does carry are drawn instead and the miss is reported.
     */
    private List<Pass> fallback(ClientEntityDefinition definition) {
        String geometryId = definition.geometry().get("default");
        if (geometryId == null) {
            geometryId = definition.geometry().values().stream().findFirst().orElse(null);
        }
        String texturePath = definition.textures().getOrDefault("default",
            definition.textures().values().stream().findFirst().orElse(null));

        BakedGeometry geometry = geometryId == null ? null : bake(geometryId);
        if (geometry == null || texturePath == null) return List.of();

        if (REPORTED.add(definition.identifier())) {
            at.ioexception.mcaddon.McAddon.LOGGER.warn(
                "{} chose no model from its render controllers; drawing its default.",
                definition.identifier());
        }
        return List.of(new Pass(geometry, textureId(texturePath), PartVisibility.EVERYTHING,
            UvShift.NONE, null, OPAQUE_WHITE, false, 1f));
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

        List<PartVisibility.Rule> visibility = new ArrayList<>();
        for (Map<String, MolangExpression> rule : controller.partVisibility()) {
            for (Map.Entry<String, MolangExpression> bone : rule.entrySet()) {
                visibility.add(new PartVisibility.Rule(bone.getKey(),
                    bone.getValue().evaluate(environment).truthy()));
            }
        }
        MaterialDefinition material = resolveMaterial(controller, definition, environment);
        if (material != null && material.invisible()) return null;

        UvShift shift = new UvShift(
            number(controller.uvOffsetU(), environment, 0f),
            number(controller.uvOffsetV(), environment, 0f),
            number(controller.uvScaleU(), environment, 1f),
            number(controller.uvScaleV(), environment, 1f));
        return new Pass(geometry, texture, new PartVisibility(visibility), shift, material,
            tint(controller, environment), controller.ignoresLighting(),
            number(controller.lightMultiplier(), environment, 1f));
    }

    /** Opaque white, which leaves a texture drawn as it was painted. */
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;

    /** The tint of a group that declares nothing, so an undeclared channel changes no pixel. */
    private static final int TRANSPARENT = 0;

    /** The two conditions a controller may tint under, which only the entity's own state answers. */
    private static final MolangExpression HURT = MolangParser.parse("query.hurt_time > 0");
    private static final MolangExpression ON_FIRE = MolangParser.parse("query.is_on_fire");

    /**
     * What one pass multiplies its texture by: its colour, with each overlay laid over in turn.
     *
     * Java tints a whole pass with one vertex colour, so the three overlay groups are folded into
     * that colour by their own alpha rather than drawn as passes of their own.
     */
    private static int tint(RenderController controller, MolangEnvironment environment) {
        int colour = controller.colour() == null
            ? OPAQUE_WHITE : controller.colour().evaluate(environment, OPAQUE_WHITE);

        colour = over(colour, controller.overlay(), environment);
        if (controller.hurt() != null && HURT.evaluate(environment).truthy()) {
            colour = over(colour, controller.hurt(), environment);
        }
        if (controller.onFire() != null && ON_FIRE.evaluate(environment).truthy()) {
            colour = over(colour, controller.onFire(), environment);
        }
        return colour;
    }

    private static int over(int colour, ControllerColour group, MolangEnvironment environment) {
        if (group == null) return colour;

        int laid = group.evaluate(environment, TRANSPARENT);
        float weight = (laid >>> 24) / 255f;
        if (weight <= 0f) return colour;

        return (colour & 0xFF000000)
            | mix(colour, laid, weight, 16)
            | mix(colour, laid, weight, 8)
            | mix(colour, laid, weight, 0);
    }

    private static int mix(int colour, int laid, float weight, int shift) {
        int under = colour >> shift & 0xFF;
        int over = laid >> shift & 0xFF;
        return Math.round(under + (over - under) * weight) << shift;
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
        return new Pass(pass.geometry(), texture, pass.visibility(), pass.shift(), EMISSIVE,
            pass.colour(), pass.ignoresLighting(), pass.lightMultiplier());
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

    /**
     * The box the entity's own geometry says it fills, with its feet at the origin.
     *
     * A render controller picks one geometry per pass out of everything the definition offers, so
     * the box has to hold all of them rather than whichever is drawn this frame. Null where no
     * geometry declares one.
     */
    public AABB visibleBounds(ClientEntityDefinition definition) {
        if (bounds.containsKey(definition.identifier())) return bounds.get(definition.identifier());

        AABB widest = null;
        for (String name : definition.geometry().values()) {
            BedrockGeometry geometry = assets.geometry(name);
            AABB declared = geometry == null ? null : geometry.visibleBounds();
            if (declared != null) widest = widest == null ? declared : widest.minmax(declared);
        }
        bounds.put(definition.identifier(), widest);
        return widest;
    }

    private BakedGeometry bake(String identifier) {
        return baked.computeIfAbsent(identifier, id -> {
            BedrockGeometry geometry = assets.geometry(id);
            return geometry == null ? null : GeometryBaker.bake(geometry);
        });
    }

    private static float number(MolangExpression expression, MolangEnvironment environment, float fallback) {
        return expression == null ? fallback : (float) expression.evaluate(environment).number();
    }

    /**
     * The Bedrock path as the definition wrote it, mapped to where the conversion filed it.
     *
     * The map is consulted rather than the path rebuilt, because two packs can hold textures with the
     * same name and the conversion is what kept them apart. A path the conversion never filed leaves
     * the pass drawing plain white, which looks like a lit model with its markings gone rather than
     * like anything failing, so it is named against the pack as well.
     */
    private Identifier textureId(String path) {
        String mapped = converted.texturePaths().get(path);
        if (mapped == null) {
            if (REPORTED.add(path)) {
                AddonEntities.unsupported("texture " + path + ", drawn as the placeholder");
            }
            return Platform.id("minecraft", "textures/misc/white.png");
        }

        int colon = mapped.indexOf(':');
        return Platform.id(mapped.substring(0, colon), "textures/" + mapped.substring(colon + 1) + ".png");
    }
}
