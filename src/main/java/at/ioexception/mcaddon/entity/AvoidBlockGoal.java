package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Set;

/**
 * Walks away from a block the entity will not stand near, and says so while it is walking.
 *
 * `minecraft:behavior.avoid_block` names the blocks and how far around the entity to look for one.
 * Java keeps no record of an entity fleeing anything, so the fact is written on the entity for
 * `q.is_avoiding_block` to read back.
 */
public final class AvoidBlockGoal extends Goal {
    private final AddonEntity mob;
    private final Set<Block> avoided;
    private final int range;
    private final int height;
    private final int interval;
    private final double walkSpeed;
    private final double sprintSpeed;

    private BlockPos found;
    private Vec3 towards;
    private int untilNextLook;

    public AvoidBlockGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.avoided = Goals.blocks(component, "target_blocks");
        this.range = (int) Goals.number(component, "search_range", 0);
        this.height = (int) Goals.number(component, "search_height", 0);
        this.interval = Math.max(1, (int) Goals.number(component, "tick_interval", 1));
        this.walkSpeed = Goals.number(component, "walk_speed_modifier", 1);
        this.sprintSpeed = Goals.number(component, "sprint_speed_modifier", 1);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (avoided.isEmpty() || untilNextLook-- > 0) return false;

        untilNextLook = interval;
        found = nearest();
        if (found == null) return false;

        towards = DefaultRandomPos.getPosAway(mob, range + 1, height + 1, Vec3.atCenterOf(found));
        return towards != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(towards.x, towards.y, towards.z, walkSpeed);
        mob.setValue("is_avoiding_block", true);
    }

    @Override
    public void tick() {
        // Bedrock runs the last stretch away from the block at the faster of the two speeds.
        mob.getNavigation().setSpeedModifier(
            mob.distanceToSqr(Vec3.atCenterOf(found)) < range * range ? sprintSpeed : walkSpeed);
    }

    @Override
    public void stop() {
        found = null;
        towards = null;
        mob.setValue("is_avoiding_block", false);
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
