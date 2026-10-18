package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Stalks a target and strikes it, `minecraft:behavior.ocelotattack`.
 *
 * Java's own ocelot goal with Bedrock's numbers in place of its constants, which are Bedrock's
 * defaults: it sneaks up from `max_sneak_range` blocks, sprints the last `max_sprint_range`, walks
 * once within reach, strikes within `reach_multiplier` times its width every `cooldown_time` seconds,
 * and gives up past `max_distance`.
 */
public final class OcelotGoal extends Goal {
    private final AddonEntity mob;
    private final double reach;
    private final double sprintRange;
    private final double sneakRange;
    private final double furthest;
    private final double walk;
    private final double sprint;
    private final double sneak;
    private final int cooldown;
    private final float turn;
    private final float headTurn;
    private LivingEntity target;
    private int untilBlow;

    public OcelotGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.reach = Goals.number(goal, "reach_multiplier", 2);
        this.sprintRange = Goals.number(goal, "max_sprint_range", 4);
        this.sneakRange = Goals.number(goal, "max_sneak_range", 15);
        this.furthest = Goals.number(goal, "max_distance", 15);
        this.walk = Goals.number(goal, "walk_speed_multiplier", 0.8);
        this.sprint = Goals.number(goal, "sprint_speed_multiplier", 1.33);
        this.sneak = Goals.number(goal, "sneak_speed_multiplier", 0.6);
        this.cooldown = Goals.ticks(goal, "cooldown_time", 1);
        this.turn = (float) Goals.number(goal, "x_max_rotation", 30);
        this.headTurn = (float) Goals.number(goal, "y_max_head_rotation", 30);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity hunted = mob.getTarget();
        if (hunted == null || mob.distanceTo(hunted) > furthest) return false;

        target = hunted;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return target.isAlive() && mob.distanceTo(target) <= furthest
            && (!mob.getNavigation().isDone() || canUse());
    }

    @Override
    public void stop() {
        target = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(target, headTurn, turn);
        double within = mob.getBbWidth() * reach;
        double distance = mob.distanceTo(target);
        double speed = distance > within && distance < sprintRange ? sprint
            : distance > within && distance < sneakRange ? sneak : walk;
        mob.getNavigation().moveTo(target, speed);

        untilBlow = Math.max(untilBlow - 1, 0);
        if (distance > within || untilBlow > 0) return;

        untilBlow = cooldown;
        //? if >=26 {
        mob.doHurtTarget(getServerLevel(mob), target);
        //?} else {
        /*mob.doHurtTarget(target);
        *///?}
    }
}
