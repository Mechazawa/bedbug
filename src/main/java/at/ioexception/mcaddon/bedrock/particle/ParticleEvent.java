package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry under `events`, which an emitter or a particle asks for by name.
 *
 * A node does any of six things, and a pack may write several of them in one node, in which case
 * all of them happen. `sequence` runs its nodes one after another and `randomize` picks one of
 * them by weight, so a node is a small tree rather than a single action.
 */
public record ParticleEvent(MolangExpression expression, String log, Visual particle, String sound,
    List<ParticleEvent> sequence, List<Chance> randomize) {

    /**
     * Another effect, shown where this one is.
     *
     * `type` says what is started: one particle, one particle carrying this one's velocity, or a
     * whole emitter, bound to the entity or left behind at the point.
     */
    public record Visual(String effect, String type, MolangExpression before) {
    }

    public record Chance(float weight, ParticleEvent event) {
    }

    /** A moment in an emitter's or a particle's life, and the events it raises when it arrives. */
    public record Moment(float at, List<String> events) {
    }

    /** A distance an emitter travels, and the events it raises on reaching it. */
    public record Travel(float distance, List<String> events, boolean looping) {
    }

    /**
     * The events one component raises over a life: at the start, at the end, and along the way.
     *
     * A name is written either on its own or in a list, and both forms read as a list here.
     */
    public record Lifecycle(List<String> creation, List<String> expiration, List<Moment> timeline,
        List<Travel> travel) {
    }

    /** Every event a file declares, by the name a component asks for it under. */
    public static Map<String, ParticleEvent> readAll(JsonObject events) {
        Map<String, ParticleEvent> read = new LinkedHashMap<>();
        if (events == null) return read;

        events.entrySet().forEach(entry -> {
            ParticleEvent event = tryFrom(entry.getValue());
            if (event != null) read.put(entry.getKey(), event);
        });
        return read;
    }

    /** Null for a node that asks for nothing this reader can act on. */
    public static ParticleEvent tryFrom(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;

        JsonObject json = value.getAsJsonObject();
        Visual particle = visual(ParticleJson.object(json, "particle_effect"));
        String sound = sound(ParticleJson.object(json, "sound_effect"));
        MolangExpression expression = json.has("expression")
            ? ParticleJson.expression(json, "expression", 0) : null;
        String log = Json.text(json, "log", null);
        List<ParticleEvent> sequence = nodes(json.get("sequence"));
        List<Chance> randomize = chances(json.get("randomize"));

        boolean empty = particle == null && sound == null && expression == null && log == null
            && sequence.isEmpty() && randomize.isEmpty();
        return empty ? null
            : new ParticleEvent(expression, log, particle, sound, sequence, randomize);
    }

    private static Visual visual(JsonObject json) {
        String effect = json == null ? null : Json.text(json, "effect", null);
        return effect == null ? null : new Visual(effect, Json.text(json, "type", "particle"),
            json.has("pre_effect_expression")
                ? ParticleJson.expression(json, "pre_effect_expression", 0) : null);
    }

    private static String sound(JsonObject json) {
        return json == null ? null : Json.text(json, "event_name", null);
    }

    private static List<ParticleEvent> nodes(JsonElement value) {
        List<ParticleEvent> read = new ArrayList<>();
        if (value == null || !value.isJsonArray()) return List.of();

        value.getAsJsonArray().forEach(node -> {
            ParticleEvent event = tryFrom(node);
            if (event != null) read.add(event);
        });
        return List.copyOf(read);
    }

    /** A node with no weight of its own counts as one, which is what an even choice looks like. */
    private static List<Chance> chances(JsonElement value) {
        List<Chance> read = new ArrayList<>();
        if (value == null || !value.isJsonArray()) return List.of();

        value.getAsJsonArray().forEach(node -> {
            ParticleEvent event = tryFrom(node);
            if (event == null) return;

            JsonObject json = node.getAsJsonObject();
            read.add(new Chance(ParticleJson.number(json, "weight", 1f), event));
        });
        return List.copyOf(read);
    }

    /** The creation, expiration, timeline and travel entries of a lifetime events component. */
    public static Lifecycle lifecycle(JsonObject component) {
        if (component == null) {
            return new Lifecycle(List.of(), List.of(), List.of(), List.of());
        }

        List<Travel> travel = new ArrayList<>(reached(component.get("travel_distance_events")));
        travel.addAll(repeating(component.get("looping_travel_distance_events")));

        return new Lifecycle(names(component.get("creation_event")),
            names(component.get("expiration_event")), timeline(component.get("timeline")),
            List.copyOf(travel));
    }

    /** Distances the emitter passes once, written the way a timeline is: the key is the distance. */
    private static List<Travel> reached(JsonElement value) {
        if (value == null || !value.isJsonObject()) return List.of();

        List<Travel> read = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            List<String> events = names(entry.getValue());
            if (!events.isEmpty()) read.add(new Travel(at(entry.getKey()), events, false));
        }
        return List.copyOf(read);
    }

    /** Distances the emitter raises events at again every time it travels that far. */
    private static List<Travel> repeating(JsonElement value) {
        if (value == null || !value.isJsonArray()) return List.of();

        List<Travel> read = new ArrayList<>();
        value.getAsJsonArray().forEach(entry -> {
            if (!entry.isJsonObject()) return;

            JsonObject json = entry.getAsJsonObject();
            List<String> events = names(json.get("effects"));
            float distance = ParticleJson.number(json, "distance", 0f);
            if (!events.isEmpty() && distance > 0) read.add(new Travel(distance, events, true));
        });
        return List.copyOf(read);
    }

    /** An event name, written either on its own or in a list of them. */
    private static List<String> names(JsonElement value) {
        if (value == null) return List.of();
        if (value.isJsonPrimitive()) return List.of(value.getAsString());
        if (!value.isJsonArray()) return List.of();

        List<String> read = new ArrayList<>();
        value.getAsJsonArray().forEach(name -> {
            if (name.isJsonPrimitive()) read.add(name.getAsString());
        });
        return List.copyOf(read);
    }

    /** Moments in order, so a run of ticks can walk forward through them without searching. */
    private static List<Moment> timeline(JsonElement value) {
        if (value == null || !value.isJsonObject()) return List.of();

        List<Moment> moments = new ArrayList<>();
        for (Map.Entry<String, JsonElement> moment : value.getAsJsonObject().entrySet()) {
            List<String> events = names(moment.getValue());
            if (!events.isEmpty()) moments.add(new Moment(at(moment.getKey()), events));
        }
        moments.sort(Comparator.comparing(Moment::at));
        return List.copyOf(moments);
    }

    private static float at(String key) {
        try {
            return Float.parseFloat(key);
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }
}
