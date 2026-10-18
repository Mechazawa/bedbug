package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The sounds one entity makes, out of the resource pack's `sounds.json`.
 *
 * Bedrock keeps every mob's noises in one file, keyed by entity and then by the moment that plays
 * them. Java asks the mob itself for three of those moments and takes the fourth off the block it is
 * walking on, so those four are answered here and the rest of the moments a Bedrock mob has are
 * named instead.
 *
 * @param events  the sound event named for each moment, under Bedrock's own name for the moment
 * @param volume  how loud, or -1 where the entry names no volume
 * @param pitch   the range a pitch is drawn from, or null where the entry names none
 */
public record EntitySounds(Map<String, String> events, double volume, double[] pitch) {
    /**
     * The moments Java asks a mob about by itself.
     *
     * The three idle moments beside the plain one are what `minecraft:uses_legacy_ambient_sounds`
     * picks between, so Java's one question about an idle sound reaches each of them. Every other
     * moment is kept as well and is made only where one of the entity's components names it, the way
     * a panic names `panic_sound`, so it is still reported: nothing makes it on its own.
     */
    private static final Set<String> ANSWERED = Set.of("ambient", "ambient.in.air",
        "ambient.in.water", "ambient.baby", "hurt", "death", "step");

    public String event(String moment) {
        return events.get(moment);
    }

    /**
     * A pitch from inside the range the entry names, or null where it names none.
     *
     * @param roll where in the range to land, from zero for the low end to one for the high
     */
    public Float voicePitch(double roll) {
        if (pitch == null) return null;
        return (float) (pitch[0] + roll * (pitch[1] - pitch[0]));
    }

    /**
     * Every entity the file describes, by the name it is keyed under.
     *
     * `defaults` states what a mob naming nothing of its own makes, so each entry is read over the
     * defaults rather than beside them. A pack keys its own mob by that mob's identifier and the
     * vanilla file keys each by its bare name, so the caller matches both spellings.
     */
    public static Map<String, EntitySounds> readAll(BedrockPack pack) {
        Map<String, EntitySounds> found = new LinkedHashMap<>();
        Path file = pack.file("sounds.json");
        if (!Files.isRegularFile(file)) return found;

        try {
            JsonObject body = Json.parseObject(file);
            reportOtherSections(body);

            JsonObject entities = section(body, "entity_sounds");
            EntitySounds defaults = read(section(entities, "defaults"), null);
            for (Map.Entry<String, JsonElement> entry : section(entities, "entities").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                found.put(entry.getKey(), read(entry.getValue().getAsJsonObject(), defaults));
            }
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read sounds.json: {}", error.toString());
        }
        return found;
    }

    /** The entry for one entity, under either spelling of its name. */
    public static EntitySounds of(Map<String, EntitySounds> sounds, String identifier) {
        EntitySounds named = sounds.get(identifier);
        if (named != null) return named;

        int colon = identifier.indexOf(':');
        return colon < 0 ? null : sounds.get(identifier.substring(colon + 1));
    }

    /**
     * What the rest of the file says, which describes a Bedrock client rather than a mob.
     *
     * `block_sounds` is answered through the sound group a block's own `blocks.json` entry names,
     * which is how Java gives a block its sounds. The other three attach a sound to a moment in the
     * Bedrock interface: an item used on a block, a named one-off, and the click of a screen.
     */
    private static void reportOtherSections(JsonObject body) {
        for (String section : Set.of("individual_event_sounds", "individual_named_sounds",
            "interactive_sounds")) {
            if (body.has(section)) AddonEntities.unsupported("sounds.json " + section);
        }
    }

    private static JsonObject section(JsonObject body, String name) {
        JsonElement found = body.get(name);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : new JsonObject();
    }

    private static EntitySounds read(JsonObject body, EntitySounds defaults) {
        Map<String, String> events = new LinkedHashMap<>(
            defaults == null ? Map.of() : defaults.events());

        for (Map.Entry<String, JsonElement> entry : section(body, "events").entrySet()) {
            String named = named(entry.getValue());
            if (named == null || named.isBlank()) continue;
            if (!ANSWERED.contains(entry.getKey())) AddonEntities.unsupported("entity sound " + entry.getKey());
            events.put(entry.getKey(), named);
        }

        double volume = Json.number(body, "volume",
            defaults == null ? -1 : defaults.volume());
        double[] pitch = range(body.get("pitch"));
        return new EntitySounds(Map.copyOf(events), volume,
            pitch == null && defaults != null ? defaults.pitch() : pitch);
    }

    /** Bedrock writes a moment as the event name alone or as an object naming it under `sound`. */
    private static String named(JsonElement value) {
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.isJsonObject() ? Json.text(value.getAsJsonObject(), "sound", null) : null;
    }

    /** Null where nothing is written; a single number is a range of no width. */
    private static double[] range(JsonElement value) {
        if (value == null) return null;
        if (value.isJsonPrimitive()) {
            return new double[] {value.getAsDouble(), value.getAsDouble()};
        }
        if (!value.isJsonArray() || value.getAsJsonArray().size() < 2) return null;
        return new double[] {value.getAsJsonArray().get(0).getAsDouble(),
            value.getAsJsonArray().get(1).getAsDouble()};
    }
}
