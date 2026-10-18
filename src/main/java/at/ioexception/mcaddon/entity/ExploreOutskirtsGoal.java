package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * `minecraft:behavior.explore_outskirts`, which walks a Bedrock villager out past the edge of its
 * village and looks round there.
 *
 * Java has no village object, so the village is the box round the beds, job sites and bells within
 * a village's reach of the entity. An entity inside that box grown by `dist_from_boundary` walks out
 * on the side of the village it stands on, to `explore_dist` past the edge at its own height. Once
 * there it waits a while between `min_wait_time` and `max_wait_time` divided by `timer_ratio`, then
 * walks to a point within `next_xz` and `next_y` of the last, until `max_wait_time` has gone by
 * since it first arrived. A walk taking longer than `max_travel_time` ends the goal.
 */
public final class ExploreOutskirtsGoal extends Goal {
    /** How often an entity outside the goal looks for a village edge, in ticks. */
    private static final int LOOK_EVERY = 20;

    private final AddonEntity mob;
    private final double speed;
    private final Vec3 boundary;
    private final double exploreDistance;
    private final double perimeter;
    private final double arrived;
    private final int travelTime;
    private final int exploreTime;
    private final int shortestWait;
    private final int longestWait;
    private final double ratio;
    private final int nextAcross;
    private final int nextUp;
    private Vec3 target;
    private int lookAt;
    private int travelUntil;
    private int exploreUntil;
    private int waitUntil;
    private boolean finished;

    public ExploreOutskirtsGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.speed = Goals.number(goal, "speed_multiplier", 1);
        this.boundary = BedrockUnits.blocks(goal.get("dist_from_boundary"), new Vec3(5, 0, 5));
        this.exploreDistance = Goals.number(goal, "explore_dist", 5);
        this.perimeter = Goals.number(goal, "min_perimeter", 1);
        double near = Goals.number(goal, "min_dist_from_target", 2.25);
        this.arrived = near * near;
        this.travelTime = Goals.ticks(goal, "max_travel_time", 60);
        this.exploreTime = Goals.ticks(goal, "max_wait_time", 0);
        int least = Goals.ticks(goal, "min_wait_time", 3);
        this.shortestWait = Math.min(least, exploreTime);
        this.longestWait = Math.max(least, exploreTime);
        this.ratio = Math.max(1, Goals.number(goal, "timer_ratio", 2));
        this.nextAcross = Math.max(0, (int) Goals.number(goal, "next_xz", 5));
        this.nextUp = Math.max(0, (int) Goals.number(goal, "next_y", 3));
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.tickCount < lookAt || !(mob.level() instanceof ServerLevel level)) return false;
        lookAt = mob.tickCount + LOOK_EVERY;

        AABB village = level.getPoiManager()
            .getInRange(type -> type.is(PoiTypeTags.VILLAGE), mob.blockPosition(), MoveToPoiGoal.SEARCH_RADIUS,
                PoiManager.Occupancy.ANY)
            .map(PoiRecord::getPos).map(AABB::new).reduce(AABB::minmax).orElse(null);
        if (village == null || 2 * (village.getXsize() + village.getZsize()) < perimeter) return false;
        if (!village.inflate(boundary.x, boundary.y, boundary.z).contains(mob.position())) return false;

        target = outskirts(village);
        return true;
    }

    @Override
    public void start() {
        finished = false;
        exploreUntil = -1;
        waitUntil = -1;
        walk();
    }

    @Override
    public boolean canContinueToUse() {
        return !finished;
    }

    @Override
    public void tick() {
        if (waitUntil >= 0) {
            if (mob.tickCount >= waitUntil) {
                target = target.add(offset(nextAcross), offset(nextUp), offset(nextAcross));
                waitUntil = -1;
                walk();
            }
            return;
        }

        if (mob.distanceToSqr(target) <= arrived) {
            if (exploreUntil < 0) exploreUntil = mob.tickCount + exploreTime;
            finished = mob.tickCount >= exploreUntil;
            waitUntil = mob.tickCount + (int) (Goals.between(mob.getRandom(), shortestWait, longestWait) / ratio);
            mob.getNavigation().stop();
        } else if (mob.tickCount > travelUntil) {
            finished = true;
        } else if (mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
    }

    private void walk() {
        travelUntil = mob.tickCount + travelTime;
        mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
    }

    /** `explore_dist` past the edge of the village on the side the entity stands. */
    private Vec3 outskirts(AABB village) {
        Vec3 middle = village.getCenter();
        double across = mob.getX() - middle.x;
        double along = mob.getZ() - middle.z;
        double x = mob.getX();
        double z = mob.getZ();
        if (Math.abs(across) >= Math.abs(along)) {
            x = across < 0 ? village.minX - exploreDistance : village.maxX + exploreDistance;
        } else {
            z = along < 0 ? village.minZ - exploreDistance : village.maxZ + exploreDistance;
        }
        return new Vec3(x, mob.getY(), z);
    }

    private int offset(int most) {
        return mob.getRandom().nextInt(2 * most + 1) - most;
    }
}
