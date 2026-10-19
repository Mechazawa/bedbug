package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * An add-on's items and blocks are drawn from the pack's own pictures, and its glyphs reach the font.
 *
 * - `tp:wand` draws its icon, and `tp:shard`, which is dyeable, is tinted the pack's default colour.
 * - `tp:ember_spawn_egg`, whose entity states only a base and an overlay colour, draws this mod's
 *   own egg painted in the two.
 * - `tp:lamp`'s item is named by the pack's `tile.tp:lamp.name`, the name of the block it places.
 * - `tp:lamp`'s geometry is written centred on the block the way Bedrock writes it, a post four
 *   pixels across under a shade eight across, so the model reaches from four to twelve pixels.
 * - `tp:totem` names `*` and `north` and no geometry, and breaks into `lamp_off`, the texture of
 *   its `down` face, which `*` gives it. `tp:lamp` breaks into the `lamp_on` its
 *   `minecraft:destruction_particles` names.
 * - `tp:lamp` draws `lamp_off`, and `lamp_on` on the face its geometry names `glow`, until its
 *   `tp:lit` state is true, where a permutation's material instances name `lamp_on` for every face
 *   and the light it gives becomes 15.
 * - `tp:lamp`'s culling shape is a full slab twelve pixels tall with a smaller block on top, so the
 *   face of a block under it is hidden and the faces of blocks above and beside it are not.
 * - `font/glyph_E1.png` paints each glyph as wide as its column, so U+E103 is three pixels wider
 *   than U+E100.
 */
