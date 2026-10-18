package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.AddonClientAssets;
import at.ioexception.mcaddon.client.render.PackTextures;
import at.ioexception.mcaddon.client.render.ScriptClock;
import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * A render controller draws what its Molang picks, and draws something else once the entity changes.
 *
 * `tp:prism` has one controller that decides every part of its one pass from the entity: the
 * geometry from `q.is_baby` and the `tp:facet` property, the texture from an array indexed by
 * `q.variant`, the material from `q.had_component_group`, the mark bone's visibility from
 * `q.mark_variant`, the texture's slide from the variant again, its colour from the variant, an
 * overlay from the mark variant and a colour of its own while the entity burns. Each event the test
 * raises changes one of those, and the frame the renderer submits next has to show that change.
 *
 * The three geometries are told apart by how far their core reaches from the middle, which does not
 * depend on which way the prism faces: two pixels for the round one, four for the square one and
 * one for the young one, each across the diagonal of a square.
 */
public final class RenderControllerClientGameTest implements FabricClientGameTest {
    private static final double TOLERANCE = 0.002;

    private static final double ROUND_REACH = Math.sqrt(2) * 2 / 16;
    private static final double SQUARE_REACH = Math.sqrt(2) * 4 / 16;
    private static final double YOUNG_REACH = Math.sqrt(2) / 16;

    /** The top of the mark bone's cube, and the top of the core it sits over. */
    private static final double MARK_TOP = 8 / 16.0;
    private static final double CORE_TOP = 4 / 16.0;

    private static final int WHITE = 0xFFFFFFFF;
    /** The variant's colour: red at half, the other two channels untouched. */
    private static final int DYED = 0xFF80FFFF;
    /**
     * The same with the mark variant's blue overlay laid over it at half strength, which eight bits of
     * alpha carry as 128 parts in 255.
     */
    private static final int DYED_AND_MARKED = 0xFF407FFF;
    private static final int ON_FIRE = 0xFFFF0000;
    /** Green laid over white at half, which eight bits of alpha carry as 128 parts in 255. */
    private static final int HURT_GREEN = 0xFF7FFF7F;

    /** Every channel at half, which eight bits carry as 128 parts in 255. */
    private static final int GREY = 0xFF808080;

    /** The top of the sprite geometry's body, which its spark sits on. */
    private static final double BODY_TOP = 8 / 16.0;

    /** What `tp:tailor`'s base and outfit are painted in. */
    private static final int TAILOR_BASE = 0xFF2846C8;
    private static final int TAILOR_OUTFIT = 0xFFC82828;

