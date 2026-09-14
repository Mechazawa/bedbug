package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
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
import java.util.List;
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
    private final boolean obeysGriefing;

    private CarryBlockGoal(AddonEntity mob, JsonObject component, boolean taking,
        String blockKey, String filterKey, String eventKey) {
        this.mob = mob;
        this.taking = taking;
        this.chance = Goals.number(component, "chance", 1);
        this.xzLow = (int) Goals.low(component, "xz_range", -2);
        this.xzHigh = (int) Goals.high(component, "xz_range", 2);
        this.yLow = (int) Goals.low(component, "y_range", 0);
        this.yHigh = (int) Goals.high(component, "y_range", 3);
        this.allowed = blockKey == null ? Set.of() : Goals.blocks(component, blockKey);
        this.conditions = filterKey == null ? Filter.parse(null) : Goals.filter(component, filterKey);
        this.doneEvent = eventKey == null ? null : Goals.event(component, eventKey);
        this.obeysGriefing = Goals.flag(component, "affected_by_griefing_rule", true);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.take_block`, which lifts one of the blocks the pack listed. */
    public static CarryBlockGoal taking(AddonEntity mob, JsonObject component) {
        return new CarryBlockGoal(mob, component, true, "blocks", "can_take", "on_take");
    }

    /** `minecraft:behavior.place_block`, which puts down whatever the entity is carrying. */
    public static CarryBlockGoal placing(AddonEntity mob, JsonObject component) {
        return new CarryBlockGoal(mob, component, false, "randomly_placeable_blocks",
            "can_place", "on_place");
    }

    /** `minecraft:behavior.enderman_take_block`, the same lifting with an enderman's own reach. */
    public static CarryBlockGoal endermanTaking(AddonEntity mob, JsonObject component) {
        return new CarryBlockGoal(mob, component, true, null, null, null);
    }

    /** `minecraft:behavior.enderman_leave_block`, which sets down what the enderman carried. */
    public static CarryBlockGoal endermanLeaving(AddonEntity mob, JsonObject component) {
        return new CarryBlockGoal(mob, component, false, null, null, null);
    }

    @Override
    public boolean canUse() {
        if (mob.getRandom().nextDouble() > chance) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;
        if (obeysGriefing && !Platform.mobGriefing(level)) return false;
        if (taking == carrying()) return false;

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
        Block held = carried();
        if (held == null) return;

        for (BlockPos at : shuffled()) {
            if (!level.getBlockState(at).canBeReplaced()) continue;
            if (level.getBlockState(at.below()).isAir()) continue;

            level.setBlockAndUpdate(at, held.defaultBlockState());
            mob.setValue(CARRIED, null);
            raise(at);
            return;
        }
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
}
