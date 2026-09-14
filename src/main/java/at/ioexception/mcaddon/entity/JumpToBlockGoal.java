package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Leaps onto a block nearby instead of walking round to it.
 *
 * `minecraft:behavior.jump_to_block` is how a Bedrock goat gets up a cliff a path would never find.
 * The jump is aimed rather than pathed: the target is a block with air above it inside the search
 * box, and the entity is thrown at it hard enough to land.
 */
public final class JumpToBlockGoal extends Goal {
    private final AddonEntity mob;
    private final int cooldownLow;
    private final int cooldownHigh;
    private final double maxVelocity;
    private final int minimumDistance;
    private final int searchWidth;
    private final int searchHeight;
    private final Set<Block> preferred;
    private final Set<Block> forbidden;
    private int untilNext;
    private BlockPos landing;

    public JumpToBlockGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.cooldownLow = Goals.ticksLow(component, "cooldown_range", 10);
        this.cooldownHigh = Goals.ticksHigh(component, "cooldown_range", 20);
        this.maxVelocity = Goals.number(component, "max_velocity", 1.5);
        this.minimumDistance = (int) Goals.number(component, "minimum_distance", 2);
        this.searchWidth = clamp(Goals.number(component, "search_width", 8));
        this.searchHeight = clamp(Goals.number(component, "search_height", 10));
        this.preferred = Goals.blocks(component, "preferred_blocks");
        this.forbidden = Goals.blocks(component, "forbidden_blocks");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (untilNext-- > 0 || !mob.onGround()) return false;

        landing = pick();
        return landing != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        untilNext = Goals.between(mob.getRandom(), cooldownLow, cooldownHigh);
        if (landing == null) return;

        Vec3 towards = Vec3.atCenterOf(landing).subtract(mob.position());
        double flat = Math.sqrt(towards.x * towards.x + towards.z * towards.z);
        double reach = Math.min(maxVelocity, flat / 6);
        mob.setDeltaMovement(towards.x / Math.max(1, flat) * reach,
            Math.max(0.42, towards.y * 0.15 + 0.42), towards.z / Math.max(1, flat) * reach);
    }

    /** A standable block inside the search box, taking one of the preferred kinds when there is one. */
    private BlockPos pick() {
        List<BlockPos> reachable = new ArrayList<>();
        List<BlockPos> wanted = new ArrayList<>();
        BlockPos at = mob.blockPosition();

        for (BlockPos candidate : BlockPos.betweenClosed(
            at.offset(-searchWidth, -searchHeight, -searchWidth),
            at.offset(searchWidth, searchHeight, searchWidth))) {
            if (at.distManhattan(candidate) < minimumDistance) continue;
            if (!standable(candidate)) continue;

            BlockPos immutable = candidate.immutable();
            reachable.add(immutable);
            if (preferred.contains(mob.level().getBlockState(immutable).getBlock())) {
                wanted.add(immutable);
            }
        }

        List<BlockPos> from = wanted.isEmpty() ? reachable : wanted;
        return from.isEmpty() ? null : from.get(mob.getRandom().nextInt(from.size()));
    }

    /** Something solid with room to stand on it, and not one of the blocks the pack ruled out. */
    private boolean standable(BlockPos at) {
        if (forbidden.contains(mob.level().getBlockState(at).getBlock())) return false;

        return !mob.level().getBlockState(at).isAir()
            && mob.level().getFluidState(at).isEmpty()
            && mob.level().getBlockState(at.above()).isAir()
            && mob.level().getBlockState(at.above(2)).isAir();
    }

    /** Bedrock bounds both sides of the search box at fifteen blocks. */
    private static int clamp(double blocks) {
        return (int) Math.max(1, Math.min(15, blocks));
    }
}
