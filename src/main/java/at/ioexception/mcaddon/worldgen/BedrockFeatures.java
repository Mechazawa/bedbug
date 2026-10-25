package at.ioexception.mcaddon.worldgen;

import at.ioexception.mcaddon.McAddon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * The feature types and the placement modifier this mod adds for the Bedrock features Java has no
 * type for.
 *
 * Each feature is a record of its own fields that places itself. From 26.3 that record is a Java
 * feature; before it, it is the configuration of `Placing`, the one feature type that hands its
 * placement to its configuration.
 */
public final class BedrockFeatures {
    public static final String SCATTER = McAddon.MOD_ID + ":scatter";
    public static final String AGGREGATE = McAddon.MOD_ID + ":aggregate";
    public static final String SINGLE_BLOCK = McAddon.MOD_ID + ":single_block";

    private BedrockFeatures() {
    }

    public static void register() {
        //? if >=26.3 {
        /*Registry.register(BuiltInRegistries.FEATURE_TYPE, McAddon.id("scatter"), ScatterFeature.CODEC);
        Registry.register(BuiltInRegistries.FEATURE_TYPE, McAddon.id("aggregate"), AggregateFeature.CODEC);
        Registry.register(BuiltInRegistries.FEATURE_TYPE, McAddon.id("single_block"), SingleBlockFeature.CODEC);
        Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, McAddon.id("scatter"), ScatterPlacement.CODEC);
        *///?} else {
        Registry.register(BuiltInRegistries.FEATURE, McAddon.id("scatter"),
            new Placing<>(ScatterFeature.CODEC.codec()));
        Registry.register(BuiltInRegistries.FEATURE, McAddon.id("aggregate"),
            new Placing<>(AggregateFeature.CODEC.codec()));
        Registry.register(BuiltInRegistries.FEATURE, McAddon.id("single_block"),
            new Placing<>(SingleBlockFeature.CODEC.codec()));
        Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, McAddon.id("scatter"), ScatterPlacement.TYPE);
        //?}
    }

    //? if <26.3 {
    /** A configuration that places itself, which is what each of this mod's features is. */
    public interface Config extends net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration {
        boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin);
    }

    private static final class Placing<C extends Config>
        extends net.minecraft.world.level.levelgen.feature.Feature<C> {
        Placing(com.mojang.serialization.Codec<C> codec) {
            super(codec);
        }

        @Override
        public boolean place(net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<C> context) {
            return context.config().place(context.level(), context.chunkGenerator(), context.random(),
                context.origin());
        }
    }
    //?}
}
