package at.ioexception.mcaddon.bedrock.input;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * One kind of player input `/inputpermission` and `Player.inputPermissions` switch on and off.
 *
 * A category answers for itself, which is what `/inputpermission query` and the `haspermission`
 * selector argument read. What a player may do is narrower: switching `movement` off takes away
 * jumping, sneaking, lateral movement, mounting and dismounting as well, and switching lateral
 * movement off takes away each of the four directions.
 */
public enum InputPermission {
    CAMERA("camera", 1, null),
    MOVEMENT("movement", 2, null),
    LATERAL_MOVEMENT("lateral_movement", 4, MOVEMENT),
    SNEAK("sneak", 5, MOVEMENT),
    JUMP("jump", 6, MOVEMENT),
    MOUNT("mount", 7, MOVEMENT),
    DISMOUNT("dismount", 8, MOVEMENT),
    MOVE_FORWARD("move_forward", 9, LATERAL_MOVEMENT),
    MOVE_BACKWARD("move_backward", 10, LATERAL_MOVEMENT),
    MOVE_LEFT("move_left", 11, LATERAL_MOVEMENT),
    MOVE_RIGHT("move_right", 12, LATERAL_MOVEMENT);

    private final String bedrockName;
    private final int scriptValue;
    private final InputPermission within;

    InputPermission(String bedrockName, int scriptValue, InputPermission within) {
        this.bedrockName = bedrockName;
        this.scriptValue = scriptValue;
        this.within = within;
    }

    /** The category a command or a selector names, or null for a word that names none. */
    public static InputPermission tryFrom(String bedrockName) {
        for (InputPermission permission : values()) {
            if (permission.bedrockName.equalsIgnoreCase(bedrockName)) return permission;
        }
        return null;
    }

    /** The category a script names by number, or null for a number that names none. */
    public static InputPermission tryFrom(int scriptValue) {
        for (InputPermission permission : values()) {
            if (permission.scriptValue == scriptValue) return permission;
        }
        return null;
    }

    public String bedrockName() {
        return bedrockName;
    }

    public int scriptValue() {
        return scriptValue;
    }

    /** Whether a player with these categories switched off may still give this input. */
    public boolean allowedWith(Set<InputPermission> disabled) {
        for (InputPermission covering = this; covering != null; covering = covering.within) {
            if (disabled.contains(covering)) return false;
        }
        return true;
    }

    public static int bits(Collection<InputPermission> permissions) {
        return permissions.stream().mapToInt(permission -> 1 << permission.scriptValue)
            .reduce(0, (left, right) -> left | right);
    }

    public static Set<InputPermission> fromBits(int bits) {
        Set<InputPermission> permissions = EnumSet.noneOf(InputPermission.class);
        for (InputPermission permission : values()) {
            if ((bits & (1 << permission.scriptValue)) != 0) permissions.add(permission);
        }
        return permissions;
    }
}
