package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A camera `/camera set` and `Camera.setCamera` may name: one of Bedrock's own, or one a behaviour
 * pack describes in `cameras/presets/` on top of another.
 *
 * `camera` is the Java camera the preset is drawn with, and is null for a preset that ends up at one
 * of Bedrock's cameras with no Java counterpart, which `root` then names. A pack may only build on
 * `minecraft:free` or on another of its own, so every preset a pack draws is a free camera, with
 * `position`, `xRot` and `yRot` as the placement a command gives when it names none of its own.
 * Each of the three is null where nothing in the chain states it, which leaves that part of the
 * placement to the camera Java sets up, the same as for `minecraft:free` itself.
 *
 * `listener` says where the player hears from. Bedrock hears from the camera unless a preset names
 * the player, and so does Java once the camera has moved.
 */
public record CameraPreset(String identifier, String root, CameraView.Preset camera, Vec3 position, Float xRot,
    Float yRot, boolean listensFromPlayer) {

    /** Bedrock's built-in presets with no Java camera behind them: an orbit, a boom and a scheme's own. */
    private static final Set<String> UNDRAWN = Set.of(
        "minecraft:fixed_boom", "minecraft:follow_orbit", "minecraft:control_scheme_camera");

    /** The fields a preset file states that this reads. Every other field is reported as it is read. */
    private static final Set<String> READ = Set.of(
        "identifier", "inherit_from", "pos_x", "pos_y", "pos_z", "rot_x", "rot_y", "listener");

    /** Null for a name that is no built-in preset. The namespace may be left off, and usually is. */
    public static CameraPreset builtIn(String written) {
        String name = written.toLowerCase(Locale.ROOT);
        String full = name.contains(":") ? name : "minecraft:" + name;
        CameraView.Preset camera = CameraView.Preset.tryFrom(full);
        return camera != null || UNDRAWN.contains(full)
            ? new CameraPreset(full, full, camera, null, null, null, false) : null;
    }

    /**
     * Every preset body a pack's `cameras/presets/` holds, by its identifier.
     *
     * Bedrock refuses a preset whose pitch would turn the camera upside down rather than clamp it, so
     * such a file is left out here too.
     */
    public static Map<String, JsonObject> written(BedrockPack pack) {
        Map<String, JsonObject> presets = new LinkedHashMap<>();
        for (Path file : pack.list("cameras/presets", ".json")) {
            try {
                JsonObject body = Json.parseObject(file).getAsJsonObject("minecraft:camera_preset");
                String identifier = body == null ? null : Json.text(body, "identifier", null);
                if (identifier == null) continue;
                if (Math.abs(Json.number(body, "rot_x", 0)) > 90) {
                    McAddon.LOGGER.warn("Camera preset {} turns the camera past straight up or down.", identifier);
                    continue;
                }

                body.keySet().stream().filter(field -> !READ.contains(field))
                    .forEach(field -> AddonEntities.unsupported("camera preset " + field));
                presets.put(identifier.toLowerCase(Locale.ROOT), body);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return presets;
    }

    /**
     * The preset a pack writes on top of this one: what it states replaces what this one holds.
     *
     * A position is three numbers Bedrock keeps together, each of them zero on `minecraft:free`, so a
     * preset stating one of them stands at zero on the others unless the chain above it says more.
     */
    public CameraPreset inheritedBy(String child, JsonObject body) {
        boolean placed = body.has("pos_x") || body.has("pos_y") || body.has("pos_z");
        Vec3 from = position == null ? Vec3.ZERO : position;
        return new CameraPreset(child, root, camera,
            placed ? new Vec3(Json.number(body, "pos_x", from.x), Json.number(body, "pos_y", from.y),
                Json.number(body, "pos_z", from.z)) : position,
            body.has("rot_x") ? Float.valueOf((float) Json.number(body, "rot_x", 0)) : xRot,
            body.has("rot_y") ? Float.valueOf((float) Json.number(body, "rot_y", 0)) : yRot,
            body.has("listener") ? Json.text(body, "listener", "").equals("player") : listensFromPlayer);
    }

    /**
     * Why this preset cannot be set with this ease, or null when it can.
     *
     * Bedrock refuses an ease to one of the three cameras that follow the player rather than chase a
     * moving target, and a preset built on a camera Java has nothing like is named for what it is
     * built on.
     */
    public String refusal(Easing easing) {
        if (camera == null) {
            return root.equals(identifier)
                ? identifier + " is a Bedrock camera that Java has no counterpart for, so it is not drawn."
                : identifier + " is built on " + root + ", a Bedrock camera that Java has no counterpart for,"
                    + " so it is not drawn.";
        }
        if (easing != null && !camera.stationary()) {
            return "Easing to non-stationary cameras is not supported, and " + identifier
                + " follows the player.";
        }
        return null;
    }

    /** What the player is sent: this preset, with whatever the command or the script stated over it. */
    public CameraView view(Vec3 givenPosition, Float givenXRot, Float givenYRot, Vec3 facing, float seconds,
        Easing easing) {
        return new CameraView(camera, givenPosition == null ? position : givenPosition,
            givenXRot == null ? xRot : givenXRot, givenYRot == null ? yRot : givenYRot, facing, seconds, easing,
            listensFromPlayer);
    }
}
