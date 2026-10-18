package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;


/**
 * The view `/camera` set for one player.
 *
 * Three of the four presets name a camera the game already draws, so the client switches to that one
 * and lets the game work out where a third person camera belongs. `minecraft:free` is the one that
 * names a place of its own, and `pos` and `rot` override whichever parts of the placement they give.
 * `facing` is a point to turn the camera towards instead of a rotation, which only the client can
 * turn into one, because only the client knows where its camera stands when no `pos` was given.
 * A pack's own preset arrives as the free camera it is built on, with its placement already filled in.
 *
 * `listensFromPlayer` puts what the player hears back at the player's own head while the camera is
 * away from it.
 *
 * A view with no preset is a cleared one, which gives the player back the camera they had.
 */
public record CameraView(Preset preset, Vec3 position, Float xRot, Float yRot, Vec3 facing, float seconds,
    Easing easing, boolean listensFromPlayer) {

    public enum Preset {
        FIRST_PERSON("minecraft:first_person"),
        THIRD_PERSON("minecraft:third_person"),
        THIRD_PERSON_FRONT("minecraft:third_person_front"),
        FREE("minecraft:free");

        private final String bedrockName;

        Preset(String bedrockName) {
            this.bedrockName = bedrockName;
        }

        /** Null for a name that is not one of these four, written with its namespace. */
        public static Preset tryFrom(String full) {
            for (Preset preset : values()) {
                if (preset.bedrockName.equals(full)) return preset;
            }
            return null;
        }

        public String bedrockName() {
            return bedrockName;
        }

        /**
         * Whether the camera stands still where it is put, which is the only kind Bedrock eases to.
         *
         * The three gameplay perspectives follow the player, and Bedrock refuses an ease to one of
         * them rather than chase a moving target.
         */
        public boolean stationary() {
            return this == FREE;
        }
    }

    public static final CameraView CLEARED = new CameraView(null, null, null, null, null, 0f, null, false);

    /** The kinds a fade and a field of view are sent to a client under, apart from the view itself. */
    public static final String FADE = "camera_fade";
    public static final String FOV = "camera_fov";

    public boolean cleared() {
        return preset == null;
    }

    /**
     * This view with `facing` turned into the rotation that looks at it from where the camera stands.
     *
     * The camera stands at the view's own position when it names one and at `from` otherwise.
     * Bedrock works the rotation out once, as if it had been given with `rot`, so the camera does
     * not follow the point afterwards.
     */
    public CameraView facingFrom(Vec3 from) {
        if (facing == null) return this;

        Vec3 towards = facing.subtract(position == null ? from : position);
        double across = Math.sqrt(towards.x * towards.x + towards.z * towards.z);
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(towards.z, towards.x)) - 90f);
        float pitch = (float) -Math.toDegrees(Math.atan2(towards.y, across));
        return new CameraView(preset, position, pitch, yaw, null, seconds, easing, listensFromPlayer);
    }

    public String encode() {
        JsonObject json = new JsonObject();
        if (preset != null) json.addProperty("preset", preset.bedrockName());
        if (position != null) {
            json.addProperty("x", position.x);
            json.addProperty("y", position.y);
            json.addProperty("z", position.z);
        }
        if (xRot != null) json.addProperty("xRot", xRot);
        if (yRot != null) json.addProperty("yRot", yRot);
        if (facing != null) {
            json.addProperty("facingX", facing.x);
            json.addProperty("facingY", facing.y);
            json.addProperty("facingZ", facing.z);
        }
        if (easing != null) {
            json.addProperty("easing", easing.bedrockName());
            json.addProperty("seconds", seconds);
        }
        if (listensFromPlayer) json.addProperty("listener", "player");
        return json.toString();
    }

    public static CameraView decode(String encoded) {
        JsonElement parsed = Json.parse(encoded);
        if (!parsed.isJsonObject()) return CLEARED;

        JsonObject json = parsed.getAsJsonObject();
        Preset preset = json.has("preset") ? Preset.tryFrom(Json.text(json, "preset", "")) : null;
        if (preset == null) return CLEARED;

        Easing easing = json.has("easing") ? Easing.tryFrom(Json.text(json, "easing", "")) : null;
        float seconds = json.has("seconds") ? json.get("seconds").getAsFloat() : 0f;

        return new CameraView(preset, point(json, "x", "y", "z"), angle(json, "xRot"), angle(json, "yRot"),
            point(json, "facingX", "facingY", "facingZ"), seconds, easing,
            Json.text(json, "listener", "").equals("player"));
    }

    private static Vec3 point(JsonObject json, String x, String y, String z) {
        return json.has(x)
            ? new Vec3(json.get(x).getAsDouble(), json.get(y).getAsDouble(), json.get(z).getAsDouble())
            : null;
    }

    private static Float angle(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsFloat();
    }
}
