package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.camera.CameraPreset;
import at.ioexception.mcaddon.bedrock.camera.CameraSpline;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The camera presets the packs describe, by the identifier `/camera set` names, and the camera splines
 * they describe in `camera/splines/`, by the name `/camera play_spline` names.
 *
 * Nothing is registered with the game: a preset only ever reaches a client as the view the server
 * resolved it into, and a spline as the curve it holds. Each preset is resolved against the preset it
 * inherits from once, as the packs are read, so a chain is walked here and never again.
 */
public final class AddonCameras {
    private static final Map<String, CameraPreset> PRESETS = new LinkedHashMap<>();
    private static final Map<String, CameraSpline> SPLINES = new LinkedHashMap<>();

    private AddonCameras() {
    }

    public static void register(PackSet packs) {
        PRESETS.clear();

        Map<String, JsonObject> written = new LinkedHashMap<>();
        for (BedrockPack pack : packs.withRole(PackRole.BEHAVIOUR)) {
            CameraPreset.written(pack).forEach(written::putIfAbsent);
        }
        written.keySet().forEach(identifier -> resolve(identifier, written, new HashSet<>()));
        if (!PRESETS.isEmpty()) McAddon.LOGGER.info("Read {} camera presets from add-ons.", PRESETS.size());

        SPLINES.clear();
        packs.withRole(PackRole.BEHAVIOUR).forEach(AddonCameras::readSplines);
        if (!SPLINES.isEmpty()) McAddon.LOGGER.info("Read {} camera splines from add-ons.", SPLINES.size());
    }

    /** The names `/camera play_spline` suggests. */
    public static List<String> splineNames() {
        return List.copyOf(SPLINES.keySet());
    }

    /** Null when no pack describes a spline of that name. */
    public static CameraSpline splineOf(String name) {
        return SPLINES.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * A spline is named by its own `name`, or by its file's name where it states none. The first pack
     * to name one keeps it, as with presets. A turn about the camera's own forward axis is reported,
     * because Java's camera has none.
     */
    private static void readSplines(BedrockPack pack) {
        for (Path file : pack.list("camera/splines", ".json")) {
            try {
                JsonObject json = Json.parseObject(file);
                CameraSpline spline = CameraSpline.fromFile(json);
                if (spline == null) continue;

                String stated = CameraSpline.nameIn(json);
                String name = stated != null ? stated : file.getFileName().toString().replace(".json", "");
                if (spline.rolls()) AddonEntities.unsupported("camera spline roll");
                SPLINES.putIfAbsent(name.toLowerCase(Locale.ROOT), spline);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
    }

    /**
     * Null when neither the game nor a pack has a preset of that name.
     *
     * A built-in name may leave its namespace off, and a pack's may not, because Bedrock only lets a
     * pack name its presets in a namespace of its own.
     */
    public static CameraPreset presetOf(String written) {
        CameraPreset builtIn = CameraPreset.builtIn(written);
        return builtIn != null ? builtIn : PRESETS.get(written.toLowerCase(Locale.ROOT));
    }

    /** The names `/camera set` suggests: Bedrock's own presets and every one a pack added. */
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (CameraView.Preset preset : CameraView.Preset.values()) names.add(preset.bedrockName());
        names.addAll(PRESETS.keySet());
        return names;
    }

    /**
     * Walks a preset's chain up to the built-in it ends at and back down again.
     *
     * A chain that names a preset nobody has, or that comes back round to itself, has nothing at its
     * top to be drawn as, so every preset on it is left out and the reason logged once.
     */
    private static CameraPreset resolve(String identifier, Map<String, JsonObject> written, Set<String> walked) {
        CameraPreset known = presetOf(identifier);
        if (known != null) return known;

        JsonObject body = written.get(identifier);
        if (body == null || !walked.add(identifier)) {
            McAddon.LOGGER.warn("Camera preset {} is missing, or inherits from a preset that inherits from it.",
                identifier);
            return null;
        }

        String parentName = body.has("inherit_from")
            ? body.get("inherit_from").getAsString().toLowerCase(Locale.ROOT) : "minecraft:free";
        CameraPreset parent = resolve(parentName, written, walked);
        if (parent == null) return null;

        CameraPreset preset = parent.inheritedBy(identifier, body);
        PRESETS.put(identifier, preset);
        AddonAimAssist.readCamera(identifier, parentName, body);
        return preset;
    }
}
