package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Walks to the nearest block that answers a question, which is what several of Bedrock's goals are.
 *
 * Java's own goal already searches the ground around a mob and paths to what it finds; all that is
 * left is deciding what counts, and what to do on arrival. The searching happens on Java's own
 * clock, so an entity carrying one costs no more than a vanilla mob does.
 */
public final class MoveToMatchingBlockGoal extends MoveToBlockGoal {
    private final Predicate<BlockState> wanted;
    private final BiConsumer<ServerLevel, BlockPos> arrival;

    /** What a pack's controller reads this walk back as, or null where Bedrock publishes nothing. */
    private final String flag;

    private MoveToMatchingBlockGoal(PathfinderMob mob, JsonObject component,
        Predicate<BlockState> wanted, BiConsumer<ServerLevel, BlockPos> arrival) {
        this(mob, component, wanted, arrival, null);
    }

    private MoveToMatchingBlockGoal(PathfinderMob mob, JsonObject component,
        Predicate<BlockState> wanted, BiConsumer<ServerLevel, BlockPos> arrival, String flag) {
        super(mob, speed(component), range(component), height(component));
        this.wanted = wanted;
        this.arrival = arrival;
        this.flag = flag;
    }

    @Override
    public void start() {
        super.start();
        publish(true);
    }

    @Override
    public void stop() {
        super.stop();
        publish(false);
    }

    private void publish(boolean running) {
        if (flag != null && mob instanceof AddonEntity addon) {
            addon.setValue(flag, running);
        }
    }

    /** `minecraft:behavior.move_to_block`, which names the blocks it is looking for. */
    public static MoveToMatchingBlockGoal named(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component, among(component, "target_blocks"), null);
    }

    /** `minecraft:behavior.move_to_land`, which is any ground that is not under a liquid. */
    public static MoveToMatchingBlockGoal land(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component,
            state -> state.getFluidState().isEmpty() && !state.isAir(), null);
    }

    /** `minecraft:behavior.move_to_lava`, which a pack gives to something that lives in it. */
    public static MoveToMatchingBlockGoal lava(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component,
            state -> state.getFluidState().is(Fluids.LAVA)
                || state.getFluidState().is(Fluids.FLOWING_LAVA), null);
    }

    /** `minecraft:behavior.move_to_random_block`, which wants somewhere to stand and nothing more. */
    public static MoveToMatchingBlockGoal anywhere(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component, state -> !state.isAir(), null);
    }

    /**
     * `minecraft:behavior.random_search_and_dig`, which turns over the ground it walked to.
     *
     * Bedrock lists what may be dug and what comes out of it, and whether anything comes out is the
     * pack's own loot table rather than the block's, so the digging here only breaks the ground.
     */
    public static MoveToMatchingBlockGoal searchingAndDigging(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component, among(component, "target_blocks"),
            (level, at) -> level.destroyBlock(at, true, mob), "is_searching");
    }

    /** `minecraft:behavior.inspect_bookshelf`, which is a walk over to a shelf and a look at it. */
    public static MoveToMatchingBlockGoal bookshelf(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component,
            state -> state.is(net.minecraft.world.level.block.Blocks.BOOKSHELF)
                || state.is(net.minecraft.world.level.block.Blocks.CHISELED_BOOKSHELF), null);
    }

    /** `minecraft:behavior.raid_garden`, which eats the blocks a pack listed rather than leaving them. */
    public static MoveToMatchingBlockGoal raid(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component, among(component, "blocks"),
            (level, at) -> level.destroyBlock(at, false, mob));
    }

    /**
     * `minecraft:behavior.harvest_farm_block`, which takes the crop once it is worth taking.
     *
     * Java knows when a crop has stopped growing, so what counts as ripe is the block's own answer
     * rather than a list of ages per crop.
     */
    public static MoveToMatchingBlockGoal harvest(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component, MoveToMatchingBlockGoal::ripe,
            (level, at) -> level.destroyBlock(at, true, mob));
    }

    /** `minecraft:behavior.fertilize_farm_block`, which is bone meal by another name. */
    public static MoveToMatchingBlockGoal fertilize(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component,
            state -> state.getBlock() instanceof BonemealableBlock && !ripe(state),
            (level, at) -> bonemeal(level, at, mob));
    }

    @Override
    public void tick() {
        super.tick();
        if (arrival == null || !isReachedTarget()) return;
        if (!(mob.level() instanceof ServerLevel level)) return;

        arrival.accept(level, blockPos);
    }

    @Override
    protected boolean isValidTarget(LevelReader level, BlockPos at) {
        return wanted.test(level.getBlockState(at));
    }

    /** A crop that bone meal would no longer change is one that has finished growing. */
    private static boolean ripe(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private static void bonemeal(ServerLevel level, BlockPos at, PathfinderMob mob) {
        BlockState state = level.getBlockState(at);
        if (!(state.getBlock() instanceof BonemealableBlock crop)) return;
        if (!Platform.canBonemeal(crop, level, at, state)) return;

        crop.performBonemeal(level, mob.getRandom(), at, state);
    }

    /** The blocks a component lists under one key, as the question the goal asks of a state. */
    private static Predicate<BlockState> among(JsonObject component, String key) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (JsonElement element : Json.list(component, key)) {
            if (!element.isJsonPrimitive()) continue;

            Identifier id = Identifier.tryParse(element.getAsString());
            if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
        }
        return state -> blocks.contains(state.getBlock());
    }

    private static double speed(JsonObject component) {
        return number(component, "speed_multiplier", 1);
    }

    private static int range(JsonObject component) {
        return (int) number(component, "search_range", 16);
    }

    private static int height(JsonObject component) {
        return (int) number(component, "search_height", 4);
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
