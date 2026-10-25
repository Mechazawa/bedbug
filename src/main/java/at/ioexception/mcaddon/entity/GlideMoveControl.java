package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Glides the way a phantom does: always moving, gathering speed while it flies straight and losing
 * it while it turns.
 *
 * `minecraft:movement.glide` states the speed a glide sets out at, `start_speed`, and the speed it
 * slows to through a turn, `speed_when_turning`. Mojang publishes no schema for the component, so the
 * rest is Java's phantom control, whose own two numbers are the 0.1 and 0.2 Bedrock's phantom writes:
 * the glide gathers speed towards the entity's movement speed, turns at most `max_turn` degrees a
 * tick, and turns back on itself when it flies into a wall. With nowhere to go it glides on ahead.
 */
final class GlideMoveControl extends MoveControl {
    /** Turning less than this in a tick still counts as flying straight, which is Java's three degrees. */
    private static final float STRAIGHT = 3f;

    /** How quickly a glide gathers speed and loses it, which is Java's phantom's. */
    private static final float GATHER = 0.005f;
    private static final float SHED = 0.025f;

    /** How much of the way to its new velocity a glide gets in a tick. */
    private static final double STEER = 0.2;

    private final float startSpeed;
    private final float turningSpeed;
    private final float maxTurn;
    private float speed;

    GlideMoveControl(AddonEntity mob, JsonObject glide) {
        super(mob);
        this.startSpeed = (float) Goals.number(glide, "start_speed", 0.1);
        this.turningSpeed = (float) Goals.number(glide, "speed_when_turning", 0.2);
        this.maxTurn = (float) Goals.number(glide, "max_turn", 30);
        this.speed = startSpeed;
    }

    @Override
    public void tick() {
        if (mob.horizontalCollision) {
            mob.setYRot(mob.getYRot() + 180f);
            speed = startSpeed;
        }

        Vec3 towards = operation == Operation.MOVE_TO
            ? new Vec3(wantedX - mob.getX(), wantedY - mob.getY(), wantedZ - mob.getZ())
            : Vec3.directionFromRotation(0, mob.getYRot());
        double across = Math.sqrt(towards.x * towards.x + towards.z * towards.z);
        if (across < 1.0E-5) return;

        double level = 1 - Math.abs(towards.y * 0.7) / across;
        double dx = towards.x * level;
        double dz = towards.z * level;
        across = Math.sqrt(dx * dx + dz * dz);
        double whole = Math.sqrt(dx * dx + dz * dz + towards.y * towards.y);

        float before = mob.getYRot();
        float heading = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        mob.setYRot(Mth.approachDegrees(before, heading, maxTurn));
        mob.yBodyRot = mob.getYRot();

        double pace = speedModifier > 0 ? speedModifier : 1;
        float top = (float) (mob.getAttributeValue(Attributes.MOVEMENT_SPEED) * pace);
        speed = Mth.degreesDifferenceAbs(before, mob.getYRot()) < STRAIGHT
            ? Mth.approach(speed, top, GATHER * (top / Math.max(speed, 1.0E-3f)))
            : Mth.approach(speed, turningSpeed, SHED);

        float pitch = (float) -(Mth.atan2(-towards.y, across) * Mth.RAD_TO_DEG);
        mob.setXRot(pitch);
        float moving = (mob.getYRot() + 90f) * Mth.DEG_TO_RAD;
        Vec3 wanted = new Vec3(speed * Mth.cos(moving) * Math.abs(dx / whole),
            speed * Mth.sin(pitch * Mth.DEG_TO_RAD) * Math.abs(towards.y / whole),
            speed * Mth.sin(moving) * Math.abs(dz / whole));
        Vec3 velocity = mob.getDeltaMovement();
        mob.setDeltaMovement(velocity.add(wanted.subtract(velocity).scale(STEER)));
    }
}
