package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Closes on the target and hits it, at the reach and the pace the pack's melee behaviour states.
 *
 * Java's goal does the chasing, and Bedrock's four melee behaviours chase the same way. What they
 * state about the blow itself is answered here: the wait between blows, the reach, the field of
 * view the target has to be in, the events a blow and a kill raise, and the fire a burning
 * attacker passes on. `melee_box_attack` reaches as far as its own box grown sideways by
 * `horizontal_reach`; the others reach `reach_multiplier` times their width, which is the distance
 * Java's own goal used before it turned to boxes.
 */
public final class MeleeGoal extends MeleeAttackGoal {
    private final AddonEntity attacker;
    private final int cooldown;
    private final Double boxReach;
    private final double reachMultiplier;
    private final double halfFieldOfView;
    private final boolean once;
    private final boolean spreadsFire;
    private final boolean completePath;
    private final int stopChance;
    private final String onAttack;
    private final String onKill;
    private int readyAt;
    private boolean spent;

    public MeleeGoal(AddonEntity attacker, JsonObject component, boolean boxed) {
        super(attacker, Goals.number(component, "speed_multiplier", 1),
            Goals.flag(component, "track_target", false));
        this.attacker = attacker;
        this.cooldown = Goals.ticks(component, "cooldown_time", 1);
        this.boxReach = boxed ? Goals.number(component, "horizontal_reach", DEFAULT_BOX_REACH) : null;
        this.reachMultiplier = Goals.number(component, "reach_multiplier", 2);
        this.halfFieldOfView = Goals.number(component, "melee_fov", 90) / 2;
        this.once = Goals.flag(component, "attack_once", false);
        this.spreadsFire = Goals.flag(component, "can_spread_on_fire", false);
        this.completePath = Goals.flag(component, "require_complete_path", false);
        this.stopChance = (int) Goals.number(component, "random_stop_interval", 0);
        this.onAttack = Goals.event(component, "on_attack");
        this.onKill = Goals.event(component, "on_kill");
    }

    /** Bedrock's default sideways growth of the attacker's box, as its schema states it. */
    private static final double DEFAULT_BOX_REACH = 0.8;

    @Override
    public boolean canUse() {
        if (spent || !super.canUse()) return false;
        if (!completePath) return true;

        Path path = attacker.getNavigation().createPath(attacker.getTarget(), 0);
        return path != null && path.canReach();
    }

    @Override
    public boolean canContinueToUse() {
        if (spent) return false;
        if (stopChance > 0 && attacker.getRandom().nextInt(stopChance) == 0) return false;
        return super.canContinueToUse();
    }

    //? if >=1.21 {
    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
    //?} else {
    /*@Override
    protected void checkAndPerformAttack(LivingEntity target, double distanceSqr) {
    *///?}
        if (attacker.tickCount < readyAt || !inReach(target) || !inView(target)
            || !attacker.getSensing().hasLineOfSight(target)) {
            return;
        }

        readyAt = attacker.tickCount + cooldown;
        attacker.swing(InteractionHand.MAIN_HAND);
        //? if >=26 {
        boolean hit = attacker.doHurtTarget(getServerLevel(attacker), target);
        //?} else {
        /*boolean hit = attacker.doHurtTarget(target);
        *///?}
        if (!hit) return;

        spent = once;
        if (spreadsFire && attacker.isOnFire() && attacker.getMainHandItem().isEmpty()) {
            // Java's burning zombie passes on two seconds of fire for each step of the difficulty.
            target.setRemainingFireTicks(Math.max(target.getRemainingFireTicks(),
                2 * attacker.level().getDifficulty().getId() * Goals.TICKS_PER_SECOND));
        }
        if (onAttack != null) EntityEvents.trigger(attacker, onAttack, target, ServerHooks.state());
        if (onKill != null && !target.isAlive()) {
            EntityEvents.trigger(attacker, onKill, target, ServerHooks.state());
        }
    }

    private boolean inReach(LivingEntity target) {
        if (boxReach != null) {
            return attacker.getBoundingBox().inflate(boxReach, 0, boxReach).intersects(target.getBoundingBox());
        }
        double reach = attacker.getBbWidth() * reachMultiplier;
        return attacker.distanceToSqr(target) <= reach * reach + target.getBbWidth();
    }

    /** Whether the target stands inside `melee_fov`, measured across the ground from where the head faces. */
    private boolean inView(LivingEntity target) {
        if (halfFieldOfView >= 180) return true;

        Vec3 towards = new Vec3(target.getX() - attacker.getX(), 0, target.getZ() - attacker.getZ());
        if (towards.lengthSqr() < 1.0E-6) return true;

        double facing = Math.toRadians(attacker.getYHeadRot());
        Vec3 ahead = new Vec3(-Math.sin(facing), 0, Math.cos(facing));
        double cosine = ahead.dot(towards.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine)))) <= halfFieldOfView;
    }
}
