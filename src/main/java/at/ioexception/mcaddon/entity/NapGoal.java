package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Lies down while the pack's conditions hold and wakes for any mob that comes near.
 *
 * `minecraft:behavior.nap` is a fox's daytime sleep. What wakes it is a living entity inside a box
 * `mob_detect_dist` wide and `mob_detect_height` tall around it, unless `wake_mob_exceptions`
 * accepts that entity. After waking it waits somewhere between `cooldown_min` and `cooldown_max`
 * seconds before it naps again.
 */
public final class NapGoal extends Goal {
    private final AddonEntity mob;
    private final Filter conditions;
    private final Filter exceptions;
    private final double detectDistance;
    private final double detectHeight;
    private final int cooldownLow;
    private final int cooldownHigh;
    private long readyAt;

    public NapGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.conditions = Goals.filter(component, "can_nap_filters");
        this.exceptions = component.has("wake_mob_exceptions") ? Goals.filter(component, "wake_mob_exceptions") : null;
        this.detectDistance = Goals.number(component, "mob_detect_dist", 6);
        this.detectHeight = Goals.number(component, "mob_detect_height", 6);
        this.cooldownLow = Goals.ticks(component, "cooldown_min", 0);
        this.cooldownHigh = Goals.ticks(component, "cooldown_max", 0);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() == null && mob.onGround() && mob.level().getGameTime() >= readyAt
            && mayNap() && !disturbed();
    }

    @Override
    public boolean canContinueToUse() {
        return mob.getTarget() == null && mayNap() && !disturbed();
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
        mob.setPose(Pose.SLEEPING);
        mob.setValue("is_resting", true);
    }

    @Override
    public void tick() {
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
    }

    @Override
    public void stop() {
        mob.setPose(Pose.STANDING);
        mob.setValue("is_resting", false);
        readyAt = mob.level().getGameTime() + Goals.between(mob.getRandom(), cooldownLow, cooldownHigh);
    }

    private boolean mayNap() {
        return conditions.matches(new Filter.Context(mob, mob, mob.getTarget(), ServerHooks.state()));
    }

    private boolean disturbed() {
        return mob.level().getEntitiesOfClass(LivingEntity.class,
                mob.getBoundingBox().inflate(detectDistance, detectHeight, detectDistance))
            .stream()
            .filter(nearby -> nearby != mob && nearby.isAlive())
            .anyMatch(nearby -> exceptions == null
                || !exceptions.matches(new Filter.Context(mob, nearby, nearby, ServerHooks.state())));
    }
}
