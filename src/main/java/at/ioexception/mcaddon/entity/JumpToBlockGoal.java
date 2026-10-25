package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.pathfinder.Path;
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
 * box that a walk would take at least `minimum_path_length` blocks to reach, and the entity is thrown
 * at it hard enough to land. It flies in the long jumping pose, whose box `AddonEntity` scales by
 * `scale_factor`. A preferred block is picked on a `preferred_blocks_chance` chance, and any block
 * otherwise. `cooldown_range` counts from each attempt, whether it found somewhere or not.
 */
public final class JumpToBlockGoal extends Goal {
    /** The most walks one pick asks the navigator for, since each one is a path search. */
    private static final int PATHS = 16;

    /** The ticks a jump may stay in the air before the goal lets it go. */
    private static final int LONGEST_FLIGHT = 100;

    /** The ticks after leaving the ground before standing on it again counts as landing. */
    private static final int TAKE_OFF = 2;

    private final AddonEntity mob;
    private final int cooldownLow;
    private final int cooldownHigh;
    private final double maxVelocity;
    private final int minimumDistance;
    private final int searchWidth;
    private final int searchHeight;
    private final Set<Block> preferred;
    private final Set<Block> forbidden;
    private final int minimumPath;
    private final double preferredChance;
    private int readyAt;
    private int flown;
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
        this.minimumPath = (int) Goals.number(component, "minimum_path_length", 5);
        this.preferredChance = Goals.number(component, "preferred_blocks_chance", 1);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (mob.tickCount < readyAt || !mob.onGround()) return false;

        landing = pick();
        if (landing == null) readyAt = mob.tickCount + Goals.between(mob.getRandom(), cooldownLow, cooldownHigh);
        return landing != null;
    }

    @Override
    public boolean canContinueToUse() {
        return flown < LONGEST_FLIGHT && (flown < TAKE_OFF || !mob.onGround());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        flown++;
    }

    @Override
    public void stop() {
        mob.setPose(Pose.STANDING);
    }

    @Override
    public void start() {
        readyAt = mob.tickCount + Goals.between(mob.getRandom(), cooldownLow, cooldownHigh);
        flown = 0;
        mob.setPose(Pose.LONG_JUMPING);

        Vec3 towards = Vec3.atCenterOf(landing).subtract(mob.position());
        double flat = Math.sqrt(towards.x * towards.x + towards.z * towards.z);
        double reach = Math.min(maxVelocity, flat / 6);
        mob.setDeltaMovement(towards.x / Math.max(1, flat) * reach,
            Math.max(0.42, towards.y * 0.15 + 0.42), towards.z / Math.max(1, flat) * reach);
    }

    /**
     * A standable block inside the search box that is no short walk away, taking one of the preferred
     * kinds on the pack's chance when there is one.
     */
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

        boolean preferring = !wanted.isEmpty() && mob.getRandom().nextDouble() < preferredChance;
        List<BlockPos> from = preferring ? wanted : reachable;
        java.util.Collections.shuffle(from, new java.util.Random(mob.getRandom().nextLong()));

        int paths = 0;
        for (BlockPos candidate : from) {
            if (minimumPath <= 0 || !shortWalk(candidate)) return candidate;
            if (++paths >= PATHS) break;
        }
        return null;
    }

    /** Whether the entity could walk to stand on the block in fewer than `minimum_path_length` steps. */
    private boolean shortWalk(BlockPos block) {
        Path walk = mob.getNavigation().createPath(block.above(), 0);
        return walk != null && walk.canReach() && walk.getNodeCount() - 1 < minimumPath;
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
