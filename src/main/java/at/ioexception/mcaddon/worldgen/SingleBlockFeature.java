package at.ioexception.mcaddon.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bedrock's `minecraft:single_block_feature`: one block, picked by weight, placed only where the
 * block there and the blocks beside it are ones the feature allows.
 *
 * Java folds a block's placement rules and its survival rules into one question, `canSurvive`, so
 * either of Bedrock's two `enforce_*` flags asks it. With neither, the block is placed whether it
 * could stay there or not, as Bedrock places it.
 */
//? if >=26.3 {
/*public record SingleBlockFeature(List<Choice> places, Optional<List<StateMatch>> mayReplace,
    Optional<Attachment> mayAttachTo, Map<Direction, List<StateMatch>> mayNotAttachTo,
    boolean enforceRules, boolean randomizeRotation)
    implements net.minecraft.world.level.levelgen.feature.Feature {
*///?} else {
public record SingleBlockFeature(List<Choice> places, Optional<List<StateMatch>> mayReplace,
    Optional<Attachment> mayAttachTo, Map<Direction, List<StateMatch>> mayNotAttachTo,
    boolean enforceRules, boolean randomizeRotation)
    implements BedrockFeatures.Config {
//?}
    private static final Codec<Map<Direction, List<StateMatch>>> FACES =
        Codec.unboundedMap(Direction.CODEC, StateMatch.CODEC.listOf());

    public static final MapCodec<SingleBlockFeature> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Choice.CODEC.listOf().fieldOf("places").forGetter(SingleBlockFeature::places),
            StateMatch.CODEC.listOf().optionalFieldOf("may_replace").forGetter(SingleBlockFeature::mayReplace),
            Attachment.CODEC.optionalFieldOf("may_attach_to").forGetter(SingleBlockFeature::mayAttachTo),
            FACES.optionalFieldOf("may_not_attach_to", Map.of()).forGetter(SingleBlockFeature::mayNotAttachTo),
            Codec.BOOL.optionalFieldOf("enforce_rules", false).forGetter(SingleBlockFeature::enforceRules),
            Codec.BOOL.optionalFieldOf("randomize_rotation", false).forGetter(SingleBlockFeature::randomizeRotation))
        .apply(instance, SingleBlockFeature::new));

    public MapCodec<SingleBlockFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        BlockState state = pick(random);
        if (state == null) return false;
        if (randomizeRotation) state = state.rotate(Rotation.getRandom(random));

        BlockState there = level.getBlockState(origin);
        if (mayReplace.isPresent() && mayReplace.get().stream().noneMatch(match -> match.matches(there))) {
            return false;
        }
        boolean shunned = mayNotAttachTo.entrySet().stream().anyMatch(face -> face.getValue().stream()
            .anyMatch(match -> match.matches(level.getBlockState(origin.relative(face.getKey())))));
        if (shunned) return false;
        if (mayAttachTo.isPresent() && !mayAttachTo.get().holds(level, origin)) return false;
        if (enforceRules && !state.canSurvive(level, origin)) return false;

        return level.setBlock(origin, state, Block.UPDATE_CLIENTS);
    }

    private BlockState pick(RandomSource random) {
        int total = places.stream().mapToInt(Choice::weight).sum();
        if (total <= 0) return null;

        int roll = random.nextInt(total);
        for (Choice choice : places) {
            roll -= choice.weight();
            if (roll < 0) return choice.state();
        }
        return null;
    }

    public record Choice(BlockState state, int weight) {
        static final Codec<Choice> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockState.CODEC.fieldOf("state").forGetter(Choice::state),
                Codec.INT.optionalFieldOf("weight", 1).forGetter(Choice::weight))
            .apply(instance, Choice::new));
    }

    /** A block a pack named, and the states it named beside it; any other state of the block matches. */
    public record StateMatch(Block block, Map<String, String> properties) {
        static final Codec<StateMatch> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(StateMatch::block),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("properties", Map.of())
                    .forGetter(StateMatch::properties))
            .apply(instance, StateMatch::new));

        boolean matches(BlockState state) {
            return state.is(block) && properties.entrySet().stream().allMatch(named -> {
                Property<?> property = block.getStateDefinition().getProperty(named.getKey());
                return property != null && nameOf(state, property).equals(named.getValue());
            });
        }

        private static <T extends Comparable<T>> String nameOf(BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }
    }

    /**
     * The neighbours the block has to rest against. The block above and below must match where they
     * are listed, and of the four sides at least `min_sides_must_attach` of those listed must, each
     * read as a side relative to the others when `auto_rotate` is on.
     */
    public record Attachment(Map<Direction, List<StateMatch>> faces, int minSides, boolean autoRotate) {
        static final Codec<Attachment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                FACES.fieldOf("faces").forGetter(Attachment::faces),
                Codec.INT.optionalFieldOf("min_sides_must_attach", 1).forGetter(Attachment::minSides),
                Codec.BOOL.optionalFieldOf("auto_rotate", true).forGetter(Attachment::autoRotate))
            .apply(instance, Attachment::new));

        boolean holds(WorldGenLevel level, BlockPos origin) {
            for (Direction vertical : List.of(Direction.UP, Direction.DOWN)) {
                List<StateMatch> allowed = faces.get(vertical);
                if (allowed != null && !touches(level, origin, vertical, allowed)) return false;
            }

            List<Direction> sides = Direction.Plane.HORIZONTAL.stream().filter(faces::containsKey).toList();
            int needed = Math.min(minSides, sides.size());
            if (needed <= 0) return true;

            for (int turns = 0; turns < (autoRotate ? 4 : 1); turns++) {
                int quarterTurns = turns;
                long touching = sides.stream().filter(side -> touches(level, origin,
                    turned(side, quarterTurns), faces.get(side))).count();
                if (touching >= needed) return true;
            }
            return false;
        }

        private static Direction turned(Direction side, int quarterTurns) {
            Direction turned = side;
            for (int turn = 0; turn < quarterTurns; turn++) turned = turned.getClockWise();
            return turned;
        }

        private static boolean touches(WorldGenLevel level, BlockPos origin, Direction face, List<StateMatch> allowed) {
            BlockState neighbour = level.getBlockState(origin.relative(face));
            return allowed.stream().anyMatch(match -> match.matches(neighbour));
        }
    }
}
