package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * Climbs onto something else, which is how Bedrock puts a rider on a mount it did not spawn with.
 *
 * `minecraft:behavior.find_mount` is what a chicken jockey and a spider jockey use to look for the
 * thing they ride. Anything nearby that can still take a passenger counts as a mount, because what
 * the goal describes is the searching rather than a list of what may be ridden.
 */
public final class FindMountGoal extends Goal {
    private final AddonEntity mob;
    private final double withinRadius;
    private final double mountDistance;
    private final boolean avoidWater;
    private final boolean targetNeeded;
    private final int startDelay;
    private final int maxAttempts;
    private int waited;
    private int failed;
    private Mob mount;

    public FindMountGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.withinRadius = Math.max(1, Goals.number(component, "within_radius", 16));
        this.mountDistance = Goals.number(component, "mount_distance", -1);
        this.avoidWater = Goals.flag(component, "avoid_water", false);
        this.targetNeeded = Goals.flag(component, "target_needed", false);
        this.startDelay = Goals.ticks(component, "start_delay", 0);
        this.maxAttempts = (int) Goals.number(component, "max_failed_attempts", 20);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.isPassenger() || failed >= maxAttempts) return false;
        if (targetNeeded && mob.getTarget() == null) return false;

        mount = mob.level().getEntitiesOfClass(Mob.class,
                mob.getBoundingBox().inflate(withinRadius), FindMountGoal::rideable).stream()
            .filter(candidate -> candidate != mob)
            .filter(candidate -> !avoidWater || !candidate.isInWater())
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
        return mount != null;
    }

    @Override
    public boolean canContinueToUse() {
        return mount != null && mount.isAlive() && !mob.isPassenger();
    }

    @Override
    public void start() {
        waited = 0;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (mount == null) return;
        if (waited++ < startDelay) return;

        mob.getLookControl().setLookAt(mount, 30f, 30f);
        double reach = mountDistance < 0 ? mob.getBbWidth() + mount.getBbWidth() : mountDistance;
        if (mob.distanceTo(mount) > reach) {
            mob.getNavigation().moveTo(mount, 1);
            return;
        }
        if (!mob.startRiding(mount)) failed++;
    }

    /** Anything alive with a free seat, which is what a Bedrock mount amounts to. */
    private static boolean rideable(Mob candidate) {
        return candidate.isAlive() && candidate.getPassengers().isEmpty() && !candidate.isPassenger();
    }
}
