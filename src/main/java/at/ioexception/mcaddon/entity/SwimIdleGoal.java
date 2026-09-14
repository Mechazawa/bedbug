package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Stops swimming and drifts for a while.
 *
 * `minecraft:behavior.swim_idle` is what keeps a fish from crossing the ocean in a straight line.
 * Java's own swimming goal has no pause in it, so the pause is the goal here: while it runs the
 * entity keeps only the movement the water gives it.
 */
public final class SwimIdleGoal extends Goal {
    private final AddonEntity mob;
    private final int idleTime;
    private final double successRate;
    private int idled;

    public SwimIdleGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.idleTime = Goals.ticks(component, "idle_time", 5);
        this.successRate = Goals.number(component, "success_rate", 0.1);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return mob.isInWater() && mob.getRandom().nextDouble() < successRate;
    }

    @Override
    public boolean canContinueToUse() {
        return idled < idleTime && mob.isInWater();
    }

    @Override
    public void start() {
        idled = 0;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        idled++;
        mob.setDeltaMovement(0, mob.getDeltaMovement().y * 0.5, 0);
    }
}
