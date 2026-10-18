package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Runs a weapon into a target and then backs off to come round again.
 *
 * `minecraft:behavior.use_kinetic_weapon` is a lance: the damage comes from the closing speed, so the
 * entity charges, connects, then retreats to the distance the pack named and waits out the cooldown
 * before the next pass. Java has nothing of the kind, so all of it is here. A mount carries the
 * entity while it charges, and the charge moves the mount when the pack asked for that.
 */
public final class KineticWeaponGoal extends Goal {
    private final AddonEntity mob;
    private final double approachDistance;
    private final double repositionLow;
    private final double repositionHigh;
    private final double repositionSpeed;
    private final double cooldownLow;
    private final double cooldownHigh;
    private final double cooldownSpeed;
    private final int cooldownTicks;
    private final double speed;
    private final double reachMultiplier;
    private final double minSpeed;
    private final boolean tracksTarget;
    private final boolean attacksOnce;
    private final boolean movesTheMount;
    private final String onAttack;
    private double backingOffTo;
    private int readyAt;
    private boolean spent;

    public KineticWeaponGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.approachDistance = Goals.number(component, "approach_distance", 8);
        this.repositionLow = Goals.low(component, "reposition_distance", 8);
        this.repositionHigh = Goals.high(component, "reposition_distance", 8);
        this.repositionSpeed = Goals.number(component, "reposition_speed_multiplier", 1);
        this.cooldownLow = Goals.low(component, "cooldown_distance", 8);
        this.cooldownHigh = Goals.high(component, "cooldown_distance", 8);
        this.cooldownSpeed = Goals.number(component, "cooldown_speed_multiplier", 1);
        this.cooldownTicks = Goals.ticks(component, "cooldown_time", 1);
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.reachMultiplier = Goals.number(component, "weapon_reach_multiplier", 1);
        this.minSpeed = Goals.number(component, "weapon_min_speed_multiplier", 0);
        this.tracksTarget = Goals.flag(component, "track_target", true);
        this.attacksOnce = Goals.flag(component, "attack_once", false);
        this.movesTheMount = Goals.flag(component, "hijack_mount_navigation", false);
        this.onAttack = Goals.event(component, "on_attack");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (spent || mob.tickCount < readyAt) return false;

        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.distanceTo(target) <= approachDistance;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && (tracksTarget || backingOffTo > 0);
    }

    @Override
    public void start() {
        backingOffTo = 0;
    }

    @Override
    public void stop() {
        backingOffTo = 0;
        rider().getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;

        mob.getLookControl().setLookAt(target, 30f, 30f);
        if (backingOffTo > 0) {
            retreat(target);
            return;
        }

        charge(target);
    }

    /** Closes on the target and hits it once the weapon reaches, if the charge is fast enough. */
    private void charge(LivingEntity target) {
        rider().getNavigation().moveTo(target, speed);
        double reaching = mob.getBbWidth() + target.getBbWidth() + reachMultiplier;
        if (mob.distanceTo(target) > reaching || carryingSpeed() < minSpeed) return;

        hit(target);
        spent = attacksOnce;
        backingOffTo = repositionLow + mob.getRandom().nextDouble()
            * Math.max(0, repositionHigh - repositionLow);
    }

    /** Walks back out to the distance the pack named, then waits out the cooldown there. */
    private void retreat(LivingEntity target) {
        if (mob.distanceTo(target) < backingOffTo) {
            Vec3 away = mob.position().subtract(target.position()).normalize().scale(backingOffTo);
            Vec3 to = mob.position().add(away);
            rider().getNavigation().moveTo(to.x, to.y, to.z, repositionSpeed);
            return;
        }

        rider().getNavigation().moveTo(target, cooldownSpeed);
        backingOffTo = 0;
        readyAt = mob.tickCount + cooldownTicks;
        holdAt(target);
    }

    /** How far out the entity waits between passes, which the cooldown distance states as a range. */
    private void holdAt(LivingEntity target) {
        double waiting = cooldownLow + mob.getRandom().nextDouble()
            * Math.max(0, cooldownHigh - cooldownLow);
        if (mob.distanceTo(target) >= waiting) rider().getNavigation().stop();
    }

    private void hit(LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        Platform.hurt(target, level, mob.damageSources().mobAttack(mob),
            (float) (mob.getAttributeValue(Attributes.ATTACK_DAMAGE) * carryingSpeed()));
        if (onAttack != null) EntityEvents.trigger(mob, onAttack, target, ServerHooks.state());
    }

    /** How fast the entity is actually travelling, as a multiple of one block a tick. */
    private double carryingSpeed() {
        Entity moving = mob.isPassenger() ? mob.getVehicle() : mob;
        return moving == null ? 0 : moving.getDeltaMovement().horizontalDistance() * 10;
    }

    /**
     * Whose feet do the walking.
     *
     * A mounted entity has no route of its own: `hijack_mount_navigation` is the pack saying that
     * this goal steers the mount rather than the rider, so that is whose navigator is given the
     * destination.
     */
    private net.minecraft.world.entity.Mob rider() {
        return movesTheMount && mob.getVehicle() instanceof net.minecraft.world.entity.Mob mount
            ? mount : mob;
    }
}
