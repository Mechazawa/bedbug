package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.aim.AimAssistCategory;
import at.ioexception.mcaddon.bedrock.aim.AimAssistPreset;
import at.ioexception.mcaddon.bedrock.aim.AimAssistSettings;
import at.ioexception.mcaddon.bedrock.aim.AimAssistTargeting;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The aim assist presets and categories the packs describe, those a script adds, the game's own
 * default of each, and the aim assist each camera preset states.
 *
 * A pack keeps its presets and categories under `aim_assist/`, or beside its camera presets under
 * `cameras/`, which is where Microsoft's tutorial puts them; a file is told apart by its top key.
 *
 * The first pack to name a preset or a category keeps it, as with camera presets. A script adding
 * one under a name that is taken replaces it.
 */
public final class AddonAimAssist {
    private static final Map<String, AimAssistPreset> PRESETS = new LinkedHashMap<>();
    private static final Map<String, AimAssistCategory> CATEGORIES = new LinkedHashMap<>();
    private static final Map<String, AimAssistSettings> CAMERAS = new LinkedHashMap<>();

    private AddonAimAssist() {
    }

    public static void register(PackSet packs) {
        PRESETS.clear();
        CATEGORIES.clear();
        CAMERAS.clear();
        PRESETS.put(AimAssistPreset.DEFAULT.identifier(), AimAssistPreset.DEFAULT);
        CATEGORIES.put(AimAssistCategory.DEFAULT.identifier(), AimAssistCategory.DEFAULT);

        for (BedrockPack pack : packs.withRole(PackRole.BEHAVIOUR)) {
            for (Path file : java.util.stream.Stream.of("aim_assist", "cameras")
                .flatMap(directory -> pack.list(directory, ".json").stream()).toList()) {
                try {
                    read(Json.parseObject(file));
                } catch (IOException | RuntimeException error) {
                    McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
                }
            }
        }
        if (PRESETS.size() > 1 || CATEGORIES.size() > 1) {
            McAddon.LOGGER.info("Read {} aim assist presets and {} categories from add-ons.", PRESETS.size() - 1,
                CATEGORIES.size() - 1);
        }
    }

    /** The first pack to describe a name keeps it, and a pack describing a default replaces the game's. */
    private static void read(JsonObject file) {
        if (file.has("minecraft:aim_assist_preset")) {
            AimAssistPreset preset = AimAssistPreset.tryFrom(file.getAsJsonObject("minecraft:aim_assist_preset"));
            if (preset != null && (PRESETS.get(preset.identifier()) == null
                || PRESETS.get(preset.identifier()) == AimAssistPreset.DEFAULT)) {
                PRESETS.put(preset.identifier(), preset);
            }
        }
        if (file.has("minecraft:aim_assist_categories")) {
            Json.list(file.getAsJsonObject("minecraft:aim_assist_categories"), "categories").stream()
                .map(JsonElement::getAsJsonObject).map(AimAssistCategory::tryFrom).filter(Objects::nonNull)
                .filter(category -> CATEGORIES.get(category.identifier()) == null
                    || CATEGORIES.get(category.identifier()) == AimAssistCategory.DEFAULT)
                .forEach(category -> CATEGORIES.put(category.identifier(), category));
        }
    }

    /**
     * Keeps the aim assist a camera preset states, or the one the preset it inherits from states,
     * which `AddonCameras` calls for each preset after the one it inherits from.
     */
    public static void readCamera(String identifier, String parent, JsonObject body) {
        AimAssistSettings stated = body.has("aim_assist") && body.get("aim_assist").isJsonObject()
            ? AimAssistSettings.fromCamera(body.getAsJsonObject("aim_assist")) : CAMERAS.get(parent);
        if (stated != null) CAMERAS.put(identifier, stated);
    }

    /** Null for a camera preset that states no aim assist. */
    public static AimAssistSettings ofCamera(String cameraPreset) {
        return CAMERAS.get(cameraPreset);
    }

    public static AimAssistPreset preset(String identifier) {
        return PRESETS.get(identifier);
    }

    public static AimAssistCategory category(String identifier) {
        return CATEGORIES.get(identifier);
    }

    public static Collection<AimAssistPreset> presets() {
        return PRESETS.values();
    }

    public static Collection<AimAssistCategory> categories() {
        return CATEGORIES.values();
    }

    public static void add(AimAssistPreset preset) {
        PRESETS.put(preset.identifier(), preset);
    }

    public static void add(AimAssistCategory category) {
        CATEGORIES.put(category.identifier(), category);
    }

    /**
     * What a player's client is sent for these settings, or null where no preset of that name exists.
     * A category the preset names and nobody describes is left for the client to answer with the
     * game's default.
     */
    public static AimAssistTargeting targeting(AimAssistSettings settings) {
        AimAssistPreset preset = PRESETS.get(settings.presetId());
        if (preset == null) return null;

        Map<String, AimAssistCategory> named = new LinkedHashMap<>();
        preset.categories().stream().map(CATEGORIES::get).filter(Objects::nonNull)
            .forEach(category -> named.put(category.identifier(), category));
        return new AimAssistTargeting(settings, preset, named);
    }
}
