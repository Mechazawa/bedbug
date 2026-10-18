package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Sits down and looks in a few directions before standing up again.
 *
 * `minecraft:behavior.random_look_around_and_sit` makes a number of looks, each held for a number of
 * ticks and each aimed inside a window of angles measured from where the entity faced when it began.
 * Nothing else starts it for `random_look_around_cooldown` seconds afterwards. A leash ends it
 * unless `continue_if_leashed` says it may go on.
 */
public final class LookAroundAndSitGoal extends Goal {
    private static final double LOOK_DISTANCE = 8;

    private final AddonEntity mob;
    private final double probability;
    private final int looksLow;
    private final int looksHigh;
    private final int timeLow;
    private final int timeHigh;
    private final double angleLow;
    private final double angleHigh;
    private final int cooldown;
    private final boolean continuesLeashed;
    private long readyAt;
    private float facing;
    private int looksLeft;
    private long lookUntil;
    private Vec3 looking;

    public LookAroundAndSitGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.probability = Goals.number(component, "probability", 0.02);
        this.looksLow = (int) Goals.number(component, "min_look_count", 1);
        this.looksHigh = (int) Goals.number(component, "max_look_count", 2);
        this.timeLow = (int) Goals.number(component, "min_look_time", 20);
        this.timeHigh = (int) Goals.number(component, "max_look_time", 40);
        this.angleLow = Goals.number(component, "min_angle_of_view_horizontal", -180);
        this.angleHigh = Goals.number(component, "max_angle_of_view_horizontal", 180);
        this.cooldown = Goals.ticks(component, "random_look_around_cooldown", 0);
        this.continuesLeashed = Goals.flag(component, "continue_if_leashed", false);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() == null && mob.onGround() && mob.level().getGameTime() >= readyAt
            && mob.getRandom().nextDouble() < probability;
    }

    @Override
    public boolean canContinueToUse() {
        return (looksLeft > 0 || mob.level().getGameTime() < lookUntil) && mob.getTarget() == null && (continuesLeashed || !mob.isLeashed());
    }

    @Override
    public void start() {
        facing = mob.getYRot();
        looksLeft = Goals.between(mob.getRandom(), looksLow, looksHigh);
        lookUntil = 0;
        mob.getNavigation().stop();
        mob.setPose(Pose.SITTING);
    }

    @Override
    public void tick() {
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
        long now = mob.level().getGameTime();
        if (now >= lookUntil && looksLeft > 0) {
            double angle = Math.toRadians(facing + angleLow + mob.getRandom().nextDouble() * (angleHigh - angleLow));
            looking = mob.getEyePosition().add(-Math.sin(angle) * LOOK_DISTANCE, 0, Math.cos(angle) * LOOK_DISTANCE);
            lookUntil = now + Goals.between(mob.getRandom(), timeLow, timeHigh);
            looksLeft--;
        }
        mob.getLookControl().setLookAt(looking.x, looking.y, looking.z, 30f, 30f);
    }

    @Override
    public void stop() {
        looksLeft = 0;
        lookUntil = 0;
        mob.setPose(Pose.STANDING);
        readyAt = mob.level().getGameTime() + cooldown;
    }
}