public final class ItemAndBlockClientGameTest implements FabricClientGameTest {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final int SHARD_DYE = 0x4C8FD1;
    private static final int LAMP_LIGHT = 15;
    private static final int EMBER_BASE = 0xD94F1E;
    private static final int EMBER_OVERLAY = 0xFFD060;
    private static final float SHADE_NEAR = 4 / 16f;
    private static final float SHADE_FAR = 12 / 16f;
    private static final float CLOSE = 1e-4f;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            items(context);
            spawnEgg(context);
            blockItemName(context);
            lampModels(context);
            lampGeometry(context);
            particles(context);
            lampCulling(context);
            lampLight(context, world);
            glyphs(context);
        }
    }

    private static void items(ClientGameTestContext context) {
        Submitted.Item wand = context.computeOnClient(client -> drawnIcon("tp:wand"));
        Set<String> sprites = spriteNames(wand.quads());
        Assert.that(!sprites.isEmpty() && !sprites.contains(MissingTextureAtlasSprite.getLocation().toString()),
            "tp:wand's icon is drawn from " + sprites + " instead of its pack's picture.");

        Submitted.Item shard = context.computeOnClient(client -> drawnIcon("tp:shard"));
        Assert.that(shard.tints().length > 0 && (shard.tints()[0] & 0xFFFFFF) == SHARD_DYE,
            "tp:shard is dyeable with a default of #4C8FD1, and its icon was tinted "
                + (shard.tints().length == 0 ? "nothing" : Integer.toHexString(shard.tints()[0])) + ".");
    }

    private static void spawnEgg(ClientGameTestContext context) {
        Submitted.Item egg = context.computeOnClient(client -> drawnIcon("tp:ember_spawn_egg"));
        Set<String> sprites = spriteNames(egg.quads());
        Assert.that(!sprites.isEmpty() && !sprites.contains(MissingTextureAtlasSprite.getLocation().toString()),
            "tp:ember_spawn_egg is drawn from " + sprites + " instead of a spawn egg.");
        Assert.that(egg.tints().length == 2 && (egg.tints()[0] & 0xFFFFFF) == EMBER_BASE
                && (egg.tints()[1] & 0xFFFFFF) == EMBER_OVERLAY,
            "tp:ember_spawn_egg is painted #D94F1E under #FFD060, and its tints were "
                + Arrays.toString(egg.tints()) + ".");
    }

    private static void blockItemName(ClientGameTestContext context) {
        String name = context.computeOnClient(client -> new ItemStack(
            BuiltInRegistries.ITEM.getValue(Platform.id("tp", "lamp"))).getHoverName().getString());
        Assert.that(name.equals("Test Lamp"),
            "tp:lamp's item is named after its block, Test Lamp, and it is named " + name + ".");
    }

    private static void lampGeometry(ClientGameTestContext context) {
        List<BakedQuad> quads = context.computeOnClient(client -> quads(lamp(false)));
        List<Float> xs = quads.stream().flatMap(quad -> corners(quad).map(Vector3fc::x)).toList();
        List<Float> zs = quads.stream().flatMap(quad -> corners(quad).map(Vector3fc::z)).toList();
        Assert.that(spans(xs) && spans(zs),
            "tp:lamp's model reaches from 0.25 to 0.75 of the block across, and it reaches x "
                + Collections.min(xs) + " to " + Collections.max(xs) + ", z " + Collections.min(zs) + " to "
                + Collections.max(zs) + ".");
    }

    private static Stream<Vector3fc> corners(BakedQuad quad) {
        return IntStream.range(0, BakedQuad.VERTEX_COUNT).mapToObj(quad::position);
    }

    private static boolean spans(List<Float> values) {
        return Math.abs(Collections.min(values) - SHADE_NEAR) < CLOSE
            && Math.abs(Collections.max(values) - SHADE_FAR) < CLOSE;
    }

    private static void particles(ClientGameTestContext context) {
        String totem = context.computeOnClient(client -> particle(
            BuiltInRegistries.BLOCK.getValue(Platform.id("tp", "totem")).defaultBlockState()));
        Assert.that(totem.contains("lamp_off"),
            "tp:totem breaks into lamp_off, the texture its `*` gives its down face, and it breaks into "
                + totem + ".");
        String lamp = context.computeOnClient(client -> particle(lamp(false)));
        Assert.that(lamp.contains("lamp_on"),
            "tp:lamp breaks into lamp_on, the texture its minecraft:destruction_particles names, and it breaks into "
                + lamp + ".");
    }

    private static String particle(BlockState state) {
        return Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state)
            .particleMaterial().sprite().contents().name().toString();
    }

    private static void lampModels(ClientGameTestContext context) {
        Set<String> unlit = context.computeOnClient(client -> spriteNames(quads(lamp(false))));
        Set<String> lit = context.computeOnClient(client -> spriteNames(quads(lamp(true))));
        Assert.that(unlit.stream().anyMatch(name -> name.contains("lamp_off"))
                && unlit.stream().allMatch(name -> name.contains("lamp_off") || name.contains("lamp_on")),
            "tp:lamp is drawn from lamp_off, with lamp_on on its glow face, until it is lit, and its model was"
                + " drawn from " + unlit + ".");
        Assert.that(lit.stream().allMatch(name -> name.contains("lamp_on")) && !lit.isEmpty(),
            "tp:lamp's lit permutation draws lamp_on, and its model was drawn from " + lit + ".");

        int unlitLight = context.computeOnClient(client -> lamp(false).getLightEmission());
        int litLight = context.computeOnClient(client -> lamp(true).getLightEmission());
        Assert.that(unlitLight == 0 && litLight == LAMP_LIGHT, "tp:lamp gives no light unlit and 15 lit, and it gave "
            + unlitLight + " and " + litLight + ".");
    }

    private static void lampCulling(ClientGameTestContext context) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        boolean below = context.computeOnClient(client -> Block.shouldRenderFace(stone, lamp(false), Direction.UP));
        boolean above = context.computeOnClient(client -> Block.shouldRenderFace(stone, lamp(false), Direction.DOWN));
        boolean beside = context.computeOnClient(client ->
            Block.shouldRenderFace(stone, lamp(false), Direction.NORTH));
        Assert.that(!below, "tp:lamp's culling shape covers the whole of its bottom, and the top of the stone"
            + " under it was still drawn.");
        Assert.that(above, "tp:lamp's culling shape covers only the middle of its top, and the bottom of the stone"
            + " over it was hidden.");
        Assert.that(beside, "tp:lamp's culling shape stops four pixels short of its top, and the face of the stone"
            + " beside it was hidden.");
    }

    private static void lampLight(ClientGameTestContext context, TestSingleplayerContext world) {
        BlockPos lamp = context.computeOnClient(client -> client.player.blockPosition().offset(3, 0, 3));
        world.getServer().runCommand("setblock " + lamp.getX() + " " + lamp.getY() + " " + lamp.getZ() + " tp:lamp");
        Worlds.waitForClient(context, client -> client.level.getBrightness(LightLayer.BLOCK, lamp.above())
                == LAMP_LIGHT - 1, 100,
            () -> "tp:lamp lights itself and gives 15, and the block over it is lit at "
                + Minecraft.getInstance().level.getBrightness(LightLayer.BLOCK, lamp.above()) + ".");
    }

    private static void glyphs(ClientGameTestContext context) {
        int narrow = context.computeOnClient(client -> client.font.width(""));
        int wide = context.computeOnClient(client -> client.font.width(""));
        Assert.that(wide - narrow == 3, "U+E103 is painted three pixels wider than U+E100 on tp's glyph page, and"
            + " the font draws them " + wide + " and " + narrow + " wide.");
    }

    /** What an item's icon hands the frame, drawn the way the inventory draws it. */
    private static Submitted.Item drawnIcon(String identifier) {
        Minecraft client = Minecraft.getInstance();
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Platform.id(
            identifier.substring(0, identifier.indexOf(':')), identifier.substring(identifier.indexOf(':') + 1))));
        ItemStackRenderState state = new ItemStackRenderState();
        client.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, client.level, null, 0);
        Submitted submitted = new Submitted();
        state.submit(new PoseStack(), submitted.collector(), FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
        Assert.that(!submitted.items().isEmpty(), identifier + " drew no item at all.");
        return submitted.items().get(0);
    }

    @SuppressWarnings("unchecked")
    private static BlockState lamp(boolean lit) {
        BlockState state = BuiltInRegistries.BLOCK.getValue(Platform.id("tp", "lamp")).defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            if (property.getName().endsWith("lit")) {
                state = state.setValue((Property<Boolean>) property, lit);
            }
        }
        return state;
    }

    private static List<BakedQuad> quads(BlockState state) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(0), parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            quads.addAll(part.getQuads(null));
            for (Direction direction : Direction.values()) quads.addAll(part.getQuads(direction));
        }
        return quads;
    }

    private static Set<String> spriteNames(List<BakedQuad> quads) {
        return quads.stream().map(quad -> quad.materialInfo().sprite().contents().name().toString())
            .collect(Collectors.toSet());
    }
}
