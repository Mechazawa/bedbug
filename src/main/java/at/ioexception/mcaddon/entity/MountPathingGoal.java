package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * `minecraft:behavior.mount_pathing`: a mount carrying a mob walks to that rider's target.
 *
 * Java walks a mob-ridden mount only through a goal of the rider's that moves it, because
 * `Mob.updateControlFlags` switches the mount's own moving goals off. This goal holds only the
 * controls `control_flags` names, none by default, so it runs while the mount is ridden. It sets the
 * mount's speed every tick, ahead of any goal of the rider's, and stops the mount inside
 * `target_dist`. Without `track_target` the walk ends at the place the target stood as it began.
 */
public final class MountPathingGoal extends Goal {
    /** How often the walk to a moving target is planned again, the pace of Java's melee goal. */
    private static final int REPATH_TICKS = 10;

    private final AddonEntity mount;
    private final double speed;
    private final double keepAway;
    private final boolean tracking;
    private LivingEntity target;
    private Vec3 aimedAt;
    private int replanAt;

    public MountPathingGoal(AddonEntity mount, JsonObject component, double speed) {
        this.mount = mount;
        this.speed = speed;
        this.keepAway = Goals.number(component, "target_dist", 0);
        this.tracking = Goals.flag(component, "track_target", false);
        setFlags(Goals.controls(component));
    }

    @Override
    public boolean canUse() {
        target = ridersTarget();
        return target != null && mount.distanceTo(target) > keepAway;
    }

    private LivingEntity ridersTarget() {
        if (!(mount.getControllingPassenger() instanceof Mob rider)) return null;

        LivingEntity aimed = rider.getTarget();
        return aimed != null && aimed.isAlive() ? aimed : null;
    }

    @Override
    public boolean canContinueToUse() {
        if (ridersTarget() != target) return false;
        if (tracking) return true;

        return !mount.getNavigation().isDone();
    }

    @Override
    public void start() {
        aimedAt = target.position();
        mount.getNavigation().moveTo(aimedAt.x, aimedAt.y, aimedAt.z, speed);
        replanAt = mount.tickCount + REPATH_TICKS;
    }

    @Override
    public void stop() {
        target = null;
        mount.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (mount.distanceTo(target) <= keepAway) {
            mount.getNavigation().stop();
            return;
        }
        if (tracking && (mount.tickCount >= replanAt || mount.getNavigation().isDone())) {
            mount.getNavigation().moveTo(target, speed);
            replanAt = mount.tickCount + REPATH_TICKS;
        }
        mount.getNavigation().setSpeedModifier(speed);
    }
}
