package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks a block up off the ground and puts it down again somewhere else.
 *
 * Bedrock has four goals for this and every one of them is half of the same thing: an enderman takes
 * a block and leaves it, and the generic pair does the same with the pack's own filters and ranges
 * around it. What is being carried is kept as `q.carried_block_identifier`, which is how a pack's
 * render controller knows to draw it.
 */
public final class CarryBlockGoal extends Goal {
    /** What the carried block is stored under, which is the name Bedrock's own query reads. */
    private static final String CARRIED = "carried_block_identifier";

    private final AddonEntity mob;
    private final boolean taking;
    private final double chance;
    private final int xzLow;
    private final int xzHigh;
    private final int yLow;
    private final int yHigh;
    private final Set<Block> allowed;
    private final Filter conditions;
    private final String doneEvent;
    private final List<RandomBlock> randomBlocks;
    private final boolean obeysGriefing;

    private CarryBlockGoal(AddonEntity mob, JsonObject component, boolean taking, Set<Block> allowed,
        Filter conditions, String doneEvent, List<RandomBlock> randomBlocks) {
        this.mob = mob;
        this.taking = taking;
        this.chance = Goals.number(component, "chance", 1);
        this.xzLow = (int) Goals.low(component, "xz_range", -2);
        this.xzHigh = (int) Goals.high(component, "xz_range", 2);
        this.yLow = (int) Goals.low(component, "y_range", 0);
        this.yHigh = (int) Goals.high(component, "y_range", 3);
        this.allowed = allowed;
        this.conditions = conditions;
        this.doneEvent = doneEvent;
        this.randomBlocks = randomBlocks;
        this.obeysGriefing = Goals.flag(component, "affected_by_griefing_rule", true);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.take_block`, which lifts one of the blocks the pack listed. */
    public static CarryBlockGoal taking(AddonEntity mob, JsonObject component) {
        return new CarryBlockGoal(mob, component, true, Goals.blocks(component, "blocks"),
            Goals.filter(component, "can_take"), Goals.event(component, "on_take"), List.of());
    }

    /**
     * `minecraft:behavior.place_block`, which puts down one of `randomly_placeable_blocks` where the
     * pack lists any, and whatever the entity is carrying where it lists none.
     */
    public static CarryBlockGoal placing(AddonEntity mob, JsonObject component) {
        return new CarryBlockGoal(mob, component, false, Goals.blocks(component, "placeable_carried_blocks"),
            Goals.filter(component, "can_place"), Goals.event(component, "on_place"),
            Json.list(component, "randomly_placeable_blocks").stream()
                .filter(JsonElement::isJsonObject)
                .map(entry -> RandomBlock.tryFrom(entry.getAsJsonObject()))
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    /** `minecraft:behavior.enderman_take_block`, the same lifting with an enderman's own reach. */
    public static CarryBlockGoal endermanTaking(AddonEntity mob, JsonObject component) {
        return new CarryBlockGoal(mob, component, true, Set.of(), Filter.parse(null), null, List.of());
    }

    /** `minecraft:behavior.enderman_leave_block`, which sets down what the enderman carried. */
    public static CarryBlockGoal endermanLeaving(AddonEntity mob, JsonObject component) {
        return new CarryBlockGoal(mob, component, false, Set.of(), Filter.parse(null), null, List.of());
    }

    @Override
    public boolean canUse() {
        if (mob.getRandom().nextDouble() > chance) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;
        if (obeysGriefing && !Platform.mobGriefing(level)) return false;
        if (taking ? carrying() : randomBlocks.isEmpty() && !carrying()) return false;

        return conditions.matches(new Filter.Context(mob, mob, mob.getTarget(), ServerHooks.state()));
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(mob.level() instanceof ServerLevel level)) return;

        if (taking) {
            take(level);
        } else {
            place(level);
        }
    }

    private void take(ServerLevel level) {
        for (BlockPos at : shuffled()) {
            BlockState state = level.getBlockState(at);
            if (state.isAir() || state.getDestroySpeed(level, at) < 0) continue;
            if (!allowed.isEmpty() && !allowed.contains(state.getBlock())) continue;

            level.removeBlock(at, false);
            mob.setValue(CARRIED, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            raise(at);
            return;
        }
    }

    private void place(ServerLevel level) {
        BlockState placed = randomBlocks.isEmpty() ? carriedState() : RandomBlock.pick(randomBlocks, mob);
        if (placed == null) return;

        for (BlockPos at : shuffled()) {
            if (!level.getBlockState(at).canBeReplaced()) continue;
            if (level.getBlockState(at.below()).isAir()) continue;

            level.setBlockAndUpdate(at, placed);
            if (randomBlocks.isEmpty()) mob.setValue(CARRIED, null);
            raise(at);
            return;
        }
    }

    /** The carried block, where the pack's `placeable_carried_blocks` lets it be put down. */
    private BlockState carriedState() {
        Block held = carried();
        return held == null || !allowed.isEmpty() && !allowed.contains(held) ? null : held.defaultBlockState();
    }

    /** The places the pack's two ranges cover, in no order, so a run does not always pick the same. */
    private List<BlockPos> shuffled() {
        List<BlockPos> places = new ArrayList<>();
        BlockPos at = mob.blockPosition();
        for (int x = xzLow; x <= xzHigh; x++) {
            for (int y = yLow; y <= yHigh; y++) {
                for (int z = xzLow; z <= xzHigh; z++) {
                    places.add(at.offset(x, y, z));
                }
            }
        }
        java.util.Collections.shuffle(places);
        return places;
    }

    private boolean carrying() {
        return carried() != null;
    }

    /** The block the entity is carrying, by the name it was stored under. */
    private Block carried() {
        Object stored = mob.value(CARRIED);
        Identifier id = stored instanceof String text ? Identifier.tryParse(text) : null;
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    private void raise(BlockPos at) {
        if (doneEvent == null) return;

        mob.setValue("block_x", (double) at.getX());
        mob.setValue("block_y", (double) at.getY());
        mob.setValue("block_z", (double) at.getZ());
        EntityEvents.trigger(mob, doneEvent, mob.getTarget(), ServerHooks.state());
    }

    /** One entry of `randomly_placeable_blocks`: a block in its states, offered while its filter holds. */
    private record RandomBlock(BlockState state, Filter filter, int weight) {
        static RandomBlock tryFrom(JsonObject entry) {
            JsonElement block = entry.get("block");
            JsonObject descriptor = block != null && block.isJsonObject() ? block.getAsJsonObject() : null;
            String name = descriptor != null ? Json.text(descriptor, "name", null)
                : block != null && block.isJsonPrimitive() ? block.getAsString() : null;
            if (name == null) return null;

            Map<String, String> states = new LinkedHashMap<>();
            JsonObject stated = descriptor == null ? null : Goals.object(descriptor, "states");
            if (stated != null) stated.entrySet().forEach(state -> states.put(state.getKey(), state.getValue().getAsString()));
            BlockState state = AddonBlocks.stateOf(name, states, AddonEntities::unsupported);
            int weight = (int) Goals.number(entry, "weight", 1);
            return state == null || weight <= 0 ? null : new RandomBlock(state, Goals.filter(entry, "filter"), weight);
        }

        /** A weighted draw among the entries whose filter the entity passes, or null where none does. */
        static BlockState pick(List<RandomBlock> entries, AddonEntity mob) {
            Filter.Context context = new Filter.Context(mob, mob, mob.getTarget(), ServerHooks.state());
            List<RandomBlock> open = entries.stream().filter(entry -> entry.filter.matches(context)).toList();
            int total = open.stream().mapToInt(RandomBlock::weight).sum();
            if (total <= 0) return null;

            int drawn = mob.getRandom().nextInt(total);
            for (RandomBlock entry : open) {
                drawn -= entry.weight;
                if (drawn < 0) return entry.state;
            }
            return null;
        }
    }
}
