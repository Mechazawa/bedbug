package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Shoots at the target from range, `minecraft:behavior.ranged_attack`.
 *
 * Without a `charge_shoot_trigger` this is Java's own ranged goal: the reload runs from the low end of
 * `attack_interval` to the high end as the target stands further into the range. With one, the
 * entity charges while it can see its target and loses charge while it cannot; it counts as charged
 * from `charge_charged_trigger` plus `charge_shoot_trigger` seconds, and at twice
 * `charge_shoot_trigger` it fires `burst_shots` shots `burst_interval` apart and starts again from
 * nothing. That is the blaze's eight second cycle and the ghast's four.
 */
public final class RangedGoal extends Goal {
    private final AddonEntity mob;
    private final double speed;
    private final int fastest;
    private final int slowest;
    private final double reach;
    private final double closest;
    private final int sightNeeded;
    private final boolean swing;
    private final float turn;
    private final float headTurn;

    /** Ticks of charge at which the entity counts as charged, and at which it fires; zero for none. */
    private final int chargedAt;
    private final int firesAt;
    private final int burstShots;
    private final int burstGap;

    private LivingEntity target;
    private int seen;
    private int reload = -1;
    private int charge;
    private int burstLeft;
    private int untilBurstShot;
    private boolean charged;

    public RangedGoal(AddonEntity mob, JsonObject goal, double speed) {
        this.mob = mob;
        this.speed = speed;
        this.fastest = goal.has("attack_interval") ? Goals.ticksLow(goal, "attack_interval", 1)
            : Goals.ticks(goal, "attack_interval_min", 1);
        this.slowest = Math.max(fastest, goal.has("attack_interval") ? Goals.ticksHigh(goal, "attack_interval", 1)
            : Goals.ticks(goal, "attack_interval_max", 1));
        this.reach = goal.has("attack_range") ? Goals.high(goal, "attack_range", 16)
            : Goals.number(goal, "attack_radius", 16);
        this.closest = goal.has("attack_range") ? Goals.low(goal, "attack_range", 0)
            : Goals.number(goal, "attack_radius_min", 0);
        this.sightNeeded = Goals.ticks(goal, "target_in_sight_time", 1);
        this.swing = Goals.flag(goal, "swing", false);
        this.turn = (float) Goals.number(goal, "x_max_rotation", 30);
        this.headTurn = (float) Goals.number(goal, "y_max_head_rotation", 30);

        int shootTrigger = Goals.ticks(goal, "charge_shoot_trigger", 0);
        this.firesAt = 2 * shootTrigger;
        this.chargedAt = Goals.ticks(goal, "charge_charged_trigger", 0) + shootTrigger;
        this.burstShots = Math.max(1, (int) Goals.number(goal, "burst_shots", 1));
        this.burstGap = Goals.ticks(goal, "burst_interval", 0);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity hunted = mob.getTarget();
        if (hunted == null || !hunted.isAlive()) return false;

        target = hunted;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() || target.isAlive() && !mob.getNavigation().isDone();
    }

    @Override
    public void stop() {
        target = null;
        seen = 0;
        reload = -1;
        charge = 0;
        burstLeft = 0;
        charged(false);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        double distance = mob.distanceTo(target);
        boolean sees = mob.getSensing().hasLineOfSight(target);
        seen = sees ? seen + 1 : 0;
        if (distance <= reach && seen >= sightNeeded) {
            mob.getNavigation().stop();
        } else {
            mob.getNavigation().moveTo(target, speed);
        }
        mob.getLookControl().setLookAt(target, headTurn, turn);

        boolean inRange = distance >= closest && distance <= reach;
        if (firesAt > 0) {
            tickCharge(sees, inRange);
        } else if (--reload == 0) {
            if (!sees || !inRange) return;

            shoot((float) (distance / reach));
            reload = (int) Math.floor(distance / reach * (slowest - fastest) + fastest);
        } else if (reload < 0) {
            reload = (int) Math.floor(fastest + distance / reach * (slowest - fastest));
        }
    }

    private void tickCharge(boolean sees, boolean inRange) {
        if (burstLeft > 0) {
            if (--untilBurstShot > 0) return;

            shoot(1f);
            burstLeft--;
            untilBurstShot = burstGap;
            return;
        }

        charge = sees && inRange ? charge + 1 : Math.max(0, charge - 1);
        charged(charge >= chargedAt);
        if (charge < firesAt) return;

        charge = 0;
        charged(false);
        burstLeft = burstShots;
        untilBurstShot = 0;
        tickCharge(sees, inRange);
    }

    /** What `query.is_charged` answers, written only on a change because each write goes to every client. */
    private void charged(boolean now) {
        if (now == charged) return;

        charged = now;
        mob.setValue("is_charged", now);
    }

    private void shoot(float pull) {
        mob.performRangedAttack(target, Math.max(0.1f, Math.min(1f, pull)));
        if (swing) mob.swing(InteractionHand.MAIN_HAND);
    }
}
