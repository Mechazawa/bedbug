package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Creeps up on a target and pounces on it, `minecraft:behavior.stalk_and_pounce_on_target`.
 *
 * It stalks at `stalk_speed` from `max_stalk_dist` blocks, stops within `pounce_max_dist` and stays
 * interested for `interest_time` seconds, then leaps: `leap_dist` along the way to the target and
 * `leap_height` up, the two speeds Java's fox pounces with. Landing within `strike_dist` of the
 * target strikes it; landing on what `stuck_blocks` names holds it stuck for `stuck_time` seconds,
 * the way a fox's head goes into snow.
 */
public final class PounceGoal extends Goal {
    private final AddonEntity mob;
    private final double stalkSpeed;
    private final double stalkRange;
    private final double pounceRange;
    private final double strikeRange;
    private final double leapReach;
    private final double leapHeight;
    private final int interest;
    private final int stuckFor;
    private final Filter sticky;
    private LivingEntity target;
    private int interested;
    /** Ticks since the leap, or -1 while the entity is not leaping. */
    private int flown = -1;
    private int stuck;

    public PounceGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.stalkSpeed = Goals.number(goal, "stalk_speed", 1.2);
        this.stalkRange = Goals.number(goal, "max_stalk_dist", 10);
        this.pounceRange = Goals.number(goal, "pounce_max_dist", 5);
        this.strikeRange = Goals.number(goal, "strike_dist", 2);
        this.leapReach = Goals.number(goal, "leap_dist", 0.8);
        this.leapHeight = Goals.number(goal, "leap_height", 0.9);
        this.interest = Goals.ticks(goal, "interest_time", 2);
        this.stuckFor = Goals.ticks(goal, "stuck_time", 2);
        this.sticky = goal.has("stuck_blocks") ? Goals.filter(goal, "stuck_blocks") : null;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity hunted = mob.getTarget();
        if (hunted == null || !hunted.isAlive() || mob.distanceTo(hunted) > stalkRange) return false;

        target = hunted;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return stuck > 0 || flown >= 0 || canUse();
    }

    @Override
    public void start() {
        interested = 0;
    }

    @Override
    public void stop() {
        target = null;
        flown = -1;
        stuck = 0;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (stuck > 0) {
            stuck--;
            mob.getNavigation().stop();
            mob.setDeltaMovement(Vec3.ZERO.add(0, mob.getDeltaMovement().y, 0));
            return;
        }
        if (flown >= 0) {
            // The leap leaves the ground on the tick after it is made.
            if (++flown < 2 || !mob.onGround()) return;

            flown = -1;
            land();
            return;
        }

        mob.getLookControl().setLookAt(target, 30, 30);
        if (mob.distanceTo(target) > pounceRange) {
            interested = 0;
            mob.getNavigation().moveTo(target, stalkSpeed);
            return;
        }

        mob.getNavigation().stop();
        if (++interested < interest || !mob.onGround()) return;

        Vec3 toward = new Vec3(target.getX() - mob.getX(), 0, target.getZ() - mob.getZ()).normalize();
        mob.setDeltaMovement(mob.getDeltaMovement().add(toward.x * leapReach, leapHeight, toward.z * leapReach));
        flown = 0;
        interested = 0;
    }

    private void land() {
        if (sticky != null && sticky.matches(new Filter.Context(mob, target, target, ServerHooks.state()))) {
            stuck = stuckFor;
            return;
        }
        if (target == null || mob.distanceTo(target) > strikeRange) return;

        //? if >=26 {
        mob.doHurtTarget(getServerLevel(mob), target);
        //?} else {
        /*mob.doHurtTarget(target);
        *///?}
    }
}
