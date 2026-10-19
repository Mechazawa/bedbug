package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Stops and settles for a while, which is what sitting and lying down both come to.
 *
 * Java gives each of its own mobs its own sitting goal, tied to an owner or to a bed. A pack's
 * entity has neither, so what carries across is the part a player sees: it stays where it is, in
 * the pose the pack asked for, until it gets up again. Each Bedrock goal states when it starts and
 * stops differently, so the chances, the least time down and the wait before the next go in as
 * numbers and the two factories read them from the fields their component carries.
 */
public final class RestGoal extends Goal {
    private final Mob mob;
    private final Pose pose;
    private final double startChance;
    private final double stopChance;
    private final int leastHeld;
    private final int cooldown;
    private long readyAt;
    private long leastUntil;
    private boolean done;

    private RestGoal(Mob mob, Pose pose, double startChance, double stopChance, int leastHeld, int cooldown) {
        this.mob = mob;
        this.pose = pose;
        this.startChance = startChance;
        this.stopChance = stopChance;
        this.leastHeld = leastHeld;
        this.cooldown = cooldown;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    /**
     * `minecraft:behavior.random_sitting`, which sits down on a chance, stays for at least
     * `min_sit_time` and then gets up on a chance each tick, and waits `cooldown` seconds to sit again.
     */
    public static RestGoal sitting(Mob mob, JsonObject component) {
        return new RestGoal(mob, Pose.SITTING, Goals.number(component, "start_chance", 0.1),
            Goals.number(component, "stop_chance", 0.3), Goals.ticks(component, "min_sit_time", 10),
            Goals.ticks(component, "cooldown", 100));
    }

    /**
     * `minecraft:behavior.lay_down`, whose two intervals are each a one in that many chance every
     * tick, to lie down and to get up.
     */
    public static RestGoal lyingDown(Mob mob, JsonObject component) {
        return new RestGoal(mob, Pose.SLEEPING, 1 / Math.max(1, Goals.number(component, "interval", 120)),
            1 / Math.max(1, Goals.number(component, "random_stop_interval", 120)), 0, 0);
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() == null && mob.onGround() && mob.level().getGameTime() >= readyAt
            && mob.getRandom().nextDouble() < startChance;
    }

    @Override
    public boolean canContinueToUse() {
        return !done && mob.getTarget() == null;
    }

    @Override
    public void start() {
        leastUntil = mob.level().getGameTime() + leastHeld;
        done = false;
        mob.getNavigation().stop();
        mob.setPose(pose);
        resting(true);
    }

    @Override
    public void tick() {
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
        done = mob.level().getGameTime() >= leastUntil && mob.getRandom().nextDouble() < stopChance;
    }

    @Override
    public void stop() {
        mob.setPose(Pose.STANDING);
        readyAt = mob.level().getGameTime() + cooldown;
        resting(false);
    }

    /** A pack's controller reads the settling back as `q.is_resting`. */
    private void resting(boolean still) {
        if (mob instanceof AddonEntity addon) addon.setValue("is_resting", still);
    }
}
