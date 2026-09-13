package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Stands still and watches whatever the entity is hunting.
 *
 * `minecraft:behavior.hold_ground` is a mob that stops where it is once it has something in sight,
 * and `minecraft:behavior.look_at_target` is the same thing without the stopping. Java has a goal
 * for looking at a kind of entity but none for looking at the one already chosen.
 */
public final class HoldGroundGoal extends Goal {
    private final Mob mob;
    private final boolean stopsMoving;

    private HoldGroundGoal(Mob mob, boolean stopsMoving) {
        this.mob = mob;
        this.stopsMoving = stopsMoving;
        setFlags(stopsMoving ? EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK) : EnumSet.of(Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.hold_ground`, which stands its ground rather than closing the distance. */
    public static HoldGroundGoal standing(Mob mob) {
        return new HoldGroundGoal(mob, true);
    }

    /** `minecraft:behavior.look_at_target`, which leaves the entity free to keep moving. */
    public static HoldGroundGoal watching(Mob mob) {
        return new HoldGroundGoal(mob, false);
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() != null && mob.getTarget().isAlive();
    }

    @Override
    public void tick() {
        if (mob.getTarget() == null) return;

        mob.getLookControl().setLookAt(mob.getTarget(), 30f, 30f);
        if (!stopsMoving) return;

        mob.getNavigation().stop();
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
    }
}
