package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * Gets where it is going in hops rather than in steps.
 *
 * `minecraft:movement.jump` and `minecraft:movement.skip` both describe an entity that never walks:
 * it turns to face where it is going and leaves the ground. Java writes the same movement into its
 * rabbit and its slime, each as a control of its own that no other mob can be given. The first
 * states `jump_delay`, the seconds it rests after landing, a random point of a range each time.
 */
public final class HopMoveControl extends MoveControl {
    /** How close counts as arrived, so the entity stops hopping instead of overshooting. */
    private static final double ARRIVED = 0.5;

    private final AddonEntity hopper;
    private boolean airborne;
    private int restUntil;

    public HopMoveControl(AddonEntity mob) {
        super(mob);
        this.hopper = mob;
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

        if (!mob.onGround()) {
            airborne = true;
            return;
        }
        if (airborne) {
            airborne = false;
            com.google.gson.JsonObject jump = hopper.liveComponent("minecraft:movement.jump");
            int shortest = Goals.ticksLow(jump, "jump_delay", 0);
            restUntil = mob.tickCount + Goals.between(mob.getRandom(), shortest,
                Math.max(shortest, Goals.ticksHigh(jump, "jump_delay", 0)));
        }
        if (mob.tickCount < restUntil) return;

        hopper.hop(speedModifier);
    }
}
