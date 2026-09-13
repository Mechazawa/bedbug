package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Tumbles forward for a moment, which is what a Bedrock panda does when it feels like it.
 *
 * `minecraft:behavior.roll` names only how likely it is. What the roll looks like is the pack's own
 * animation, keyed off `q.is_rolling`, so the goal carries the entity along the ground and says it
 * is rolling while it does.
 */
public final class RollGoal extends Goal {
    /** How long a roll carries the entity, which is one animation's worth in every vanilla pack. */
    private static final int DURATION = 20;

    private final AddonEntity mob;
    private final double probability;
    private int rolled;

    public RollGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.probability = Goals.number(component, "probability", 0.02);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return mob.onGround() && mob.getRandom().nextDouble() < probability;
    }

    @Override
    public boolean canContinueToUse() {
        return rolled < DURATION;
    }

    @Override
    public void start() {
        rolled = 0;
        mob.getNavigation().stop();
        mob.setValue("is_rolling", true);
    }

    @Override
    public void tick() {
        rolled++;
        Vec3 forward = Vec3.directionFromRotation(0, mob.getYRot()).scale(0.12);
        mob.setDeltaMovement(forward.x, mob.getDeltaMovement().y, forward.z);
    }

    @Override
    public void stop() {
        mob.setValue("is_rolling", false);
    }
}
