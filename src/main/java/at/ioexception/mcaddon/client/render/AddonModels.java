package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.model.BedrockGeometry;
import at.ioexception.mcaddon.bedrock.model.ControllerColour;
import at.ioexception.mcaddon.bedrock.model.GeometryBaker;
import at.ioexception.mcaddon.bedrock.model.MaskedMultitexture;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.bedrock.model.PartVisibility;
import at.ioexception.mcaddon.bedrock.model.RenderController;
import at.ioexception.mcaddon.bedrock.model.TexelMask;
import at.ioexception.mcaddon.bedrock.model.UvShift;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.convert.AlphaMask;
import at.ioexception.mcaddon.convert.AlphaTested;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
     * `visibility` hides a bone and everything hanging off it. `owned` names the bones whose own
     * boxes this pass draws, because a controller giving one bone a material of its own draws that
     * bone in a pass of its own while its children may still belong to this one.
     *
     * `colour` is packed ARGB and multiplies the texture, white leaving it as it is. `redWhenHurt` is
     * false for a pass whose controller states an `is_hurt_color`, which replaces the red Bedrock
     * otherwise flashes a hurt entity with and is folded into `colour` instead, and for a pass whose
     * material removes `USE_OVERLAY`.
     */
    public record Pass(BakedGeometry geometry, Identifier texture, PartVisibility visibility, PartVisibility owned,
        UvShift shift, MaterialDefinition material, int colour, boolean ignoresLighting, float lightMultiplier,
        boolean redWhenHurt) {
        /** Whether this pass draws the boxes of the bone of that name. */
        public boolean draws(String bone) {
            return !owned.hides(bone);
        }

        /** The overlay Java's entity shaders lay over the pass: the red of a hit, or none at all. */
        public int overlay(boolean hurt) {
            return OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(hurt && redWhenHurt));
        }

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
    private final Map<String, Optional<TexelMask>> masks = new HashMap<>();
    private final Map<String, AABB> bounds = new HashMap<>();
    private final Map<String, Optional<String>> alphaTested = new HashMap<>();
    private final Map<String, Optional<String>> tintWeights = new HashMap<>();
    private final Map<String, Optional<Identifier>> masked = new HashMap<>();

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

            passes.addAll(resolvePasses(controller, definition, environment));
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

        BakedGeometry geometry = geometryId == null ? null : bake(geometryId, definition);
        if (geometry == null || texturePath == null) return List.of();

        if (REPORTED.add(definition.identifier())) {
            at.ioexception.mcaddon.McAddon.LOGGER.warn(
                "{} chose no model from its render controllers; drawing its default.",
                definition.identifier());
        }
        return List.of(new Pass(geometry, textureId(texturePath), PartVisibility.EVERYTHING,
            PartVisibility.EVERYTHING, UvShift.NONE, null, OPAQUE_WHITE, false, 1f, true));
    }

    /**
     * The passes one controller draws: one for each material its `materials` list gives a bone,
     * each drawing the boxes of the bones that material is given to.
     */
    private List<Pass> resolvePasses(RenderController controller, ClientEntityDefinition definition,
        MolangEnvironment environment) {
        environment.setArrays(controller.arrays());

        String geometryKey = controller.geometry() == null
            ? "default" : controller.geometry().evaluate(environment).text();
        if (geometryKey == null) return List.of();

        String geometryId = definition.geometry().get(geometryKey);
        BakedGeometry geometry = geometryId == null ? null : bake(geometryId, definition);
        if (geometry == null) return List.of();

        List<String> texturePaths = new ArrayList<>();
        for (MolangExpression expression : controller.textures()) {
            MolangValue value = expression.evaluate(environment);
            texturePaths.add(value.isText() ? definition.textures().get(value.text()) : null);
        }
        String texturePath = texturePaths.stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (texturePath == null) return List.of();

        List<PartVisibility.Rule> visibility = new ArrayList<>();
        for (Map<String, MolangExpression> rule : controller.partVisibility()) {
            for (Map.Entry<String, MolangExpression> bone : rule.entrySet()) {
                visibility.add(new PartVisibility.Rule(bone.getKey(),
                    bone.getValue().evaluate(environment).truthy()));
            }
        }
        UvShift shift = new UvShift(
            number(controller.uvOffsetU(), environment, 0f),
            number(controller.uvOffsetV(), environment, 0f),
            number(controller.uvScaleU(), environment, 1f),
            number(controller.uvScaleV(), environment, 1f));

        List<BoneMaterial> boneMaterials = boneMaterials(controller, definition, environment);
        List<MaterialDefinition> materials = boneMaterials.isEmpty() ? NO_MATERIAL
            : boneMaterials.stream().map(BoneMaterial::material).distinct().toList();
        List<Pass> passes = new ArrayList<>();
        for (MaterialDefinition material : materials) {
            if (material != null && material.invisible()) continue;

            boolean tested = material != null && material.dropsFaintTexels();
            Identifier composed = material != null && material.masksSecondTexture() && texturePaths.size() > 1
                ? masked(texturePaths.get(0), texturePaths.get(1), tested) : null;
            String alphaTested = composed == null && tested ? alphaTested(texturePath) : null;
            Identifier texture = composed != null ? composed
                : alphaTested == null ? textureId(texturePath) : identifierOf(alphaTested);

            PartVisibility owned = materials.size() < 2 ? PartVisibility.EVERYTHING
                : new PartVisibility(boneMaterials.stream()
                    .map(rule -> new PartVisibility.Rule(rule.pattern(), rule.material().equals(material)))
                    .toList());
            int tinted = overlaid(controllerColour(controller, environment, material), controller, environment,
                material);
            Identifier weights = composed == null ? tintLayer(controller, material, texturePath) : null;
            float lightMultiplier = number(controller.lightMultiplier(), environment, 1f);
            boolean redWhenHurt = (material == null || material.laysOverlay()) && controller.hurt() == null;
            Pass pass = new Pass(geometry, texture, new PartVisibility(visibility), owned, shift, material,
                weights == null ? tinted : overlaid(OPAQUE_WHITE, controller, environment, material),
                controller.ignoresLighting(), lightMultiplier, redWhenHurt);
            passes.add(pass);
            if (weights != null) {
                passes.add(new Pass(geometry, weights, pass.visibility(), owned, shift, material.blended(),
                    tinted, controller.ignoresLighting(), lightMultiplier, redWhenHurt));
            }
            Pass glow = emissivePass(pass, definition, environment, controller);
            if (glow != null) passes.add(glow);
        }
        return passes;
    }

    /**
     * The texture a colour mask's tint is laid over its pass with, null where one colour over the
     * pass tints it exactly.
     *
     * Bedrock draws a texel under `USE_COLOR_MASK` as `mix(rgb, rgb * color, alpha)`, which one vertex
     * colour cannot carry where a texel is partly clear. Such a pass is drawn untinted and then again,
     * blended and tinted, from the texture as the pack painted it, so each texel's alpha weighs the
     * tint. A blended colour mask reads the same alpha as its fade as well, which two passes cannot
     * keep apart, so it is tinted whole and reported.
     */
    private Identifier tintLayer(RenderController controller, MaterialDefinition material, String texturePath) {
        if (controller.colour() == null || material == null || !material.readsControllerColour()) return null;

        String weights = tintWeights.computeIfAbsent(texturePath,
            bedrock -> Optional.ofNullable(converted.tintWeights(bedrock))).orElse(null);
        if (weights == null) return null;
        if (!material.blends()) return identifierOf(weights);

        if (REPORTED.add(controller.name() + " " + material.name() + " weights")) {
            AddonEntities.unsupported("color of " + controller.name() + " under " + material.name()
                + ", a blended colour mask over partly clear texels; tinted whole");
        }
        return null;
    }

    /** Opaque white, which leaves a texture drawn as it was painted. */
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;

    /** The tint of a group that declares nothing, so an undeclared channel changes no pixel. */
    private static final int TRANSPARENT = 0;

    /**
     * The two conditions a controller may tint under, which only the entity's own state answers.
     *
     * Hurt covers dying as well, which is when Bedrock's own ender dragon fades its hurt colour out.
     */
    private static final MolangExpression HURT = MolangParser.parse("query.hurt_time > 0 || !query.is_alive");
    private static final MolangExpression ON_FIRE = MolangParser.parse("query.is_on_fire");

    /**
     * The controller's `color`, which Bedrock's entity shaders read only under a colour mask, or
     * white for a pass drawn with any other material.
     */
    private static int controllerColour(RenderController controller, MolangEnvironment environment,
        MaterialDefinition material) {
        boolean tinted = controller.colour() != null && material != null && material.readsControllerColour();
        if (controller.colour() != null && material != null && material.readsControllerColourOtherwise()
            && REPORTED.add(controller.name() + " " + material.name())) {
            AddonEntities.unsupported("color of " + controller.name() + " under " + material.name()
                + ", which tints one channel or a layered texture; drawn untinted");
        }
        return tinted ? controller.colour().evaluate(environment, OPAQUE_WHITE) : OPAQUE_WHITE;
    }

    /**
     * A pass's colour with each of the controller's overlay groups laid over in turn, under a
     * material that lays them.
     *
     * Java tints a whole pass with one vertex colour, so the three overlay groups are folded into
     * that colour by their own alpha rather than drawn as passes of their own.
     */
    private static int overlaid(int colour, RenderController controller, MolangEnvironment environment,
        MaterialDefinition material) {
        if (material != null && !material.laysOverlay()) return colour;

        int laid = over(colour, controller.overlay(), environment);
        if (controller.hurt() != null && HURT.evaluate(environment).truthy()) {
            laid = over(laid, controller.hurt(), environment);
        }
        if (controller.onFire() != null && ON_FIRE.evaluate(environment).truthy()) {
            laid = over(laid, controller.onFire(), environment);
        }
        return laid;
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
        return new Pass(pass.geometry(), texture, pass.visibility(), pass.owned(), pass.shift(), EMISSIVE,
            pass.colour(), pass.ignoresLighting(), pass.lightMultiplier(), pass.redWhenHurt());
    }

    /** The material an extracted emissive layer is drawn with: lit, and blended over what is below. */
    private static final MaterialDefinition EMISSIVE = new MaterialDefinition("emissive", "emissive",
        Set.of("USE_EMISSIVE"), Set.of(), Set.of(), Set.of());

    /** What a controller naming no material the definition carries draws its one pass with: Bedrock's default. */
    private static final List<MaterialDefinition> NO_MATERIAL = java.util.Collections.singletonList(null);

    /** One entry of a controller's `materials` list: the bones it names and the material they take. */
    private record BoneMaterial(String pattern, MaterialDefinition material) {
    }

    /**
     * The material each bone draws with, which decides blending, culling, whether it glows and
     * whether the pass's second texture is drawn over its first.
     *
     * A controller lists one material per bone pattern, and a later entry answers for a bone an
     * earlier one also matched, the way `part_visibility` does: Mojang's axolotl draws `*` with one
     * material and `*leg` with another. An entry naming a material the definition does not carry is
     * passed over. A bone no entry matches takes the first material, which is what an entity whose
     * one entry names a single bone draws everything else with.
     */
    private List<BoneMaterial> boneMaterials(RenderController controller,
        ClientEntityDefinition definition, MolangEnvironment environment) {
        List<BoneMaterial> materials = new ArrayList<>();
        for (Map<String, MolangExpression> rule : controller.materials()) {
            for (Map.Entry<String, MolangExpression> bone : rule.entrySet()) {
                MolangValue value = bone.getValue().evaluate(environment);
                String name = value.isText() ? definition.materials().get(value.text()) : null;
                MaterialDefinition material = name == null ? null : assets.material(name);
                if (material == null) continue;

                if (materials.isEmpty()) materials.add(new BoneMaterial("*", material));
                materials.add(new BoneMaterial(bone.getKey(), material));
            }
        }
        return materials;
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

    /**
     * A geometry baked for one definition, because a texture mesh names its texture the way that
     * definition's own `textures` map does.
     */
    private BakedGeometry bake(String identifier, ClientEntityDefinition definition) {
        return baked.computeIfAbsent(definition.identifier() + " " + identifier, key -> {
            BedrockGeometry geometry = assets.geometry(identifier);
            return geometry == null ? null : GeometryBaker.bake(geometry, name -> mask(definition, name));
        });
    }

    /** Which texels of the texture the definition calls `name` are drawn, null where it has none. */
    private TexelMask mask(ClientEntityDefinition definition, String name) {
        String path = definition.textures().get(name);
        TexelMask mask = path == null ? null : masks.computeIfAbsent(path, bedrock -> Optional
            .ofNullable(converted.textureFile(bedrock)).map(TexelMask::tryRead)).orElse(null);
        if (mask == null) {
            AddonEntities.unsupported("texture mesh " + name + " on " + definition.identifier()
                + ", which has no such texture");
        }
        return mask;
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
    Identifier textureId(String path) {
        String mapped = converted.texturePaths().get(path);
        if (mapped == null) {
            if (REPORTED.add(path)) {
                AddonEntities.unsupported("texture " + path + ", drawn as the placeholder");
            }
            return Platform.id("minecraft", "textures/misc/white.png");
        }
        return identifierOf(mapped);
    }

    /**
     * The form of a texture Bedrock's plain alpha test draws, as the conversion filed it, or null
     * where the conversion wrote none. Asked once per texture, because a pass is resolved every frame.
     */
    private String alphaTested(String path) {
        return alphaTested.computeIfAbsent(path, bedrock -> Optional.ofNullable(converted.alphaTested(bedrock)))
            .orElse(null);
    }

    /**
     * The one image a masked multitexture pass draws its two textures as, null where either of them
     * was not converted. Each pair is put together once and kept, because a pass is resolved every
     * frame.
     *
     * Bedrock mixes the two textures as the pack painted them and then tests the alpha of the mix,
     * so both are read with their faint texels as faint as they were shipped. The second one's alpha
     * also decides which of the two shows.
     */
    private Identifier masked(String first, String second, boolean tested) {
        if (first == null || second == null) return null;

        return masked.computeIfAbsent(first + "\n" + second + "\n" + tested,
            key -> Optional.ofNullable(compose(first, second, tested))).orElse(null);
    }

    private Identifier compose(String first, String second, boolean tested) {
        Path firstFile = converted.paintedFile(first);
        Path secondFile = converted.paintedFile(second);
        if (firstFile == null || secondFile == null) return null;

        try {
            BufferedImage drawn = new MaskedMultitexture(ImageIO.read(firstFile.toFile()),
                ImageIO.read(secondFile.toFile())).composed();
            BufferedImage shown = tested
                ? Optional.ofNullable(AlphaTested.tryFrom(drawn)).map(AlphaTested::halved).orElse(drawn)
                : Optional.ofNullable(AlphaMask.tryFrom(drawn)).map(AlphaMask::opaque).orElse(drawn);
            String firstId = converted.texturePaths().get(first);
            String secondId = converted.texturePaths().get(second);
            Identifier texture = Platform.id(converted.namespace(), "textures/masked/"
                + firstId.replace(':', '/') + "/" + secondId.replace(':', '/') + (tested ? AlphaTested.SUFFIX : ""));
            register(texture, shown);
            return texture;
        } catch (IOException | RuntimeException error) {
            AddonEntities.unsupported("texture " + second + " masked over " + first + ", which could not be read");
            return null;
        }
    }

    /**
     * Hands the client an image it draws from by that id, which is how an image made while the game
     * runs reaches a render type. 1.21.5 gave a texture a label to be known by in debugging.
     */
    private static void register(Identifier id, BufferedImage image) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", png);
        NativeImage pixels = NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
        //? if >=1.21.5 {
        DynamicTexture texture = new DynamicTexture(id::toString, pixels);
        //?} else {
        /*DynamicTexture texture = new DynamicTexture(pixels);
        *///?}
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    private static Identifier identifierOf(String mapped) {
        int colon = mapped.indexOf(':');
        return Platform.id(mapped.substring(0, colon), "textures/" + mapped.substring(colon + 1) + ".png");
    }
}
