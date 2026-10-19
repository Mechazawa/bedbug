package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.block.Blocks;

/**
 * `minecraft:behavior.stomp_turtle_egg`, on Java's own goal for the same thing, the one its zombie
 * uses on a turtle egg.
 *
 * Bedrock gives the goal a one in `interval` chance each tick of looking for an egg, where Java waits
 * ten to twenty seconds between looks. Java's constructor fixes the search at twenty-four blocks, so
 * the search is Java's own walk outwards from the mob, cut at `search_range`. It counts an egg as
 * reached within 1.14 blocks of the point above it, which a mob stopping at the edge of its waypoint
 * tolerance on top of the egg misses. `goal_radius` is added to that as wiggle room.
 */
public final class StompEggGoal extends RemoveBlockGoal {
    /** What Java's goal waits once it has found an egg, before it looks again. */
    private static final int WAIT_AFTER_FOUND = 20;

    private final int interval;
    private final double reach;
    private final int searchRange;
    private final int searchHeight;

    public StompEggGoal(PathfinderMob mob, JsonObject goal) {
        super(Blocks.TURTLE_EGG, mob, Goals.number(goal, "speed_multiplier", 1),
            (int) Goals.number(goal, "search_height", 1));
        this.reach = 1.14 + Goals.number(goal, "goal_radius", 0.5);
        this.interval = Math.max(1, (int) Goals.number(goal, "interval", 120));
        this.searchRange = (int) Goals.number(goal, "search_range", 0);
        this.searchHeight = (int) Goals.number(goal, "search_height", 1);
    }

    @Override
    public double acceptedDistance() {
        return reach;
    }

    /**
     * Java's own start, with the search cut at `search_range`.
     *
     * The search is decided here rather than in `findNearestBlock`, because a mod reworking Java's
     * block searches for speed replaces the call to it from Java's own start with a search over
     * Java's fixed twenty-four blocks.
     */
    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel level) || !Platform.mobGriefing(level)) return false;
        if (nextStartTick > 0) {
            nextStartTick--;
            return false;
        }
        if (nearestEgg()) {
            nextStartTick = reducedTickDelay(WAIT_AFTER_FOUND);
            return true;
        }
        nextStartTick = nextStartTick(mob);
        return false;
    }

    /** Rings outwards from the mob, nearest first, at each height in the order Java's goal tries them. */
    private boolean nearestEgg() {
        BlockPos from = mob.blockPosition();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dy = verticalSearchStart; dy <= searchHeight; dy = dy > 0 ? -dy : 1 - dy) {
            for (int ring = 0; ring < searchRange; ring++) {
                for (int dx = 0; dx <= ring; dx = dx > 0 ? -dx : 1 - dx) {
                    for (int dz = dx < ring && dx > -ring ? ring : 0; dz <= ring; dz = dz > 0 ? -dz : 1 - dz) {
                        at.setWithOffset(from, dx, dy - 1, dz);
                        if (Platform.withinHome(mob, at) && isValidTarget(mob.level(), at)) {
                            blockPos = at.immutable();
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** A one in N chance each tick waits N ticks on average, which is twice that drawn evenly. */
    @Override
    protected int nextStartTick(PathfinderMob mob) {
        return reducedTickDelay(mob.getRandom().nextInt(2 * interval));
    }
}
