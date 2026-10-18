package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Walks out under the open sky, or in under a roof, whichever the entity is not under already.
 *
 * `minecraft:behavior.move_outdoors` and `minecraft:behavior.move_indoors` are the two halves of a
 * Bedrock villager's day. A spot is looked for `search_count` times within `search_range` across and
 * `search_height` up or down, the walk counts as done within `goal_radius` of it, and a search or a
 * walk that fails rests the goal for `timeout_cooldown` seconds.
 */
final class ShelterGoal extends Goal {
    private final PathfinderMob mob;
    private final boolean outdoors;
    private final double speed;
    private final int across;
    private final int upDown;
    private final int tries;
    private final double arrived;
    private final int cooldown;
    private int readyAt;
    private Vec3 heading;

    private ShelterGoal(PathfinderMob mob, JsonObject component, boolean outdoors, double speed) {
        this.mob = mob;
        this.outdoors = outdoors;
        this.speed = Goals.number(component, "speed_multiplier", speed);
        this.across = Math.max(1, (int) Goals.number(component, "search_range", SEARCH_RANGE));
        this.upDown = Math.max(1, (int) Goals.number(component, "search_height", SEARCH_HEIGHT));
        this.tries = Math.max(1, (int) Goals.number(component, "search_count", SEARCH_COUNT));
        this.arrived = Goals.number(component, "goal_radius", GOAL_RADIUS);
        this.cooldown = Goals.ticks(component, "timeout_cooldown", TIMEOUT_SECONDS);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** Bedrock's defaults, which both halves share. */
    private static final int SEARCH_RANGE = 15;
    private static final int SEARCH_HEIGHT = 5;
    private static final int SEARCH_COUNT = 10;
    private static final double GOAL_RADIUS = 2;
    private static final double TIMEOUT_SECONDS = 8;

    /** `move_outdoors`, which Bedrock walks at half speed unless told otherwise. */
    static ShelterGoal outdoors(PathfinderMob mob, JsonObject component) {
        return new ShelterGoal(mob, component, true, OUTDOOR_SPEED);
    }

    /** `move_indoors`, which Bedrock walks at eight tenths of speed unless told otherwise. */
    static ShelterGoal indoors(PathfinderMob mob, JsonObject component) {
        return new ShelterGoal(mob, component, false, INDOOR_SPEED);
    }

    private static final double OUTDOOR_SPEED = 0.5;
    private static final double INDOOR_SPEED = 0.8;

    @Override
    public boolean canUse() {
        if (mob.tickCount < readyAt || underSky(mob.level(), mob.blockPosition()) == outdoors) return false;

        for (int i = 0; i < tries; i++) {
            Vec3 spot = LandRandomPos.getPos(mob, across, upDown);
            if (spot == null) spot = DefaultRandomPos.getPos(mob, across, upDown);
            if (spot != null && underSky(mob.level(), BlockPos.containing(spot)) == outdoors) {
                heading = spot;
                return true;
            }
        }
        readyAt = mob.tickCount + cooldown;
        return false;
    }

    @Override
    public void start() {
        if (!mob.getNavigation().moveTo(heading.x, heading.y, heading.z, speed)) {
            readyAt = mob.tickCount + cooldown;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.getNavigation().isDone() && !there();
    }

    @Override
    public void stop() {
        if (!there()) readyAt = mob.tickCount + cooldown;
        mob.getNavigation().stop();
    }

    private boolean there() {
        return heading != null && mob.position().distanceToSqr(heading) <= arrived * arrived;
    }

    /** Lit by the whole of the sky, which is what Java's own question about the open sky asks. */
    private static boolean underSky(net.minecraft.world.level.Level level, BlockPos at) {
        return level.canSeeSky(at);
    }
}
