package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Turns the entity to face wherever its rider is looking.
 *
 * `minecraft:behavior.controlled_by_player` is the steering half of a Bedrock mount. Where the mount
 * goes is the entity's own business, because Java asks the entity for a rider's input rather than
 * asking a goal; what the goal owns is the turn, and how much of it is allowed in one tick.
 */
public final class ControlledByPlayerGoal extends Goal {
    private final AddonEntity mob;
    private final double fraction;
    private final double limit;

    public ControlledByPlayerGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.fraction = Goals.number(component, "fractional_rotation", 0.5);
        this.limit = Goals.number(component, "fractional_rotation_limit", 5);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return mob.getControllingPassenger() instanceof Player;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
        mob.setTarget(null);
    }

    @Override
    public void tick() {
        LivingEntity rider = mob.getControllingPassenger();
        if (rider == null) return;

        float wanted = Mth.wrapDegrees(rider.getYRot() - mob.getYRot());
        float turn = (float) Mth.clamp(wanted * fraction, -limit, limit);
        mob.setYRot(mob.getYRot() + turn);
        mob.setYBodyRot(mob.getYRot());
        mob.setYHeadRot(mob.getYRot());
    }
}
