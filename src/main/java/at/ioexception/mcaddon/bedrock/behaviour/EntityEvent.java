package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One `events` entry of a behaviour pack entity.
 *
 * An event is what a pack uses to change an entity: it swaps component groups in and out, sets the
 * properties a resource pack draws from, and runs commands, which is how the data half of a pack
 * talks to its script half through `/scriptevent`.
 */
public record EntityEvent(String name, List<Action> actions) {
    public sealed interface Action {
        /** The filters an action carries are checked before it runs. */
        Filter condition();
    }

    public record ChangeGroups(Filter condition, List<String> add, List<String> remove) implements Action {
    }

    public record SetProperties(Filter condition, Map<String, JsonElement> values) implements Action {
    }

    public record RunCommands(Filter condition, List<String> commands) implements Action {
    }

    public record TriggerEvent(Filter condition, String event) implements Action {
    }

    /** One of several outcomes, chosen by weight, which is how a pack picks a random variant. */
    public record Randomise(Filter condition, List<Weighted> options) implements Action {
        public record Weighted(double weight, EntityEvent outcome) {
        }
    }

    /** `minecraft:first_valid` runs the first of its entries whose own filters hold, and stops. */
    public record FirstValid(Filter condition, List<EntityEvent> options) implements Action {
    }

    public record Sequence(Filter condition, List<EntityEvent> steps) implements Action {
    }

    public static Map<String, EntityEvent> readAll(JsonObject events) {
        Map<String, EntityEvent> parsed = new LinkedHashMap<>();
        if (events == null) return parsed;

        for (Map.Entry<String, JsonElement> entry : events.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            parsed.put(entry.getKey(), read(entry.getKey(), entry.getValue().getAsJsonObject()));
        }
        return parsed;
    }

    private static EntityEvent read(String name, JsonObject body) {
        Filter condition = Filter.parse(body.get("filters"));
        List<Action> actions = new ArrayList<>();

        List<String> add = groups(body.get("add"));
        List<String> remove = groups(body.get("remove"));
        if (!add.isEmpty() || !remove.isEmpty()) actions.add(new ChangeGroups(condition, add, remove));

        if (body.has("set_property") && body.get("set_property").isJsonObject()) {
            Map<String, JsonElement> values = new LinkedHashMap<>();
            body.getAsJsonObject("set_property").entrySet()
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
            actions.add(new SetProperties(condition, values));
        }

        List<String> commands = new ArrayList<>();
        for (JsonElement command : Json.list(body, "queue_command")) {
            if (command.isJsonPrimitive()) {
                commands.add(command.getAsString());
            } else if (command.isJsonObject()) {
                Json.list(command.getAsJsonObject(), "command")
                    .forEach(line -> commands.add(line.getAsString()));
            }
        }
        if (!commands.isEmpty()) actions.add(new RunCommands(condition, commands));

        if (body.has("trigger")) {
            JsonElement trigger = body.get("trigger");
            String event = trigger.isJsonPrimitive() ? trigger.getAsString()
                : trigger.isJsonObject() ? Json.text(trigger.getAsJsonObject(), "event", null) : null;
            if (event != null) {
                Filter triggerCondition = trigger.isJsonObject()
                    ? Filter.parse(trigger.getAsJsonObject().get("filters")) : condition;
                actions.add(new TriggerEvent(triggerCondition, event));
            }
        }

        if (body.has("randomize") && body.get("randomize").isJsonArray()) {
            List<Randomise.Weighted> options = new ArrayList<>();
            for (JsonElement option : body.getAsJsonArray("randomize")) {
                if (!option.isJsonObject()) continue;
                JsonObject outcome = option.getAsJsonObject();
                double weight = outcome.has("weight") ? outcome.get("weight").getAsDouble() : 1;
                options.add(new Randomise.Weighted(weight, read(name, outcome)));
            }
            if (!options.isEmpty()) actions.add(new Randomise(condition, options));
        }

        if (body.has("sequence") && body.get("sequence").isJsonArray()) {
            List<EntityEvent> steps = new ArrayList<>();
            for (JsonElement step : body.getAsJsonArray("sequence")) {
                if (step.isJsonObject()) steps.add(read(name, step.getAsJsonObject()));
            }
            if (!steps.isEmpty()) actions.add(new Sequence(condition, steps));
        }

        if (body.has("first_valid") && body.get("first_valid").isJsonArray()) {
            List<EntityEvent> options = new ArrayList<>();
            for (JsonElement option : body.getAsJsonArray("first_valid")) {
                if (option.isJsonObject()) options.add(read(name, option.getAsJsonObject()));
            }
            if (!options.isEmpty()) actions.add(new FirstValid(condition, options));
        }

        // A response nobody here reads is a gap in this mod rather than a mistake in the pack, and
        // an event that quietly does part of what it says is worse than one that says what it
        // skipped. `filters` is the condition every response above is already read against.
        for (String key : body.keySet()) {
            if (!READ.contains(key)) {
                at.ioexception.mcaddon.registry.AddonEntities.unsupported("event response " + key);
            }
        }

        return new EntityEvent(name, List.copyOf(actions));
    }

    /** Every key an event body may carry that something above acts on. */
    private static final java.util.Set<String> READ = java.util.Set.of(
        "filters", "add", "remove", "set_property", "queue_command", "trigger",
        "randomize", "sequence", "first_valid", "weight");

    /** `add` and `remove` wrap their list under `component_groups`. */
    private static List<String> groups(JsonElement json) {
        List<String> groups = new ArrayList<>();
        if (json == null || !json.isJsonObject()) return groups;

        for (JsonElement group : Json.list(json.getAsJsonObject(), "component_groups")) {
            if (group.isJsonPrimitive()) groups.add(group.getAsString());
        }
        return groups;
    }
}
