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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An add-on's items and blocks are drawn from the pack's own pictures, and its glyphs reach the font.
 *
 * - `tp:wand` draws its icon, and `tp:shard`, which is dyeable, is tinted the pack's default colour.
 * - `tp:lamp` draws `lamp_off` until its `tp:lit` state is true, where a permutation swaps the
 *   texture for `lamp_on` and the light it gives for 15.
 * - `tp:lamp`'s culling shape is a full slab twelve pixels tall with a smaller block on top, so the
 *   face of a block under it is hidden and the faces of blocks above and beside it are not.
 * - `font/glyph_E1.png` paints each glyph as wide as its column, so U+E103 is three pixels wider
 *   than U+E100.
 */
public final class ItemAndBlockClientGameTest implements FabricClientGameTest {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final int SHARD_DYE = 0x4C8FD1;
    private static final int LAMP_LIGHT = 15;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            items(context);
            lampModels(context);
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

    private static void lampModels(ClientGameTestContext context) {
        Set<String> unlit = context.computeOnClient(client -> spriteNames(quads(lamp(false))));
        Set<String> lit = context.computeOnClient(client -> spriteNames(quads(lamp(true))));
        Assert.that(unlit.stream().allMatch(name -> name.contains("lamp_off")) && !unlit.isEmpty(),
            "tp:lamp is drawn from lamp_off until it is lit, and its model was drawn from " + unlit + ".");
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
