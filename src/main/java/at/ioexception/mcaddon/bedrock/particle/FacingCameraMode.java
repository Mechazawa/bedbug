package at.ioexception.mcaddon.bedrock.particle;

import java.util.Locale;

/** How a billboard is turned before it is drawn. */
public enum FacingCameraMode {
    ROTATE_XYZ,
    ROTATE_Y,
    LOOKAT_XYZ,
    LOOKAT_Y,
    LOOKAT_DIRECTION,
    DIRECTION_X,
    DIRECTION_Y,
    DIRECTION_Z,
    EMITTER_TRANSFORM_XY,
    EMITTER_TRANSFORM_XZ,
    EMITTER_TRANSFORM_YZ;

    /** A name no version of Bedrock has reads as the mode a billboard gets when it names none. */
    public static FacingCameraMode of(String name) {
        if (name == null) return ROTATE_XYZ;

        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return ROTATE_XYZ;
        }
    }
}
