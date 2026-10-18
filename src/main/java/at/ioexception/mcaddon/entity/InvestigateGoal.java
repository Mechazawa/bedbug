package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Goes to look at where something happened.
 *
 * `minecraft:behavior.investigate_suspicious_location` walks a warden to the position its suspicion
 * tracking marked. Java has no such tracking for a data-driven entity, so the place the entity was
 * last hurt from is what it goes to look at, which is the one disturbance every mob notices. The
 * speed and the distance that counts as arrived are the pack's own.
 */
public final class InvestigateGoal extends Goal {
    /** How long the walk may take before the entity gives up on reaching the place. */
    private static final int TRAVEL_LIMIT = 300;

    private final AddonEntity mob;
    private final double speed;
    private final double goalRadius;
    private BlockPos looking;
    private int travelled;

    public InvestigateGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.goalRadius = Goals.number(component, "goal_radius", 0.5);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null) return false;

        looking = mob.disturbance();
        return looking != null && mob.distanceToSqr(Vec3.atCenterOf(looking))
            > goalRadius * goalRadius;
    }

    @Override
    public boolean canContinueToUse() {
        return looking != null && travelled < TRAVEL_LIMIT && mob.getTarget() == null
            && mob.distanceToSqr(Vec3.atCenterOf(looking)) > goalRadius * goalRadius;
    }

    @Override
    public void start() {
        travelled = 0;
        Vec3 at = Vec3.atCenterOf(looking);
        mob.getNavigation().moveTo(at.x, at.y, at.z, speed);
    }

    @Override
    public void stop() {
        // The place has been looked at, so it stops being what the entity is curious about.
        mob.forgetDisturbance();
        looking = null;
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (looking == null) return;

        travelled++;
        Vec3 at = Vec3.atCenterOf(looking);
        mob.getLookControl().setLookAt(at.x, at.y, at.z);
        if (mob.getNavigation().isDone()) mob.getNavigation().moveTo(at.x, at.y, at.z, speed);
    }
}
