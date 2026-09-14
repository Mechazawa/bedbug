package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Swells while something stands close, and goes off once it is full.
 *
 * `minecraft:behavior.swell` is the creeper's fuse. A pack draws the swelling off `q.swell_amount`,
 * which runs from nothing to one over the fuse, and the entity's own `minecraft:explode` decides
 * how large the blast is. `start_distance` is how close a target has to come for the fuse to catch;
 * `stop_distance` is how far it has to get for the fuse to burn back down.
 */
public final class SwellGoal extends Goal {
    /** How long the fuse burns, which is the creeper's own thirty ticks in every vanilla pack. */
    private static final int FUSE = 30;

    private final AddonEntity mob;
    private final double startDistance;
    private final double stopDistance;
    private int swelled;
    private LivingEntity watched;

    public SwellGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.startDistance = Goals.number(component, "start_distance", 10);
        this.stopDistance = Goals.number(component, "stop_distance", 2);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        watched = mob.getTarget();
        return swelled > 0 || (watched != null && mob.distanceTo(watched) < startDistance);
    }

    @Override
    public boolean canContinueToUse() {
        return swelled > 0;
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
        watched = mob.getTarget();
    }

    @Override
    public void tick() {
        watched = mob.getTarget();
        boolean nearby = watched != null && watched.isAlive()
            && mob.distanceTo(watched) <= stopDistance;

        swelled = Math.max(0, swelled + (nearby ? 1 : -1));
        mob.setValue("is_swelling", swelled > 0);
        mob.setValue("swell_amount", Math.min(1d, swelled / (double) FUSE));

        if (swelled >= FUSE) mob.detonate();
    }

    @Override
    public void stop() {
        swelled = 0;
        watched = null;
        mob.setValue("is_swelling", false);
        mob.setValue("swell_amount", 0d);
    }
}
