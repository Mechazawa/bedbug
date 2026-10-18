package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.camera.CameraPreset;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.bedrock.camera.Easing;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The camera presets the packs describe, by the identifier `/camera set` names.
 *
 * Nothing is registered with the game: a preset only ever reaches a client as the view the server
 * resolved it into. Each is resolved against the preset it inherits from once, as the packs are
 * read, so a chain is walked here and never again.
 */
public final class AddonCameras {
    private static final Map<String, CameraPreset> PRESETS = new LinkedHashMap<>();

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

    /** Why a preset cannot be set with this ease, or null when it can, in the words a player reads. */
    public static String refusal(String written, Easing easing) {
        CameraPreset preset = presetOf(written);
        return preset == null ? "There is no camera preset named " + written + "." : preset.refusal(easing);
    }

    /** The names `/camera set` suggests: the four presets Java draws and every one a pack added. */
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
        return preset;
    }
}
