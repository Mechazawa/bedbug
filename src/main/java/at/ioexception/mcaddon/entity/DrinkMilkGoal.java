package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * `minecraft:behavior.drink_milk`, which clears what is harming the entity.
 *
 * The drink is over in the tick it starts. `cooldown_seconds` holds the goal back after each one and
 * `filters` decide whether it may start. The goal holds only the controls `control_flags` names, so
 * its `priority` matters against goals that hold one of those and against nothing else.
 */
public final class DrinkMilkGoal extends Goal {
    private final AddonEntity mob;
    private final Filter filters;
    private final int cooldown;
    private int readyAt;

    public DrinkMilkGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.filters = Goals.filter(component, "filters");
        this.cooldown = Goals.ticks(component, "cooldown_seconds", 5);
        setFlags(Goals.controls(component));
    }

    @Override
    public boolean canUse() {
        return mob.tickCount >= readyAt && !mob.getActiveEffects().isEmpty()
            && filters.matches(new Filter.Context(mob, mob, mob.getTarget(), ServerHooks.state()));
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        readyAt = mob.tickCount + cooldown;
        mob.getActiveEffects().stream()
            .filter(effect -> !Platform.beneficial(effect))
            .toList()
            .forEach(effect -> mob.removeEffect(effect.getEffect()));
    }
}
