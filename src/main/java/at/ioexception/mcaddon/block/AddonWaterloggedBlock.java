package at.ioexception.mcaddon.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * A pack's block that holds water, which is what `minecraft:liquid_detection` asks for.
 *
 * Java carries the water in a state of the block itself, so a block that can contain a liquid gets
 * Java's own `waterlogged` property alongside the states the pack declared.
 */
public class AddonWaterloggedBlock extends AddonBlock implements SimpleWaterloggedBlock {
    public AddonWaterloggedBlock(BlockBehaviour.Properties settings) {
        super(settings);
        registerDefaultState(defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, false));
    }

    /** The pack's own states, with Java's waterlogged flag added to them. */
    public static AddonWaterloggedBlock build(BlockBehaviour.Properties properties, Identifier id,
        List<Property<?>> states) {
        List<Property<?>> all = new ArrayList<>(states);
        all.add(BlockStateProperties.WATERLOGGED);

        //? if >=1.21.2 {
        properties = properties.setId(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.BLOCK, id));
        //?}
        return AddonBlock.build(properties, all, AddonWaterloggedBlock::new);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED)
            ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,
            context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
    }

    // The neighbour update carried the level and the direction until 1.21.2 and carries the tick
    // schedule itself from there on.
    //? if >=1.21.2 {
    @Override
    public BlockState updateShape(BlockState state, LevelReader level,
        net.minecraft.world.level.ScheduledTickAccess ticks, BlockPos position, Direction direction,
        BlockPos neighbour, BlockState neighbourState, RandomSource random) {
        if (state.getValue(BlockStateProperties.WATERLOGGED)) {
            ticks.scheduleTick(position, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, level, ticks, position, direction, neighbour, neighbourState,
            random);
    }
    //?} else {
    /*@Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState,
        net.minecraft.world.level.LevelAccessor level, BlockPos position, BlockPos neighbour) {
        if (state.getValue(BlockStateProperties.WATERLOGGED)) {
            level.scheduleTick(position, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighbourState, level, position, neighbour);
    }
    *///?}
}
