package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * Gets where it is going in hops rather than in steps.
 *
 * `minecraft:movement.jump` and `minecraft:movement.skip` both describe an entity that never walks:
 * it turns to face where it is going and leaves the ground. Java writes the same movement into its
 * rabbit and its slime, each as a control of its own that no other mob can be given.
 */
public final class HopMoveControl extends MoveControl {
    /** How high one hop carries, which is the height a rabbit and a slime both clear. */
    private static final double HOP_HEIGHT = 0.42;

    /** How much of a hop goes forward, and how much further a hurried one carries. */
    private static final double HOP_REACH = 0.3;

    /** How close counts as arrived, so the entity stops hopping instead of overshooting. */
    private static final double ARRIVED = 0.5;

    public HopMoveControl(AddonEntity mob) {
        super(mob);
    }

    @Override
    public void tick() {
        if (operation != Operation.MOVE_TO) return;

        double towardsX = wantedX - mob.getX();
        double towardsZ = wantedZ - mob.getZ();
        if (towardsX * towardsX + towardsZ * towardsZ < ARRIVED * ARRIVED) {
            operation = Operation.WAIT;
            return;
        }

        float facing = (float) (Math.atan2(towardsZ, towardsX) * 180 / Math.PI) - 90f;
        mob.setYRot(rotlerp(mob.getYRot(), facing, 90f));
        mob.yBodyRot = mob.getYRot();
        mob.yHeadRot = mob.getYRot();

        if (!mob.onGround()) return;

        double along = Math.toRadians(mob.getYRot());
        double push = HOP_REACH * speedModifier;
        mob.setDeltaMovement(-Math.sin(along) * push, HOP_HEIGHT, Math.cos(along) * push);
    }
}
