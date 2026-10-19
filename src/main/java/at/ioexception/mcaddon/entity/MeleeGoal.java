package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Closes on the target and hits it, at the reach and the pace the pack's melee behaviour states.
 *
 * Java's goal does the chasing, and Bedrock's four melee behaviours chase the same way. What they
 * state about the blow itself is answered here: the wait between blows, the reach, the field of
 * view the target has to be in, the events a blow and a kill raise, and the fire a burning
 * attacker passes on. `melee_box_attack` reaches as far as its own box grown sideways by
 * `horizontal_reach`; the others reach `reach_multiplier` times their width, which is the distance
 * Java's own goal used before it turned to boxes. `delayed_attack` swings for `attack_duration`
 * seconds, which is also its wait between blows, and lands the blow `hit_delay_pct` of the way
 * through. `stomp_attack` hurts only a target within `stomp_range_multiplier` times its width, and
 * a stomp that falls short within `no_damage_range_multiplier` times that range still costs it its
 * wait.
 */
public final class MeleeGoal extends MeleeAttackGoal {
    /** Which of Bedrock's melee behaviours this is, which decides how the blow lands. */
    public enum Kind { PLAIN, BOX, DELAYED, STOMP }

    private final AddonEntity attacker;
    private final Kind kind;
    private final int cooldown;
    private final int hitDelay;
    private final double stompMultiplier;
    private final double missMultiplier;
    private LivingEntity pending;
    private int pendingAt;
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

    public MeleeGoal(AddonEntity attacker, JsonObject component, Kind kind) {
        super(attacker, Goals.number(component, "speed_multiplier", 1),
            Goals.flag(component, "track_target", false));
        this.attacker = attacker;
        this.kind = kind;
        this.cooldown = kind == Kind.DELAYED ? Goals.ticks(component, "attack_duration", 0.75)
            : Goals.ticks(component, "cooldown_time", 1);
        this.hitDelay = kind == Kind.DELAYED
            ? (int) Math.round(cooldown * Goals.number(component, "hit_delay_pct", 0.5)) : 0;
        this.stompMultiplier = Goals.number(component, "stomp_range_multiplier", 2);
        this.missMultiplier = Goals.number(component, "no_damage_range_multiplier", 2);
        this.boxReach = kind == Kind.BOX ? Goals.number(component, "horizontal_reach", DEFAULT_BOX_REACH) : null;
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

    @Override
    public void tick() {
        super.tick();
        if (pending == null || attacker.tickCount < pendingAt) return;

        LivingEntity struck = pending;
        pending = null;
        if (struck.isAlive() && inReach(struck)) land(struck);
    }

    @Override
    public void stop() {
        super.stop();
        pending = null;
    }

    //? if >=1.21 {
    @Override
    protected void checkAndPerformAttack(LivingEntity target) {
    //?} else {
    /*@Override
    protected void checkAndPerformAttack(LivingEntity target, double distanceSqr) {
    *///?}
        if (attacker.tickCount < readyAt || !inReach(target) || !Goals.sees(attacker, target, halfFieldOfView)
            || !attacker.getSensing().hasLineOfSight(target)) {
            return;
        }
        double stomp = attacker.getBbWidth() * stompMultiplier;
        double away = attacker.distanceTo(target);
        if (kind == Kind.STOMP && away > stomp * missMultiplier) return;

        readyAt = attacker.tickCount + cooldown;
        Platform.swing(attacker, InteractionHand.MAIN_HAND, true);
        if (kind == Kind.STOMP && away > stomp) return;
        if (hitDelay > 0) {
            pending = target;
            pendingAt = attacker.tickCount + hitDelay;
            return;
        }
        land(target);
    }

    private void land(LivingEntity target) {
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

}
