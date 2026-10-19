package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.TargetSelection;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;

import java.util.EnumSet;

/**
 * Turns on whatever pushes into the entity, `minecraft:behavior.target_when_pushed`.
 *
 * Each push from an entity its `entity_types` accept makes it the target with a `percent_chance`
 * in a hundred, which is how an iron golem shoved by a monster turns on it.
 */
final class PushedTargetGoal extends TargetGoal {
    private final AddonEntity pushed;
    private final TargetSelection selection;
    private final double chance;
    private LivingEntity pusher;

    PushedTargetGoal(AddonEntity pushed, JsonObject goal, TargetSelection selection) {
        super(pushed, false);
        this.pushed = pushed;
        this.selection = selection;
        this.chance = Goals.number(goal, "percent_chance", 0) / 100;
        setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        pusher = pushed.pushedBy() instanceof LivingEntity living ? living : null;
        return pusher != null && pusher != pushed.getTarget() && selection.accepts(pushed, pusher)
            && pushed.getRandom().nextDouble() < chance;
    }

    @Override
    public void start() {
        pushed.setTarget(pusher);
        targetMob = pusher;
        super.start();
    }
}
