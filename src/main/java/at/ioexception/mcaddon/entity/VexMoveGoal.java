package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Drifts to a spot picked out of the air nearby, steering rather than pathing.
 *
 * `minecraft:behavior.vex_random_move` is how a vex gets about: it ignores the ground, so there is no
 * route to walk and the move control is given the point directly. The component carries no values,
 * which is why this reads none. Where it wanders from is the place it started, so an entity keeps to
 * the area a pack summoned it into.
 */
public final class VexMoveGoal extends Goal {
    /** How far out of its anchor a spot may be picked, in blocks. */
    private static final int REACH = 15;

    /** How far above and below the anchor a spot may sit. */
    private static final int RISE = 4;

    /** How close counts as having arrived, squared. */
    private static final double ARRIVED = 1;

    private final AddonEntity mob;
    private Vec3 anchor;
    private Vec3 heading;

    public VexMoveGoal(AddonEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return !mob.isPassenger() && mob.getNavigation().isDone();
    }

    @Override
    public boolean canContinueToUse() {
        return heading != null && mob.distanceToSqr(heading) > ARRIVED;
    }

    @Override
    public void start() {
        if (anchor == null) anchor = mob.position();
        heading = somewhereNear();
    }

    @Override
    public void stop() {
        heading = null;
    }

    @Override
    public void tick() {
        if (heading == null) return;

        mob.getMoveControl().setWantedPosition(heading.x, heading.y, heading.z, 1);
    }

    private Vec3 somewhereNear() {
        return anchor.add((mob.getRandom().nextDouble() - 0.5) * 2 * REACH,
            (mob.getRandom().nextDouble() - 0.5) * 2 * RISE,
            (mob.getRandom().nextDouble() - 0.5) * 2 * REACH);
    }
}
