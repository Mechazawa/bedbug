package at.ioexception.mcaddon.block;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.client.render.BorderDust;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.List;

/**
 * Bedrock's `minecraft:border_block`, which Java has no block for.
 *
 * It is a wall with Bedrock's wall states that nothing breaks, no piston moves and nothing drops.
 * What it walls off is the whole column above and below it, which {@link BorderColumns} holds every
 * moving entity out of, and it sends red dust up and down that column while the Bedrock game rule
 * `showBorderEffect` is on.
 */
public final class BorderBlock extends WallBlock {
    public static final Identifier ID = McAddon.id("border_block");

    private BorderBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** Registers the block and its item, before any pack is read, so a pack naming it finds it. */
    public static void register() {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_RED)
            .forceSolidOn()
            .strength(-1.0F, 3600000.0F)
            .noLootTable()
            .pushReaction(Platform.IMMOVABLE)
            .sound(SoundType.STONE);
        Block block = Registry.register(BuiltInRegistries.BLOCK, ID, new BorderBlock(Platform.named(properties, ID)));
        Item item = Registry.register(BuiltInRegistries.ITEM, ID,
            Platform.buildBlockItem(block, new Item.Properties(), ID));
        Platform.addToCreativeTab(CreativeModeTabs.OP_BLOCKS, () -> List.of(item));
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        BorderDust.emit(pos, random);
    }
}
