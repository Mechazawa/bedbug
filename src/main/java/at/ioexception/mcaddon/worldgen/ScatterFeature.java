package at.ioexception.mcaddon.worldgen;

import at.ioexception.mcaddon.bedrock.behaviour.Scatter;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Bedrock's `minecraft:scatter_feature`: the feature it names, placed at every position its scatter
 * reaches from the one it is given. It succeeds when one of those placements does.
 */
//? if >=26.3 {
/*public record ScatterFeature(Scatter distribution, Holder<PlacedFeature> placesFeature)
    implements net.minecraft.world.level.levelgen.feature.Feature {
*///?} else {
public record ScatterFeature(Scatter distribution, Holder<PlacedFeature> placesFeature)
    implements BedrockFeatures.Config {
//?}
    public static final MapCodec<ScatterFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Scatter.CODEC.fieldOf("distribution").forGetter(ScatterFeature::distribution),
            PlacedFeature.CODEC.fieldOf("places_feature").forGetter(ScatterFeature::placesFeature))
        .apply(instance, ScatterFeature::new));

    public MapCodec<ScatterFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        boolean placed = false;
        for (BlockPos at : distribution.positions(level, random, origin)) {
            placed |= placesFeature.value().place(level, generator, random, at);
        }
        return placed;
    }
}
