package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;


/**
 * The view `/camera` set for one player.
 *
 * Three of the presets name a camera the game already draws, so the client switches to that one and
 * lets the game work out where a third person camera belongs. `minecraft:free` names a place of its
 * own, and `pos` and `rot` override whichever parts of the placement they give. `facing` is a point
 * to turn the camera towards instead of a rotation, which only the client can turn into one, because
 * only the client knows where its camera stands when no `pos` was given. A pack's own preset arrives
 * as the camera it is built on, with its placement already filled in.
 *
 * The orbit cameras stand off the player as `orbit` says. `minecraft:follow_orbit` and
 * `minecraft:control_scheme_camera` turn as the player turns, and `rot` turns the player; a fixed boom
 * keeps one turn, which is `rot` where it was given. `tracking` is how a free camera follows a target.
 *
 * `listensFromPlayer` puts what the player hears back at the player's own head while the camera is
 * away from it.
 *
 * A view with no preset is a cleared one, which gives the player back the camera they had.
 */
public record CameraView(Preset preset, Vec3 position, Float xRot, Float yRot, Vec3 facing, float seconds,
    Easing easing, boolean listensFromPlayer, CameraOrbit orbit, CameraTracking tracking)
    implements CameraInstruction {

    static final String KIND = "view";

    public enum Preset {
        FIRST_PERSON("minecraft:first_person"),
        THIRD_PERSON("minecraft:third_person"),
        THIRD_PERSON_FRONT("minecraft:third_person_front"),
        FREE("minecraft:free"),
        FOLLOW_ORBIT("minecraft:follow_orbit"),
        FIXED_BOOM("minecraft:fixed_boom"),
        CONTROL_SCHEME("minecraft:control_scheme_camera");

        private final String bedrockName;

        Preset(String bedrockName) {
            this.bedrockName = bedrockName;
        }

        /** Null for a name that is not one of these, written with its namespace. */
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
         * Whether Bedrock eases into this camera.
         *
         * The three gameplay perspectives are tied to the player's own view, and Bedrock refuses an
         * ease to one of them rather than chase it.
         */
        public boolean easesIn() {
            return this == FREE || orbits();
        }

        /** The cameras that stand off an entity at a radius rather than at a place or at its eyes. */
        public boolean orbits() {
            return this == FOLLOW_ORBIT || this == FIXED_BOOM || this == CONTROL_SCHEME;
        }

        /** The orbit cameras whose turn is the player's own. */
        public boolean turnsWithPlayer() {
            return this == FOLLOW_ORBIT || this == CONTROL_SCHEME;
        }
    }

    public static final CameraView CLEARED = new CameraView(null, null, null, null, null, 0f, null, false,
        CameraOrbit.DEFAULT, CameraTracking.DEFAULT);

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

        CameraPlacement turned = new CameraPlacement(position == null ? from : position, 0f, 0f, true)
            .turnedTo(facing);
        return new CameraView(preset, position, turned.xRot(), turned.yRot(), null, seconds, easing,
            listensFromPlayer, orbit, tracking);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject json() {
        JsonObject json = new JsonObject();
        if (preset == null) return json;

        json.addProperty("preset", preset.bedrockName());
        if (position != null) json.add("pos", Vectors.write(position));
        if (xRot != null) json.addProperty("xRot", xRot);
        if (yRot != null) json.addProperty("yRot", yRot);
        if (facing != null) json.add("facing", Vectors.write(facing));
        if (easing != null) {
            json.addProperty("easing", easing.bedrockName());
            json.addProperty("seconds", seconds);
        }
        if (listensFromPlayer) json.addProperty("listener", "player");
        json.add("orbit", orbit.json());
        json.add("tracking", tracking.json());
        return json;
    }

    static CameraView read(JsonObject json) {
        Preset preset = json.has("preset") ? Preset.tryFrom(Json.text(json, "preset", "")) : null;
        if (preset == null) return CLEARED;

        Easing easing = json.has("easing") ? Easing.tryFrom(Json.text(json, "easing", "")) : null;
        float seconds = json.has("seconds") ? json.get("seconds").getAsFloat() : 0f;

        return new CameraView(preset, Vectors.read(json.get("pos")), angle(json, "xRot"), angle(json, "yRot"),
            Vectors.read(json.get("facing")), seconds, easing, Json.text(json, "listener", "").equals("player"),
            CameraOrbit.read(json.get("orbit")), CameraTracking.read(json.get("tracking")));
    }

    private static Float angle(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsFloat();
    }
}
