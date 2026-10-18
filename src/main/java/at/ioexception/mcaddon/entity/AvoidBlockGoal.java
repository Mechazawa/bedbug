package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Walks away from a block the entity will not stand near, and says so while it is walking.
 *
 * `minecraft:behavior.avoid_block` names the blocks and how far around the entity to look for one.
 * Java keeps no record of an entity fleeing anything, so the fact is written on the entity for
 * `q.is_avoiding_block` to read back. The entity makes `avoid_block_sound` while it flees, and once
 * the block is out of the box it searches, it has escaped and raises each of `on_escape`.
 */
public final class AvoidBlockGoal extends Goal {
    private final AddonEntity mob;
    private final Set<Block> avoided;
    private final int range;
    private final int height;
    private final int interval;
    private final double walkSpeed;
    private final double sprintSpeed;
    private final String sound;
    private final int soundLow;
    private final int soundHigh;
    private final List<String> onEscape;

    private BlockPos found;
    private Vec3 towards;
    private int nextLookAt;
    private int nextSoundAt;

    public AvoidBlockGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.avoided = Goals.blocks(component, "target_blocks");
        this.range = (int) Goals.number(component, "search_range", 0);
        this.height = (int) Goals.number(component, "search_height", 0);
        this.interval = Math.max(1, (int) Goals.number(component, "tick_interval", 1));
        this.walkSpeed = Goals.number(component, "walk_speed_modifier", 1);
        this.sprintSpeed = Goals.number(component, "sprint_speed_modifier", 1);
        this.sound = Goals.text(component, "avoid_block_sound");
        this.soundLow = Goals.ticksLow(component, "sound_interval", DEFAULT_SOUND_LOW);
        this.soundHigh = Goals.ticksHigh(component, "sound_interval", DEFAULT_SOUND_HIGH);
        this.onEscape = Json.list(component, "on_escape").stream()
            .filter(JsonElement::isJsonObject)
            .map(trigger -> Goals.event(trigger.getAsJsonObject(), "event"))
            .filter(java.util.Objects::nonNull)
            .toList();
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** Bedrock's wait between two of the sounds, in seconds, when a pack states none. */
    private static final double DEFAULT_SOUND_LOW = 3;
    private static final double DEFAULT_SOUND_HIGH = 8;

    @Override
    public boolean canUse() {
        if (avoided.isEmpty() || mob.tickCount < nextLookAt) return false;

        nextLookAt = mob.tickCount + interval;
        found = nearest();
        if (found == null) return false;

        towards = DefaultRandomPos.getPosAway(mob, range + 1, height + 1, Vec3.atCenterOf(found));
        return towards != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!mob.getNavigation().isDone()) return true;
        if (!withinSearch(found)) return false;

        // Arrived and the block is still in sight: run on, since the flight ends only in escaping.
        towards = DefaultRandomPos.getPosAway(mob, range + 1, height + 1, Vec3.atCenterOf(found));
        return towards != null && mob.getNavigation().moveTo(towards.x, towards.y, towards.z, walkSpeed);
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(towards.x, towards.y, towards.z, walkSpeed);
        mob.setValue("is_avoiding_block", true);
        nextSoundAt = mob.tickCount;
    }

    @Override
    public void tick() {
        // Bedrock runs the last stretch away from the block at the faster of the two speeds.
        mob.getNavigation().setSpeedModifier(
            mob.distanceToSqr(Vec3.atCenterOf(found)) < range * range ? sprintSpeed : walkSpeed);
        if (sound == null || mob.tickCount < nextSoundAt) return;

        mob.makeNoise(sound);
        nextSoundAt = mob.tickCount + Goals.between(mob.getRandom(), Math.max(1, soundLow), soundHigh);
    }

    @Override
    public void stop() {
        if (found != null && !withinSearch(found)) onEscape.forEach(mob::raiseEvent);
        found = null;
        towards = null;
        mob.setValue("is_avoiding_block", false);
    }

    private boolean withinSearch(BlockPos block) {
        BlockPos standing = mob.blockPosition();
        return Math.abs(block.getX() - standing.getX()) <= range && Math.abs(block.getZ() - standing.getZ()) <= range
            && Math.abs(block.getY() - standing.getY()) <= height;
    }

    /** The closest block on the list within the box the component gave, or null while there is none. */
    private BlockPos nearest() {
        BlockPos standing = mob.blockPosition();
        BlockPos closest = null;
        double nearest = Double.MAX_VALUE;

        for (BlockPos at : BlockPos.betweenClosed(standing.offset(-range, -height, -range),
            standing.offset(range, height, range))) {
            if (!avoided.contains(mob.level().getBlockState(at).getBlock())) continue;

            double away = at.distSqr(standing);
            if (away >= nearest) continue;
            nearest = away;
            closest = at.immutable();
        }
        return closest;
    }
}