    private static final int NO_OVERLAY = OverlayTexture.NO_OVERLAY;
    private static final int HURT_RED = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(true));

    private static final int PATIENCE = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            prism(context, world);
            newestDefinition(context, world);
            dimmedLight(context, world);
            hurtRed(context, world);
            hurtColour(context, world);
            faintTexels(context, world);
            maskedTextures(context, world);
            boneMaterials(context, world);
        }
    }

    /**
     * A hurt `tp:sprite` is drawn red for as long as the hit lasts and plain once it is over.
     *
     * Neither of its controllers states an `is_hurt_color`, so the red is the one every Bedrock entity
     * flashes, which Java's entity shaders draw from the overlay a vertex carries.
     */
    private static void hurtRed(ClientGameTestContext context, TestSingleplayerContext world) {
        Entities.discardAll(context, world.getServer(), "tp:sprite");
        world.getServer().runCommand("summon tp:sprite ~3 ~ ~-3 {NoAI:1b}");
        AddonEntity sprite = Entities.waitForClient(context, "tp:sprite");
        Submitted whole = Frames.waitFor(context, sprite, frame -> !frame.geometry().isEmpty(),
            "tp:sprite has a client definition and nothing was drawn for it.");
        Assert.that(overlays(whole, NO_OVERLAY), "an unhurt tp:sprite was drawn with the overlay "
            + overlayOf(whole) + " where none was expected.");

        world.getServer().runCommand("damage @e[type=tp:sprite,limit=1] 1");
        Frames.waitFor(context, sprite, frame -> overlays(frame, HURT_RED),
            last -> "tp:sprite was hurt and was drawn with the overlay " + overlayOf(last) + " rather than Java's red, "
                + HURT_RED + ".");
        for (int tick = 0; tick < PATIENCE; tick++) {
            Hurt seen = context.computeOnClient(client -> new Hurt(sprite.hurtTime, Submitted.entity(sprite)));
            int wanted = seen.hurtTime() > 0 ? HURT_RED : NO_OVERLAY;
            Assert.that(overlays(seen.frame(), wanted), "tp:sprite with " + seen.hurtTime() + " ticks of its hit"
                + " left was drawn with the overlay " + overlayOf(seen.frame()) + " where " + wanted
                + " was expected.");
            if (seen.hurtTime() == 0) return;
            context.waitTick();
        }
        throw new AssertionError("tp:sprite's hit never ran out.");
    }

    /**
     * A hurt `tp:prism` takes its controller's `is_hurt_color` instead of the red: green laid over its
     * white at half, and no overlay, for as long as the hit lasts.
     */
    private static void hurtColour(ClientGameTestContext context, TestSingleplayerContext world) {
        Entities.discardAll(context, world.getServer(), "tp:prism");
        world.getServer().runCommand("summon tp:prism ~-3 ~ ~-3 {NoAI:1b}");
        AddonEntity prism = Entities.waitForClient(context, "tp:prism");
        Frames.waitFor(context, prism, frame -> !frame.geometry().isEmpty() && colours(frame.geometry().get(0), WHITE),
            last -> "an unhurt tp:prism was drawn in " + colourOf(last) + " rather than white.");

        world.getServer().runCommand("damage @e[type=tp:prism,limit=1] 1");
        Submitted hurt = Frames.waitFor(context, prism, frame -> colours(frame.geometry().get(0), HURT_GREEN),
            last -> "tp:prism's is_hurt_color lays green over it at half while it is hurt, which is "
                + Integer.toHexString(HURT_GREEN) + ", and it was drawn in " + colourOf(last) + ".");
        Assert.that(overlays(hurt, NO_OVERLAY), "tp:prism states an is_hurt_color, which replaces the red,"
            + " and it was drawn with the overlay " + overlayOf(hurt) + ".");
        Frames.waitFor(context, prism, frame -> prism.hurtTime == 0 && colours(frame.geometry().get(0), WHITE),
            last -> "tp:prism's hit ran out and it was drawn in " + colourOf(last) + " rather than white again.");
    }

    private record Hurt(int hurtTime, Submitted frame) {
    }

    private static boolean overlays(Submitted frame, int overlay) {
        return !frame.geometry().isEmpty() && frame.geometry().stream()
            .allMatch(piece -> everyVertex(piece, vertex -> vertex.overlay() == overlay));
    }

    private static String colourOf(Submitted frame) {
        return frame.geometry().isEmpty() || frame.geometry().get(0).vertices().isEmpty() ? "nothing"
            : Integer.toHexString(frame.geometry().get(0).vertices().get(0).colour());
    }

    private static String overlayOf(Submitted frame) {
        return frame.geometry().isEmpty() || frame.geometry().get(0).vertices().isEmpty() ? "nothing"
            : String.valueOf(frame.geometry().get(0).vertices().get(0).overlay());
    }

    private static void prism(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:prism ~ ~ ~3 {NoAI:1b}");
        AddonEntity prism = Entities.waitForClient(context, "tp:prism");

        Identifier plain = context.computeOnClient(client -> PackTextures.of("tp:prism", "textures/entity/prism"));
        Identifier dyed = context.computeOnClient(client ->
            PackTextures.of("tp:prism", "textures/entity/prism_dyed"));
        Identifier glow = context.computeOnClient(client ->
            PackTextures.glowOf("tp:prism", "textures/entity/prism"));
        Assert.that(glow != null, "textures/entity/prism has a texture set whose emissive channel glows,"
            + " and no glowing layer was split off it.");

        Submitted first = Frames.waitFor(context, prism, frame -> !frame.geometry().isEmpty(),
            "tp:prism has a client definition and nothing was drawn for it.");
        Assert.that(first.geometry().size() == 2, "tp:prism draws one pass and its glowing layer, and "
            + first.geometry().size() + " pieces of geometry were submitted.");
        Submitted.Geometry body = first.geometry().get(0);
        Assert.that(body.renderType() == Platform.renderType(plain, false, false, false),
            "tp:prism's variant 0 picks Texture.plain under entity_alphatest, and its pass was drawn with "
                + body.renderType() + ".");
        Assert.near(reach(body), ROUND_REACH, TOLERANCE, "the reach of tp:prism's core as Geometry.round");
        Assert.near(body.box().maxY, MARK_TOP, TOLERANCE, "the top of tp:prism with its mark bone drawn");
        Assert.that(everyVertex(body, vertex -> vertex.v() < 0.5f),
            "tp:prism's variant 0 slides its texture by nothing at half its height, and a vertex reached"
                + " the lower half of the sheet.");
        Assert.that(colours(body, WHITE), "tp:prism's colour is white while its variant is 0.");
        Submitted.Geometry lit = first.geometry().get(1);
        Assert.that(lit.renderType() == Platform.renderType(glow, false, false, true),
            "tp:prism's glowing layer was drawn with " + lit.renderType() + " instead of lit and blended.");

        world.getServer().runCommand("bedrock event entity @e[type=tp:prism] tp:prism_square");
        Frames.waitFor(context, prism, frame -> Math.abs(reach(frame.geometry().get(0)) - SQUARE_REACH) < TOLERANCE,
            "tp:prism's tp:facet property was set to square and its controller never picked Geometry.square.");

        world.getServer().runCommand("bedrock event entity @e[type=tp:prism] tp:prism_dye");
        Submitted dyedFrame = Frames.waitFor(context, prism,
            frame -> frame.geometry().get(0).renderType() == Platform.renderType(dyed, false, false, false),
            "tp:prism's variant became 1 and its controller never picked Texture.dyed out of Array.skins.");
        Submitted.Geometry dyedBody = dyedFrame.geometry().get(0);
        Assert.that(everyVertex(dyedBody, vertex -> vertex.v() >= 0.5f),
            "tp:prism's variant 1 slides its texture by half a sheet, and a vertex stayed in the upper half.");
        Assert.that(colours(dyedBody, DYED), "tp:prism's variant 1 halves its red, and it was drawn in "
            + Integer.toHexString(dyedBody.vertices().get(0).colour()) + ".");
        Assert.that(dyedFrame.geometry().size() == 1,
            "textures/entity/prism_dyed has no texture set, and a glowing layer was still drawn over it.");

        world.getServer().runCommand("bedrock event entity @e[type=tp:prism] tp:prism_mark");
        Submitted marked = Frames.waitFor(context, prism,
            frame -> Math.abs(frame.geometry().get(0).box().maxY - CORE_TOP) < TOLERANCE,
            "tp:prism's mark variant became 1 and its part_visibility never hid the mark bone.");
        Assert.that(colours(marked.geometry().get(0), DYED_AND_MARKED),
            "tp:prism's overlay_color lays blue over it at half while its mark variant is 1, and it was drawn in "
                + Integer.toHexString(marked.geometry().get(0).vertices().get(0).colour()) + ".");

        world.getServer().runCommand("bedrock event entity @e[type=tp:prism] tp:prism_glaze");
        Frames.waitFor(context, prism,
            frame -> frame.geometry().get(0).renderType() == Platform.renderType(dyed, true, false, false),
            "tp:prism's tp:prism_glassy group picks Material.glass, entity_alphablend, and its pass was never"
                + " drawn blended.");

        world.getServer().runCommand("data merge entity @e[type=tp:prism,limit=1] {Fire:400s}");
        Frames.waitFor(context, prism, frame -> colours(frame.geometry().get(0), ON_FIRE),
            "tp:prism was set on fire and its on_fire_color never painted it red.");

        world.getServer().runCommand("bedrock event entity @e[type=tp:prism] tp:prism_shrink");
        Frames.waitFor(context, prism, frame -> Math.abs(reach(frame.geometry().get(0)) - YOUNG_REACH) < TOLERANCE,
            "tp:prism was made young and its controller never picked Geometry.young off q.is_baby.");
    }

    /**
     * `tp:sprite` is described twice, and the definition for the newer engine is the one drawn.
     *
     * The older one names a geometry the pack has not got and no cap, so drawing it would leave one
     * pass of nothing; the newer one draws the young body and a cap over it.
     */
    private static void newestDefinition(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:sprite ~3 ~ ~3 {NoAI:1b}");
        AddonEntity sprite = Entities.waitForClient(context, "tp:sprite");
        String engine = context.computeOnClient(client ->
            AddonClientAssets.of("tp:sprite").definition().minEngineVersion().toString());
        Assert.that(engine.startsWith("1.16"), "tp:sprite is described for 1.8.0 and for 1.16.0 in a pack"
            + " written for 1.20.0, and the definition kept was the one for " + engine + ".");

        Submitted frame = Frames.waitFor(context, sprite, drawn -> drawn.geometry().size() == 2,
            "tp:sprite's 1.16.0 definition draws its body and its cap, and two passes were never submitted.");
        Assert.that(frame.box() != null, "tp:sprite's two passes carried no vertices.");
    }

    /**
     * `tp:ember`'s controller multiplies the light reaching it by 0.35, on both channels.
     *
     * The world is lit by the sky, so the light around the ember is not nothing and the vertices have
     * to carry a little over a third of it.
     */
    private static void dimmedLight(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:ember ~-3 ~ ~3 {NoAI:1b}");
        AddonEntity ember = Entities.waitForClient(context, "tp:ember");
        Submitted frame = Frames.waitFor(context, ember, drawn -> !drawn.geometry().isEmpty() && drawn.light() != 0,
            "tp:ember stands under the sky and was never drawn with any light around it.");

        int around = frame.light();
        int dimmed = dim(around >>> 16) << 16 | dim(around & 0xFFFF);
        Assert.that(dimmed != around, "the light around tp:ember is " + Integer.toHexString(around)
            + ", which a multiplier of 0.35 does not change, so it says nothing.");
        int drawn = frame.geometry().get(0).vertices().get(0).light();
        Assert.that(drawn == dimmed, "tp:ember stands in light " + Integer.toHexString(around)
            + " and its controller's light_color_multiplier of 0.35 asks for " + Integer.toHexString(dimmed)
            + ", and it was drawn in " + Integer.toHexString(drawn) + ".");
    }

    /**
     * A texel the pack left nearly clear is drawn by a material that reads alpha as a tint and
     * dropped by one that only tests it.
     *
     * `tp:sprite` and `tp:shade` draw the same texture, whose top four rows are written at an alpha
     * of three. The sprite's `entity_alphatest` drops everything under half, so its pass has to bind
     * a texture in which that texel is clear. The shade's `entity_alphatest_change_color` reads the
     * alpha as how much to tint and drops only what is fully clear, so its pass has to bind one in
     * which the texel is whole.
     */
    private static void faintTexels(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:sprite ~-3 ~ ~-3 {NoAI:1b}");
        world.getServer().runCommand("summon tp:shade ~ ~1 ~-3");
        AddonEntity sprite = Entities.waitForClient(context, "tp:sprite");
        AddonEntity shade = Entities.waitForClient(context, "tp:shade");

        int tested = context.computeOnClient(client ->
            PackTextures.alphaAt(ScriptClock.of(sprite).passes().get(0).texture(), 0, 0));
        Assert.that(tested == 0, "tp:sprite draws with entity_alphatest, which drops a texel at an alpha of three,"
            + " and its pass bound a texture holding that texel at " + tested + ".");
        int tinted = context.computeOnClient(client ->
            PackTextures.alphaAt(ScriptClock.of(shade).passes().get(0).texture(), 0, 0));
        Assert.that(tinted == 0xFF, "tp:shade draws with entity_alphatest_change_color, which keeps a texel at an"
            + " alpha of three, and its pass bound a texture holding that texel at " + tinted + ".");
    }

    /**
     * A material defining `MASKED_MULTITEXTURE` draws its pass's second texture over its first, the
     * second's clear but coloured texels showing the first through.
     *
     * `tp:tailor` draws the same two textures through `tp_tailored`, a material of the pack's own
     * that defines it, through the game's `villager_v2_masked`, and through `entity_alphatest`, which
     * defines nothing of the kind and so binds the first texture alone; its fourth controller draws
     * one pass of each kind. The base is blue all over and the outfit's four bands of sixteen columns
     * are an opaque red, a clear white, a clear black and a faint green, which a masked pass draws as
     * red, blue, nothing and blue.
     */
    private static void maskedTextures(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:tailor ~3 ~ ~-3");
        AddonEntity tailor = Entities.waitForClient(context, "tp:tailor");

        List<List<String>> drawn = context.computeOnClient(client -> ScriptClock.of(tailor).passes().stream()
            .map(pass -> IntStream.of(8, 24, 40, 56)
                .mapToObj(x -> texel(PackTextures.colourAt(pass.texture(), x, 8))).toList())
            .toList());
        List<String> masked = List.of(texel(TAILOR_OUTFIT), texel(TAILOR_BASE), texel(0), texel(TAILOR_BASE));
        List<String> plain = Collections.nCopies(4, texel(TAILOR_BASE));
        Assert.that(drawn.equals(List.of(masked, masked, plain, plain, masked)), "tp:tailor's five passes should"
            + " draw the outfit's bands as " + masked + " or as " + plain + ", and drew " + drawn + ".");
    }

    /**
     * A controller's `materials` list gives each bone the material of the last entry naming it, and
     * each material draws its own bones in a pass of its own.
     *
     * `tp:tailor`'s fourth controller draws every bone with `entity_alphatest` and the bones matching
     * `*ARK` with `villager_v2_masked`, under a grey `color`. The sprite geometry's spark is the one bone
     * the second entry reaches, a star at the front and the capitals notwithstanding, so the plain pass
     * draws the body, up to half a block, and the masked one draws the spark sitting on top of it.
     * Bedrock's masked multitexture shader reads no controller colour, so only the plain pass is grey.
     */
    private static void boneMaterials(ClientGameTestContext context, TestSingleplayerContext world) {
        AddonEntity tailor = Entities.waitForClient(context, "tp:tailor");
        Submitted frame = Frames.waitFor(context, tailor, drawn -> drawn.geometry().size() == 5,
            last -> "tp:tailor's four controllers draw five passes, the last one split by bone, and "
                + last.geometry().size() + " were submitted.");
        Submitted.Geometry body = frame.geometry().get(3);
        Submitted.Geometry spark = frame.geometry().get(4);
        Assert.that(!body.vertices().isEmpty() && everyVertex(body, vertex -> vertex.y() <= BODY_TOP + TOLERANCE),
            "tp:tailor's plain pass should draw the body alone, under " + BODY_TOP + ", and reached " + body.box()
                + ".");
        Assert.that(!spark.vertices().isEmpty() && everyVertex(spark, vertex -> vertex.y() >= BODY_TOP - TOLERANCE),
            "tp:tailor's masked pass should draw the spark alone, over " + BODY_TOP + ", and reached " + spark.box()
                + ".");
        Assert.that(colours(body, GREY), "tp:tailor's plain pass takes its controller's grey, and was drawn in "
            + Integer.toHexString(body.vertices().get(0).colour()) + ".");
        Assert.that(colours(spark, WHITE), "tp:tailor's masked pass takes no controller colour, and was drawn in "
            + Integer.toHexString(spark.vertices().get(0).colour()) + ".");
    }

    /** A texel as a reader compares it: its colour, or `clear` where nothing of it is drawn. */
    private static String texel(int argb) {
        return argb >>> 24 == 0 ? "clear" : Integer.toHexString(argb);
    }

    private static int dim(int channel) {
        return Math.min(0xF0, Math.round(channel * 0.35f));
    }

    /** How far the geometry reaches from the vertical line through the entity's feet. */
    private static double reach(Submitted.Geometry geometry) {
        return geometry.vertices().stream()
            .mapToDouble(vertex -> Math.sqrt(vertex.x() * vertex.x() + vertex.z() * vertex.z()))
            .max().orElse(0);
    }

    private static boolean colours(Submitted.Geometry geometry, int colour) {
        return everyVertex(geometry, vertex -> vertex.colour() == colour);
    }

    private static boolean everyVertex(Submitted.Geometry geometry, Predicate<Submitted.Vertex> holds) {
        return !geometry.vertices().isEmpty() && geometry.vertices().stream().allMatch(holds);
    }
}
