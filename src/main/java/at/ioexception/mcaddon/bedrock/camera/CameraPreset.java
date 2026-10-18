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
 * `camera` is the Bedrock camera at the top of the preset's chain, which `root` names. `position`,
 * `xRot` and `yRot` are the placement a command gives when it names none of its own, and each is
 * null where nothing in the chain states it, which leaves that part of the placement to the camera
 * Java sets up, the same as for `minecraft:free` itself. `orbit` is how an orbit camera stands off the
 * entity it follows, and `tracking` is how a free camera follows a target.
 *
 * `listener` says where the player hears from. Bedrock hears from the camera unless a preset names
 * the player, and so does Java once the camera has moved.
 */
public record CameraPreset(String identifier, String root, CameraView.Preset camera, Vec3 position, Float xRot,
    Float yRot, boolean listensFromPlayer, CameraOrbit orbit, CameraTracking tracking) {

    /** The fields a preset file states that this reads. Every other field is reported as it is read. */
    private static final Set<String> READ = Set.of(
        "identifier", "inherit_from", "pos_x", "pos_y", "pos_z", "rot_x", "rot_y", "listener",
        "radius", "view_offset", "entity_offset", "starting_rot_x", "starting_rot_y",
        "align_target_and_camera_forward", "rotation_speed", "snap_to_target", "horizontal_rotation_limit",
        "vertical_rotation_limit", "continue_targeting", "tracking_radius");

    /** Null for a name that is no built-in preset. The namespace may be left off, and usually is. */
    public static CameraPreset builtIn(String written) {
        String name = written.toLowerCase(Locale.ROOT);
        String full = name.contains(":") ? name : "minecraft:" + name;
        CameraView.Preset camera = CameraView.Preset.tryFrom(full);
        return camera == null ? null
            : new CameraPreset(full, full, camera, null, null, null, false, CameraOrbit.DEFAULT, CameraTracking.DEFAULT);
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
            body.has("listener") ? Json.text(body, "listener", "").equals("player") : listensFromPlayer,
            orbit.inheritedBy(body), tracking.inheritedBy(body));
    }

    /**
     * Why this preset cannot be set as the request asks, or null when it can.
     *
     * Bedrock refuses an ease to one of the three cameras tied to the player's own view, a position
     * or a point to face for a camera that follows an entity, and an offset for a camera that does not
     * stand off one.
     */
    public String refusal(CameraRequest request) {
        if (request.easing() != null && !camera.easesIn()) {
            return "Easing to non-stationary cameras is not supported, and " + identifier
                + " follows the player.";
        }
        if (request.places() && camera.orbits()) {
            return identifier + " follows an entity, so it takes no position and no point to face.";
        }
        if (request.offsets() && !camera.orbits()) {
            return "view_offset and entity_offset move minecraft:follow_orbit, minecraft:fixed_boom and the"
                + " presets built on them, and " + identifier + " is not one of those.";
        }
        return null;
    }

    /** What the player is sent: this preset, with whatever the command or the script stated over it. */
    public CameraView view(CameraRequest request) {
        return new CameraView(camera, request.position() == null ? position : request.position(),
            request.xRot() == null ? xRot : request.xRot(), request.yRot() == null ? yRot : request.yRot(),
            request.facing(), request.seconds(), request.easing(), listensFromPlayer,
            orbit.offsetBy(request.viewX(), request.viewY(), request.entityOffset()), tracking);
    }
}
