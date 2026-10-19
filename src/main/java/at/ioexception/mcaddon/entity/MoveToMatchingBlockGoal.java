package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.List;
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

    /** The pose held on arrival, or null where the walk is all there is to the behaviour. */
    private final Pose settled;

    /** How near the spot over the block counts as there, from where the entity stands. */
    private final double radius;
    private boolean reached;

    /** Bedrock's own timing and arrival, which only `minecraft:behavior.move_to_block` states. */
    private Visit visit;
    private boolean announced;
    private int stayed;

    private MoveToMatchingBlockGoal(PathfinderMob mob, JsonObject component,
        Predicate<BlockState> wanted, BiConsumer<ServerLevel, BlockPos> arrival) {
        this(mob, component, wanted, arrival, null, null);
    }

    private MoveToMatchingBlockGoal(PathfinderMob mob, JsonObject component,
        Predicate<BlockState> wanted, BiConsumer<ServerLevel, BlockPos> arrival, String flag) {
        this(mob, component, wanted, arrival, flag, null);
    }

    private MoveToMatchingBlockGoal(PathfinderMob mob, JsonObject component,
        Predicate<BlockState> wanted, BiConsumer<ServerLevel, BlockPos> arrival, String flag,
        Pose settled) {
        super(mob, speed(component), range(component), height(component));
        this.radius = number(component, "goal_radius", 0.5);
        this.wanted = wanted;
        this.arrival = arrival;
        this.flag = flag;
        this.settled = settled;
    }

    @Override
    public void start() {
        super.start();
        publish(true);
        announced = false;
        stayed = 0;
    }

    @Override
    public void stop() {
        super.stop();
        publish(false);
        if (settled != null) mob.setPose(Pose.STANDING);
    }

    private void publish(boolean running) {
        if (flag != null && mob instanceof AddonEntity addon) {
            addon.setValue(flag, running);
        }
    }

    /**
     * `minecraft:behavior.move_to_block`, which names the blocks it is looking for, looks every
     * `tick_interval` ticks at a `start_chance`, walks to the spot `target_offset` from the block and
     * raises `on_reach` there and `on_stay_completed` once it has stood there `stay_duration` ticks.
     */
    public static MoveToMatchingBlockGoal named(PathfinderMob mob, JsonObject component) {
        MoveToMatchingBlockGoal goal =
            new MoveToMatchingBlockGoal(mob, component, among(component, "target_blocks"), null);
        goal.visit = Visit.of(component);
        return goal;
    }

    private record Visit(int interval, double chance, Vec3i offset, String onReach, int stay, String onStayed) {
        static Visit of(JsonObject component) {
            List<JsonElement> offset = Json.list(component, "target_offset");
            return new Visit((int) number(component, "tick_interval", 20), number(component, "start_chance", 1),
                offset.size() == 3 ? new Vec3i((int) Math.floor(offset.get(0).getAsDouble()),
                    (int) Math.floor(offset.get(1).getAsDouble()), (int) Math.floor(offset.get(2).getAsDouble()))
                    : Vec3i.ZERO,
                Goals.event(component, "on_reach"), Math.max(1, (int) number(component, "stay_duration", 0)),
                Goals.event(component, "on_stay_completed"));
        }
    }

    @Override
    protected int nextStartTick(PathfinderMob mob) {
        return visit == null ? super.nextStartTick(mob) : reducedTickDelay(Math.max(1, visit.interval()));
    }

    @Override
    public boolean canUse() {
        return super.canUse() && (visit == null || mob.getRandom().nextDouble() < visit.chance());
    }

    @Override
    public boolean canContinueToUse() {
        return super.canContinueToUse() && (visit == null || visit.onStayed() == null || stayed < visit.stay());
    }

    @Override
    protected BlockPos getMoveToTarget() {
        return visit == null ? super.getMoveToTarget() : super.getMoveToTarget().offset(visit.offset());
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

    /** `minecraft:behavior.move_to_water`, which looks as far for water as its pack says. */
    public static MoveToMatchingBlockGoal water(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component,
            state -> state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER), null);
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

    /**
     * `minecraft:behavior.ocelot_sit_on_block`, which is a cat claiming whatever is warm.
     *
     * Java writes this as a goal on its own cat, holding the list of blocks it likes. The list is
     * the same here, because it is the blocks themselves that a pack means by the name. The sitting
     * is the pose, which is what `q.is_sitting` already answers from.
     */
    public static MoveToMatchingBlockGoal sitOnBlock(PathfinderMob mob, JsonObject component) {
        return new MoveToMatchingBlockGoal(mob, component,
            state -> state.is(net.minecraft.world.level.block.Blocks.CHEST)
                || state.is(net.minecraft.world.level.block.Blocks.FURNACE)
                || state.getBlock() instanceof net.minecraft.world.level.block.BedBlock,
            null, null, Pose.SITTING);
    }

    @Override
    protected void moveMobToBlock() {
        walk(getMoveToTarget());
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * Java's own tick paths to within a block of the spot and measures from the middle of that
     * block, so an entity stops a block short and Bedrock's default radius of half a block is never
     * reached. This walk paths onto the spot itself and measures from the entity's feet.
     */
    @Override
    public void tick() {
        BlockPos target = getMoveToTarget();
        reached = mob.position().closerThan(Vec3.atBottomCenterOf(target), radius);
        if (reached) {
            tryTicks--;
        } else if (++tryTicks % RETRY_TICKS == 0) {
            walk(target);
        }
        if (!reached) return;
        if (settled != null) mob.setPose(settled);
        if (visit != null && mob instanceof AddonEntity addon) arrive(addon);
        if (arrival == null || !(mob.level() instanceof ServerLevel level)) return;

        arrival.accept(level, blockPos);
    }

    private void arrive(AddonEntity addon) {
        if (!announced) {
            announced = true;
            if (visit.onReach() != null) addon.raiseEvent(visit.onReach());
            return;
        }
        if (++stayed == visit.stay() && visit.onStayed() != null) addon.raiseEvent(visit.onStayed());
    }

    @Override
    protected boolean isReachedTarget() {
        return reached;
    }

    /** How often Java's own goal plans its walk again while it has not arrived. */
    private static final int RETRY_TICKS = 40;

    private void walk(BlockPos target) {
        mob.getNavigation().moveTo(mob.getNavigation().createPath(target, 0), speedModifier);
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

        Platform.bonemeal(crop, level, mob.getRandom(), at, state);
    }

    /** The blocks a component lists under one key, as the question the goal asks of a state. */
    private static Predicate<BlockState> among(JsonObject component, String key) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (JsonElement element : Json.list(component, key)) {
            if (!element.isJsonPrimitive()) continue;

            Block block = AddonBlocks.named(element.getAsString());
            if (block != null) blocks.add(block);
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
