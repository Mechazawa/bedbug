package at.ioexception.mcaddon.worldgen;

import at.ioexception.mcaddon.bedrock.behaviour.Scatter;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * A feature rule's `distribution`, as the placement modifier of the placed feature the rule becomes.
 *
 * Bedrock hands a rule the corner of the chunk at height nothing, so a rule's heights are heights in
 * the world. Java hands a placed feature the corner of the chunk at the bottom of the world, which is
 * put back to nothing before the scatter reads it.
 */
//? if >=26.3 {
/*public final class ScatterPlacement implements PlacementModifier {
*///?} else {
public final class ScatterPlacement extends PlacementModifier {
//?}
    public static final MapCodec<ScatterPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Scatter.CODEC.fieldOf("distribution").forGetter(ScatterPlacement::distribution))
        .apply(instance, ScatterPlacement::new));

    private final Scatter distribution;

    private ScatterPlacement(Scatter distribution) {
        this.distribution = distribution;
    }

    private Scatter distribution() {
        return distribution;
    }

    //? if >=26.3 {
    /*@Override
    public void modify(PlacementContext context, RandomSource random, BlockPos origin,
        java.util.function.Consumer<BlockPos> placed) {
        distribution.positions(context.getLevel(), random, origin.atY(0)).forEach(placed);
    }

    @Override
    public MapCodec<ScatterPlacement> codec() {
        return CODEC;
    }
    *///?} else {
    public static final net.minecraft.world.level.levelgen.placement.PlacementModifierType<ScatterPlacement> TYPE =
        () -> CODEC;

    @Override
    public java.util.stream.Stream<BlockPos> getPositions(PlacementContext context, RandomSource random,
        BlockPos origin) {
        return distribution.positions(context.getLevel(), random, origin.atY(0)).stream();
    }

    @Override
    public net.minecraft.world.level.levelgen.placement.PlacementModifierType<?> type() {
        return TYPE;
    }
    //?}
}
