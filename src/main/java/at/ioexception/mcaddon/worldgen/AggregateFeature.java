package at.ioexception.mcaddon.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.List;

/**
 * Bedrock's `minecraft:aggregate_feature`: every feature it lists, in order and at the one position
 * it is given, until `early_out` stops it at the first placement that fails or the first that
 * succeeds. It succeeds when one of them does.
 */
//? if >=26.3 {
/*public record AggregateFeature(List<Holder<PlacedFeature>> features, String earlyOut)
    implements net.minecraft.world.level.levelgen.feature.Feature {
*///?} else {
public record AggregateFeature(List<Holder<PlacedFeature>> features, String earlyOut)
    implements BedrockFeatures.Config {
//?}
    public static final MapCodec<AggregateFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.list(PlacedFeature.CODEC).fieldOf("features").forGetter(AggregateFeature::features),
            Codec.STRING.optionalFieldOf("early_out", "none").forGetter(AggregateFeature::earlyOut))
        .apply(instance, AggregateFeature::new));

    public MapCodec<AggregateFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        boolean anyPlaced = false;
        for (Holder<PlacedFeature> feature : features) {
            boolean placed = feature.value().place(level, generator, random, origin);
            anyPlaced |= placed;
            if (placed ? earlyOut.equals("first_success") : earlyOut.equals("first_failure")) break;
        }
        return anyPlaced;
    }
}
