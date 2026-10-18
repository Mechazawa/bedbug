package at.ioexception.mcaddon.bedrock.camera;

import net.minecraft.world.phys.Vec3;

/**
 * What `/camera set` and `Camera.setCamera` state over the preset they name. Every part is null, or
 * zero seconds with no easing, where nothing was stated.
 *
 * `viewX`, `viewY` and `entityOffset` are the two offsets an orbit camera takes.
 */
public record CameraRequest(Vec3 position, Float xRot, Float yRot, Vec3 facing, float seconds, Easing easing,
    Float viewX, Float viewY, Vec3 entityOffset) {

    public static final CameraRequest NOTHING = new CameraRequest(null, null, null, null, 0f, null, null, null, null);

    /** A position or a point to face, which only a camera that stands still takes. */
    public boolean places() {
        return position != null || facing != null;
    }

    public boolean offsets() {
        return viewX != null || viewY != null || entityOffset != null;
    }
}
