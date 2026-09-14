package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The view `/camera` set for one player.
 *
 * Three of the four presets name a camera the game already draws, so the client switches to that one
 * and lets the game work out where a third person camera belongs. `minecraft:free` is the one that
 * names a place of its own, and `pos` and `rot` override whichever parts of the placement they give.
 *
 * A view with no preset is a cleared one, which gives the player back the camera they had.
 */
public record CameraView(Preset preset, Vec3 position, Float xRot, Float yRot, float seconds,
    Easing easing) {

    public enum Preset {
        FIRST_PERSON("minecraft:first_person"),
        THIRD_PERSON("minecraft:third_person"),
        THIRD_PERSON_FRONT("minecraft:third_person_front"),
        FREE("minecraft:free");

        private final String bedrockName;

        Preset(String bedrockName) {
            this.bedrockName = bedrockName;
        }

        /** Null for a preset Bedrock has not got. The namespace may be left off, and usually is. */
        public static Preset tryFrom(String written) {
            String name = written.toLowerCase(Locale.ROOT);
            String full = name.startsWith("minecraft:") ? name : "minecraft:" + name;

            for (Preset preset : values()) {
                if (preset.bedrockName.equals(full)) return preset;
            }
            return null;
        }

        public String bedrockName() {
            return bedrockName;
        }
    }

    public static final CameraView CLEARED = new CameraView(null, null, null, null, 0f, null);

    public static List<String> presetNames() {
        List<String> names = new ArrayList<>();
        for (Preset preset : Preset.values()) names.add(preset.bedrockName());
        return names;
    }

    public boolean cleared() {
        return preset == null;
    }

    public String encode() {
        JsonObject json = new JsonObject();
        if (preset != null) json.addProperty("preset", preset.name());
        if (position != null) {
            json.addProperty("x", position.x);
            json.addProperty("y", position.y);
            json.addProperty("z", position.z);
        }
        if (xRot != null) json.addProperty("xRot", xRot);
        if (yRot != null) json.addProperty("yRot", yRot);
        if (easing != null) {
            json.addProperty("easing", easing.bedrockName());
            json.addProperty("seconds", seconds);
        }
        return json.toString();
    }

    public static CameraView decode(String encoded) {
        JsonElement parsed = Json.parse(encoded);
        if (!parsed.isJsonObject()) return CLEARED;

        JsonObject json = parsed.getAsJsonObject();
        Preset preset = json.has("preset") ? Preset.tryFrom(Json.text(json, "preset", "")) : null;
        if (preset == null) return CLEARED;

        Vec3 position = json.has("x")
            ? new Vec3(json.get("x").getAsDouble(), json.get("y").getAsDouble(),
                json.get("z").getAsDouble())
            : null;
        Easing easing = json.has("easing") ? Easing.tryFrom(Json.text(json, "easing", "")) : null;
        float seconds = json.has("seconds") ? json.get("seconds").getAsFloat() : 0f;

        return new CameraView(preset, position, angle(json, "xRot"), angle(json, "yRot"),
            seconds, easing);
    }

    private static Float angle(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsFloat();
    }
}
