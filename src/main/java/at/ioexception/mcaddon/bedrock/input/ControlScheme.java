package at.ioexception.mcaddon.bedrock.input;

import at.ioexception.mcaddon.bedrock.camera.CameraView;

/**
 * How a player's movement keys and mouse move and turn them, which `/controlscheme`, a script and a
 * camera preset's `control_scheme` choose.
 *
 * `LOCKED_PLAYER_RELATIVE_STRAFE` is Java's own: the keys walk and strafe along the player's facing
 * and the mouse turns the player. Every other scheme keeps the player looking level. Under
 * `PLAYER_RELATIVE` the left and right keys turn the player rather than strafe; under
 * `CAMERA_RELATIVE` the keys walk along the camera's facing and the player turns to face the way it
 * walks. Under both the mouse turns the camera and leaves the player alone. Under the two strafing
 * schemes the mouse moves a pointer over the screen and the player looks toward it, and the keys walk
 * along the player's facing or along the camera's.
 *
 * A camera allows only some schemes, and every built-in camera starts on the locked one.
 */
public enum ControlScheme {
    CAMERA_RELATIVE("camera_relative", "CameraRelative"),
    CAMERA_RELATIVE_STRAFE("camera_relative_strafe", "CameraRelativeStrafe"),
    LOCKED_PLAYER_RELATIVE_STRAFE("locked_player_relative_strafe", "LockedPlayerRelativeStrafe"),
    PLAYER_RELATIVE("player_relative", "PlayerRelative"),
    PLAYER_RELATIVE_STRAFE("player_relative_strafe", "PlayerRelativeStrafe");

    public static final ControlScheme DEFAULT = LOCKED_PLAYER_RELATIVE_STRAFE;

    private final String bedrockName;
    private final String scriptName;

    ControlScheme(String bedrockName, String scriptName) {
        this.bedrockName = bedrockName;
        this.scriptName = scriptName;
    }

    /** The scheme a command, a preset or a script names, in either spelling, or null for none. */
    public static ControlScheme tryFrom(String name) {
        for (ControlScheme scheme : values()) {
            if (scheme.bedrockName.equalsIgnoreCase(name) || scheme.scriptName.equals(name)) return scheme;
        }
        return null;
    }

    public String bedrockName() {
        return bedrockName;
    }

    public String scriptName() {
        return scriptName;
    }

    /**
     * Whether a player on this camera may use this scheme, by the table Mojang publishes for the
     * built-in presets; with no camera set the player is on one of the gameplay views.
     *
     * Mojang's table leaves out `minecraft:control_scheme_camera`, the camera made to try the schemes
     * on, so it allows every one of them here.
     */
    public boolean allowedOn(CameraView.Preset camera) {
        if (this == DEFAULT) return true;
        if (camera == null) return false;

        return switch (camera) {
            case FIRST_PERSON, THIRD_PERSON, THIRD_PERSON_FRONT -> false;
            case FOLLOW_ORBIT -> this == PLAYER_RELATIVE || this == CAMERA_RELATIVE;
            case FREE, FIXED_BOOM, CONTROL_SCHEME -> true;
        };
    }

    /** Whether the keys walk along the camera's facing rather than the player's. */
    public boolean walksAlongCamera() {
        return this == CAMERA_RELATIVE || this == CAMERA_RELATIVE_STRAFE;
    }

    /** Whether the mouse moves a pointer the player looks toward. */
    public boolean looksAtPointer() {
        return this == PLAYER_RELATIVE_STRAFE || this == CAMERA_RELATIVE_STRAFE;
    }

    /** Whether the mouse turns the camera rather than the player. */
    public boolean mouseTurnsCamera() {
        return this == PLAYER_RELATIVE || this == CAMERA_RELATIVE;
    }
}
