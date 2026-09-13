package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Goes limp after a bad hit and lies there until the danger has passed.
 *
 * `minecraft:behavior.play_dead` is what keeps a Bedrock axolotl alive out of water. The trigger is
 * the entity's health dropping, which the goal watches itself, because Java's damage hooks are spelt
 * differently on each version this runs on and the health the entity is left with is not.
 */
public final class PlayDeadGoal extends Goal {
    private final AddonEntity mob;
    private final int duration;
    private final double belowHealth;
    private final double startChance;
    private final Filter conditions;
    private final boolean regenerates;
    private float healthLastTick = -1;
    private int played;

    public PlayDeadGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.duration = Goals.ticks(component, "duration", 1);
        this.belowHealth = Goals.number(component, "force_below_health", 0);
        this.startChance = Goals.number(component, "random_start_chance", 1);
        this.conditions = Goals.filter(component, "filters");
        this.regenerates = Goals.flag(component, "apply_regeneration", false);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        float health = mob.getHealth();
        boolean hurt = healthLastTick >= 0 && health < healthLastTick;
        healthLastTick = health;

        if (!hurt || health <= 0) return false;
        if (belowHealth > 0 && health > belowHealth) return false;
        if (mob.getRandom().nextDouble() > startChance) return false;

        return conditions.matches(new Filter.Context(mob, mob, mob.getTarget(), ServerHooks.state()));
    }

    @Override
    public boolean canContinueToUse() {
        return played < duration && mob.getHealth() > 0;
    }

    @Override
    public void start() {
        played = 0;
        mob.setPose(Pose.SWIMMING);
        mob.setTarget(null);
        mob.getNavigation().stop();
        if (regenerates) {
            Platform.addEffect(mob, Identifier.tryParse("minecraft:regeneration"), duration, 0, true);
        }
    }

    @Override
    public void tick() {
        played++;
        mob.getNavigation().stop();
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
    }

    @Override
    public void stop() {
        mob.setPose(Pose.STANDING);
        healthLastTick = mob.getHealth();
    }
}
