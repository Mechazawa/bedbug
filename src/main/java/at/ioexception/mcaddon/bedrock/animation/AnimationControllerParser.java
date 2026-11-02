package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Reads one animation controller out of a pack's `animation_controllers` JSON. */
public final class AnimationControllerParser {

    private AnimationControllerParser() {
    }

    public static AnimationController readController(String name, JsonObject json) {
        Map<String, AnimationController.State> states = new HashMap<>();
        JsonObject source = json.getAsJsonObject("states");
        for (Map.Entry<String, JsonElement> state : source.entrySet()) {
            states.put(state.getKey(), readState(state.getValue().getAsJsonObject()));
        }

        // A controller with no declared start begins in the state named "default".
        String initial = json.has("initial_state") ? json.get("initial_state").getAsString() : "default";
        return new AnimationController(name, initial, Map.copyOf(states));
    }

    private static AnimationController.State readState(JsonObject json) {
        List<AnimationController.Entry> animations = new ArrayList<>();
        if (json.has("animations")) {
            for (JsonElement entry : json.getAsJsonArray("animations")) {
                if (entry.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> blended : entry.getAsJsonObject().entrySet()) {
                        animations.add(new AnimationController.Entry(
                            blended.getKey(), MolangParser.parse(primitive(blended.getValue()))));
                    }
                } else {
                    animations.add(new AnimationController.Entry(entry.getAsString(), null));
                }
            }
        }

        List<AnimationController.Transition> transitions = new ArrayList<>();
        if (json.has("transitions")) {
            for (JsonElement entry : json.getAsJsonArray("transitions")) {
                Map.Entry<String, JsonElement> single = entry.getAsJsonObject().entrySet().iterator().next();
                transitions.add(new AnimationController.Transition(
                    single.getKey(), MolangParser.parse(primitive(single.getValue()))));
            }
        }

        List<String> sounds = new ArrayList<>();
        if (json.has("sound_effects")) {
            for (JsonElement entry : json.getAsJsonArray("sound_effects")) {
                JsonObject effect = entry.getAsJsonObject();
                if (effect.has("effect")) sounds.add(effect.get("effect").getAsString());
            }
        }

        List<AnimationController.Effect> particles = new ArrayList<>();
        if (json.has("particle_effects")) {
            for (JsonElement entry : json.getAsJsonArray("particle_effects")) {
                JsonObject effect = entry.getAsJsonObject();
                if (!effect.has("effect")) continue;
                particles.add(new AnimationController.Effect(effect.get("effect").getAsString(),
                    effect.has("locator") ? effect.get("locator").getAsString() : null,
                    effect.has("pre_effect_script")
                        ? AnimationParser.readExpressions(effect.get("pre_effect_script")) : List.of(),
                    AnimationParser.boundToActor(effect)));
            }
        }

        return new AnimationController.State(
            readVariables(json.get("variables")),
            List.copyOf(animations),
            List.copyOf(transitions),
            readStatements(json, "on_entry"),
            readStatements(json, "on_exit"),
            List.copyOf(sounds),
            List.copyOf(particles),
            readBlendTransition(json.get("blend_transition")),
            json.has("blend_via_shortest_path") && json.get("blend_via_shortest_path").getAsBoolean());
    }

    /** A state's `variables`, each named with or without its `variable.` or `v.` prefix. */
    private static List<AnimationController.Variable> readVariables(JsonElement json) {
        if (json == null || !json.isJsonObject()) return List.of();

        List<AnimationController.Variable> variables = new ArrayList<>();
        for (Map.Entry<String, JsonElement> declared : json.getAsJsonObject().entrySet()) {
            if (!declared.getValue().isJsonObject()) continue;
            JsonObject body = declared.getValue().getAsJsonObject();

            String name = declared.getKey().toLowerCase(Locale.ROOT).replaceFirst("^(variable|v)\\.", "");
            TreeMap<Float, Float> curve = new TreeMap<>();
            if (body.has("remap_curve") && body.get("remap_curve").isJsonObject()) {
                for (Map.Entry<String, JsonElement> point : body.getAsJsonObject("remap_curve").entrySet()) {
                    curve.put(Float.parseFloat(point.getKey()), point.getValue().getAsFloat());
                }
            }
            float[] inputs = new float[curve.size()];
            float[] outputs = new float[curve.size()];
            int i = 0;
            for (Map.Entry<Float, Float> point : curve.entrySet()) {
                inputs[i] = point.getKey();
                outputs[i++] = point.getValue();
            }
            variables.add(new AnimationController.Variable(name,
                body.has("input") ? MolangParser.parse(primitive(body.get("input"))) : MolangExpression.ZERO,
                inputs, outputs));
        }
        return List.copyOf(variables);
    }

    /**
     * How long a state takes to fade in. It is usually a duration, but may be a sampled curve, in
     * which case its last moment is the duration and the shape between is close enough to linear.
     */
    private static float readBlendTransition(JsonElement json) {
        if (json == null || json.isJsonNull()) return 0f;
        if (json.isJsonPrimitive()) return json.getAsFloat();

        float longest = 0f;
        for (String moment : json.getAsJsonObject().keySet()) longest = Math.max(longest, Float.parseFloat(moment));
        return longest;
    }

    /**
     * What a state fires as it is entered or left.
     *
     * Each entry is a command, one of the entity's own events or Molang, and which of the three it
     * is comes out of the string itself.
     */
    private static List<AnimationAction> readStatements(JsonObject json, String field) {
        if (!json.has(field)) return List.of();

        List<AnimationAction> statements = new ArrayList<>();
        for (JsonElement entry : json.getAsJsonArray(field)) {
            statements.add(AnimationAction.of(entry.getAsString()));
        }
        return List.copyOf(statements);
    }

    private static Object primitive(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) return json.getAsDouble();
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) return json.getAsBoolean() ? 1d : 0d;
        return json.getAsString();
    }

}
