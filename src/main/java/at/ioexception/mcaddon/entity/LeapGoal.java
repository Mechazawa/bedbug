package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Leaps at a target two to four blocks off, `minecraft:behavior.leap_at_target`.
 *
 * Java's leap goal, which takes off only from the ground; under `must_be_on_ground` set false it
 * leaps from the air as well, the way a cave spider does. Mojang documents `yd` as a height, but
 * every number its own entities write is the upward speed Java's goal takes for the same animal:
 * four tenths for the wolf, the cat and the spider. The ocelot writes its three tenths as
 * `target_dist`, an older name for the same speed.
 */
public final class LeapGoal extends Goal {
    private static final double NEAREST_SQUARED = 4;
    private static final double FURTHEST_SQUARED = 16;
    private static final double REACH = 0.4;
    private static final double KEPT = 0.2;

    private final AddonEntity mob;
    private final double rise;
    private final boolean fromGround;
    private LivingEntity target;

    public LeapGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.rise = goal.has("yd") ? Goals.number(goal, "yd", 0) : Goals.number(goal, "target_dist", 0);
        this.fromGround = Goals.flag(goal, "must_be_on_ground", true);
        setFlags(EnumSet.of(Goal.Flag.JUMP, Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.hasControllingPassenger()) return false;

        target = mob.getTarget();
        if (target == null) return false;

        double distance = mob.distanceToSqr(target);
        return distance >= NEAREST_SQUARED && distance <= FURTHEST_SQUARED
            && (mob.onGround() || !fromGround)
            && mob.getRandom().nextInt(reducedTickDelay(5)) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.onGround();
    }

    @Override
    public void start() {
        Vec3 was = mob.getDeltaMovement();
        Vec3 toward = new Vec3(target.getX() - mob.getX(), 0, target.getZ() - mob.getZ());
        if (toward.lengthSqr() > 1.0E-7) toward = toward.normalize().scale(REACH).add(was.scale(KEPT));
        mob.setDeltaMovement(toward.x, rise, toward.z);
    }
}
