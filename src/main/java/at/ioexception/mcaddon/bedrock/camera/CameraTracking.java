package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * How a free camera follows the entity `/camera target_entity` names, as a preset states it.
 *
 * `rotationSpeed` is in degrees a second, and zero keeps up with the target whatever it does.
 * `snapToTarget` jumps onto the target on the first frame and turns at that speed afterwards.
 *
 * The horizontal limits are how far the camera may turn left and right of the way it first looked,
 * and together cover the whole circle unless they add up to less than 360. The vertical limits are
 * measured in the world rather than from the camera, from straight down at 0 through the horizon at
 * 90 to straight up at 180.
 *
 * A target further than `trackingRadius` blocks, or outside the limits, is not followed: the camera
 * turns back to the way it first looked, or with `continueTargeting` keeps looking as far towards it
 * as the limits allow.
 */
public record CameraTracking(float rotationSpeed, boolean snapToTarget, float leftLimit, float rightLimit,
    float lowestLimit, float highestLimit, boolean continueTargeting, float trackingRadius) {

    public static final CameraTracking DEFAULT = new CameraTracking(0f, false, 0f, 360f, 0f, 180f, false, 50f);

    /** Bedrock drops a target this far from the camera, whatever the preset says. */
    public static final double LOST = 1024;

    private static final float CIRCLE = 360f;
    private static final float HORIZON = 90f;

    public CameraTracking inheritedBy(JsonObject body) {
        float[] horizontal = Vectors.pair(body.get("horizontal_rotation_limit"));
        float[] vertical = Vectors.pair(body.get("vertical_rotation_limit"));
        return new CameraTracking(
            Math.max(0f, (float) Json.number(body, "rotation_speed", rotationSpeed)),
            Json.bool(body, "snap_to_target", snapToTarget),
            horizontal == null ? leftLimit : horizontal[0], horizontal == null ? rightLimit : horizontal[1],
            vertical == null ? lowestLimit : vertical[0], vertical == null ? highestLimit : vertical[1],
            Json.bool(body, "continue_targeting", continueTargeting),
            (float) Json.number(body, "tracking_radius", trackingRadius));
    }

    /**
     * The rotation a camera turned to `current` takes this frame, following `point` from where it
     * first looked, `initial`, over `seconds` since the last frame.
     */
    public CameraPlacement follow(CameraPlacement current, CameraPlacement initial, Vec3 point, float seconds,
        boolean first) {
        CameraPlacement wanted = current.turnedTo(point);
        float yawOff = Mth.wrapDegrees(wanted.yRot() - initial.yRot());
        float elevation = HORIZON - wanted.xRot();
        boolean reached = point.distanceTo(current.position()) <= trackingRadius;
        boolean inside = turnsFreely() || (yawOff >= -leftLimit && yawOff <= rightLimit);
        inside &= elevation >= lowestLimit && elevation <= highestLimit;

        CameraPlacement goal;
        if (reached && inside) {
            goal = wanted;
        } else if (continueTargeting) {
            float yaw = turnsFreely() ? wanted.yRot() : initial.yRot() + Mth.clamp(yawOff, -leftLimit, rightLimit);
            goal = new CameraPlacement(current.position(),
                HORIZON - Mth.clamp(elevation, lowestLimit, highestLimit), yaw, current.detached());
        } else {
            goal = new CameraPlacement(current.position(), initial.xRot(), initial.yRot(), current.detached());
        }

        if (rotationSpeed <= 0f || (snapToTarget && first)) return goal;
        float step = rotationSpeed * seconds;
        return new CameraPlacement(current.position(),
            current.xRot() + Mth.clamp(goal.xRot() - current.xRot(), -step, step),
            current.yRot() + Mth.clamp(Mth.wrapDegrees(goal.yRot() - current.yRot()), -step, step),
            current.detached());
    }

    private boolean turnsFreely() {
        return leftLimit + rightLimit >= CIRCLE;
    }

    JsonObject json() {
        JsonObject json = new JsonObject();
        json.addProperty("rotation_speed", rotationSpeed);
        json.addProperty("snap_to_target", snapToTarget);
        json.add("horizontal_rotation_limit", Vectors.pair(leftLimit, rightLimit));
        json.add("vertical_rotation_limit", Vectors.pair(lowestLimit, highestLimit));
        json.addProperty("continue_targeting", continueTargeting);
        json.addProperty("tracking_radius", trackingRadius);
        return json;
    }

    static CameraTracking read(JsonElement written) {
        return written == null || !written.isJsonObject() ? DEFAULT : DEFAULT.inheritedBy(written.getAsJsonObject());
    }
}
