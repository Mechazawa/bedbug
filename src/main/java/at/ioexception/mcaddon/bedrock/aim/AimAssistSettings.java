package at.ioexception.mcaddon.bedrock.aim;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * The cone one player's aim assist picks a target in, and the preset that says what it may pick.
 *
 * `xAngle` and `yAngle` are how far either side of the way the player faces the cone reaches,
 * across and up and down, in degrees, and `distance` is how far it reaches in blocks.
 */
public record AimAssistSettings(String presetId, TargetMode mode, float xAngle, float yAngle, float distance) {
    public static final String DEFAULT_PRESET = "minecraft:aim_assist_default";
    public static final float DEFAULT_ANGLE = 50f;
    public static final float DEFAULT_DISTANCE = 8f;
    public static final float MIN_ANGLE = 10f;
    public static final float MAX_ANGLE = 90f;
    public static final float MIN_DISTANCE = 1f;
    public static final float MAX_DISTANCE = 16f;

    /** Which of two targets in the cone wins: the nearer one, or the one nearer the cone's middle. */
    public enum TargetMode {
        ANGLE("angle", "Angle"),
        DISTANCE("distance", "Distance");

        private final String commandName;
        private final String scriptName;

        TargetMode(String commandName, String scriptName) {
            this.commandName = commandName;
            this.scriptName = scriptName;
        }

        /** Null for a word that is neither mode, in the spelling of the command or of a script. */
        public static TargetMode tryFrom(String written) {
            for (TargetMode mode : values()) {
                if (mode.commandName.equals(written) || mode.scriptName.equals(written)) return mode;
            }
            return null;
        }

        public String commandName() {
            return commandName;
        }

        public String scriptName() {
            return scriptName;
        }
    }

    /**
     * What a camera preset's `aim_assist` states, with Bedrock's own defaults where it states nothing.
     * A number outside what the command accepts is brought inside it.
     */
    public static AimAssistSettings fromCamera(JsonObject aimAssist) {
        JsonArray angle = aimAssist.has("angle") && aimAssist.get("angle").isJsonArray()
            ? aimAssist.getAsJsonArray("angle") : new JsonArray();
        TargetMode mode = TargetMode.tryFrom(Json.text(aimAssist, "target_mode", ""));
        return new AimAssistSettings(namespaced(Json.text(aimAssist, "preset", DEFAULT_PRESET)),
            mode == null ? TargetMode.ANGLE : mode,
            Mth.clamp(angle.size() > 0 ? angle.get(0).getAsFloat() : DEFAULT_ANGLE, MIN_ANGLE, MAX_ANGLE),
            Mth.clamp(angle.size() > 1 ? angle.get(1).getAsFloat() : DEFAULT_ANGLE, MIN_ANGLE, MAX_ANGLE),
            Mth.clamp((float) Json.number(aimAssist, "distance", DEFAULT_DISTANCE), MIN_DISTANCE, MAX_DISTANCE));
    }

    /** A preset or a category named without a namespace is one of the game's own. */
    public static String namespaced(String written) {
        String lower = written.toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    /** Why Bedrock refuses these numbers, in its own words, or null when it takes them. */
    public String refusal() {
        if (xAngle < MIN_ANGLE || xAngle > MAX_ANGLE) return "Aim assist X angle must be between 10 and 90";
        if (yAngle < MIN_ANGLE || yAngle > MAX_ANGLE) return "Aim assist Y angle must be between 10 and 90";
        if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) return "Aim assist distance must be between 1 and 16";
        return null;
    }

    /**
     * How far a point is from winning, smaller being better, or NaN where it is outside the cone or
     * the priority keeps it out.
     *
     * A priority divides the measure the mode compares by, so a priority of six is taken as six
     * times nearer, and a priority of nothing or below keeps the point out.
     */
    public double score(Vec3 eye, float yRot, float xRot, Vec3 point, int priority) {
        Vec3 toPoint = point.subtract(eye);
        double length = toPoint.length();
        if (priority <= 0 || length > distance || length < 1.0e-6) return Double.NaN;

        double yaw = Math.toDegrees(Math.atan2(-toPoint.x, toPoint.z));
        double pitch = Math.toDegrees(-Math.atan2(toPoint.y, toPoint.horizontalDistance()));
        if (Math.abs(Mth.wrapDegrees(yaw - yRot)) > xAngle || Math.abs(pitch - xRot) > yAngle) return Double.NaN;

        if (mode == TargetMode.DISTANCE) return length / priority;
        double facing = Vec3.directionFromRotation(xRot, yRot).dot(toPoint.scale(1 / length));
        return Math.toDegrees(Math.acos(Mth.clamp(facing, -1, 1))) / priority;
    }

    /** The camera preset's own shape, which `fromCamera` reads back. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("preset", presetId);
        json.addProperty("target_mode", mode.commandName());
        JsonArray angle = new JsonArray();
        angle.add(xAngle);
        angle.add(yAngle);
        json.add("angle", angle);
        json.addProperty("distance", distance);
        return json;
    }
}
