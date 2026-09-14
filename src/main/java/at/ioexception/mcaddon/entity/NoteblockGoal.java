package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Keeping company with a note block, and handing it what the entity is carrying.
 *
 * Bedrock's allay hears a note block play and then either stays beside it or brings its items over
 * and throws them at it. Java has no note block an entity can listen for, so the block itself is what
 * the goal looks for: the nearest one in range stands in for the one that was played. The distances,
 * the speed, how hard the throw goes and the event it fires are all the pack's own.
 */
public final class NoteblockGoal extends Goal {
    /** How far a search reaches when the pack names no distance of its own. */
    private static final int DEFAULT_RANGE = 16;

    /** How far above and below the entity a note block still counts. */
    private static final int SEARCH_HEIGHT = 4;

    /** Ticks between two searches of the blocks around the entity. */
    private static final int SEARCH_INTERVAL = 60;

    private final AddonEntity mob;
    private final boolean delivers;
    private final double searchRange;
    private final double reach;
    private final double speed;
    private final int listenTicks;
    private final double throwForce;
    private final double throwRise;
    private final List<String> onThrow = new ArrayList<>();
    private int untilSearch;
    private int listened;
    private BlockPos block;

    private NoteblockGoal(AddonEntity mob, JsonObject component, boolean delivers,
        double searchRange, double reach) {
        this.mob = mob;
        this.delivers = delivers;
        this.searchRange = searchRange;
        this.reach = reach;
        this.speed = Goals.number(component, delivers ? "run_speed" : "speed", 1);
        this.listenTicks = (int) Goals.number(component, "listen_time", 600);
        this.throwForce = Goals.number(component, "throw_force", 0.2);
        this.throwRise = Goals.number(component, "vertical_throw_mul", 1.5);
        for (JsonElement element : Json.list(component, "on_item_throw")) {
            String event = element.isJsonPrimitive() ? element.getAsString()
                : element.isJsonObject() ? Json.text(element.getAsJsonObject(), "event", null) : null;
            if (event != null) onThrow.add(event);
        }
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.stay_near_noteblock`, which keeps the entity within reach of one. */
    public static NoteblockGoal stayingNear(AddonEntity mob, JsonObject component) {
        return new NoteblockGoal(mob, component, false,
            Goals.number(component, "start_distance", DEFAULT_RANGE),
            Goals.number(component, "stop_distance", 4));
    }

    /** `minecraft:behavior.go_and_give_items_to_noteblock`, which throws its items at one. */
    public static NoteblockGoal deliveringItems(AddonEntity mob, JsonObject component) {
        return new NoteblockGoal(mob, component, true, DEFAULT_RANGE,
            Goals.number(component, "reach_block_distance", 3));
    }

    @Override
    public boolean canUse() {
        // Walking a box of blocks to find a note block is not a question to ask on every tick.
        if (untilSearch-- > 0) return false;
        if (delivers && mob.getMainHandItem().isEmpty()) return false;

        untilSearch = SEARCH_INTERVAL;
        block = nearestNoteblock();
        return block != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (block == null) return false;

        return delivers ? !mob.getMainHandItem().isEmpty() : listened < listenTicks;
    }

    @Override
    public void start() {
        listened = 0;
    }

    @Override
    public void stop() {
        block = null;
    }

    @Override
    public void tick() {
        if (block == null) return;

        listened++;
        Vec3 at = Vec3.atCenterOf(block);
        mob.getLookControl().setLookAt(at.x, at.y, at.z);
        if (mob.position().distanceTo(at) > reach) {
            if (mob.getNavigation().isDone()) mob.getNavigation().moveTo(at.x, at.y, at.z, speed);
            return;
        }

        mob.getNavigation().stop();
        if (delivers) throwItem(at);
    }

    /** Lets go of what the entity is holding, aimed at the block it walked to. */
    private void throwItem(Vec3 at) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        ItemStack held = mob.getMainHandItem();
        if (held.isEmpty()) return;

        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemEntity thrown = new ItemEntity(level, mob.getX(), mob.getEyeY(), mob.getZ(), held.copy());
        Vec3 towards = at.subtract(mob.position()).normalize();
        thrown.setDeltaMovement(towards.x * throwForce,
            towards.y * throwForce + throwForce * throwRise, towards.z * throwForce);
        level.addFreshEntity(thrown);
        onThrow.forEach(event -> EntityEvents.trigger(mob, event, null, ServerHooks.state()));
    }

    /** The nearest note block in range, or null where there is none. */
    private BlockPos nearestNoteblock() {
        BlockPos from = mob.blockPosition();
        int range = (int) Math.ceil(searchRange);
        BlockPos nearest = null;
        double closest = Double.MAX_VALUE;

        for (BlockPos at : BlockPos.betweenClosed(from.offset(-range, -SEARCH_HEIGHT, -range),
            from.offset(range, SEARCH_HEIGHT, range))) {
            if (!mob.level().getBlockState(at).is(Blocks.NOTE_BLOCK)) continue;

            double away = at.distSqr(from);
            if (away >= closest) continue;

            closest = away;
            nearest = at.immutable();
        }
        return nearest;
    }
}
