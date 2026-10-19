package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Disappearing into a stone block, and calling out whatever is hiding in the ones nearby.
 *
 * The two halves of a silverfish's life with stone. Merging swaps a touching block for its infested
 * form and takes the entity out of the world; waking breaks the infested blocks in reach, and what
 * comes out of them is the block's own doing. Java writes both into `Silverfish` as private goals,
 * so a pack's entity reaches neither, and neither carries a value in its component body.
 */
public final class SilverfishGoal extends Goal {
    /** How far a call for help reaches, which is the radius Java's own silverfish shouts across. */
    private static final int WAKE_RADIUS = 3;

    /** Ticks between two looks at the blocks around the entity. */
    private static final int SEARCH_INTERVAL = 40;

    private final AddonEntity mob;
    private final boolean merges;
    private int untilSearch;
    private BlockPos found;

    private SilverfishGoal(AddonEntity mob, boolean merges) {
        this.mob = mob;
        this.merges = merges;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.silverfish_merge_with_stone`, which hides the entity inside a block. */
    public static SilverfishGoal merging(AddonEntity mob) {
        return new SilverfishGoal(mob, true);
    }

    /** `minecraft:behavior.silverfish_wake_up_friends`, which breaks open what is infested nearby. */
    public static SilverfishGoal wakingFriends(AddonEntity mob) {
        return new SilverfishGoal(mob, false);
    }

    @Override
    public boolean canUse() {
        // Both halves walk a box of blocks, which is more than a per-tick question is worth.
        if (untilSearch-- > 0) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;

        untilSearch = SEARCH_INTERVAL;
        if (merges && mob.getTarget() != null) return false;
        if (!merges && mob.getLastHurtByMob() == null) return false;

        found = merges ? hostBeside(level) : infestedNearby(level);
        return found != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (found == null || !(mob.level() instanceof ServerLevel level)) return;

        if (merges) {
            level.setBlockAndUpdate(found, InfestedBlock.infestedStateByHost(level.getBlockState(found)));
            mob.discard();
            return;
        }
        level.destroyBlock(found, false, mob);
    }

    /** A block the entity is touching that has an infested form to become. */
    private BlockPos hostBeside(ServerLevel level) {
        for (Direction side : Direction.values()) {
            BlockPos at = mob.blockPosition().relative(side);
            if (InfestedBlock.isCompatibleHostBlock(level.getBlockState(at))) return at;
        }
        return null;
    }

    /** The nearest infested block in shouting range, or null where none is. */
    private BlockPos infestedNearby(ServerLevel level) {
        for (BlockPos at : Platform.withinManhattan(mob.blockPosition(), WAKE_RADIUS, WAKE_RADIUS,
            WAKE_RADIUS)) {
            BlockState state = level.getBlockState(at);
            if (state.getBlock() instanceof InfestedBlock) return at.immutable();
        }
        return null;
    }
}
