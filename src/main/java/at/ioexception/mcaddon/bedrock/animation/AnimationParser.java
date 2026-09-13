package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Reads one keyframe animation out of a pack's `animations` JSON. */
public final class AnimationParser {

    private AnimationParser() {
    }

    public static BedrockAnimation readAnimation(String name, JsonObject json) {
        // A negative limit stands for an animation that declared no length, so nothing is clamped.
        float declared = json.has("animation_length") ? json.get("animation_length").getAsFloat() : -1f;

        Map<String, BoneAnimation> bones = new HashMap<>();
        if (json.has("bones")) {
            for (Map.Entry<String, JsonElement> bone : json.getAsJsonObject("bones").entrySet()) {
                bones.put(bone.getKey(), readBone(bone.getValue().getAsJsonObject(), declared));
            }
        }

        List<BedrockAnimation.TimelineEntry> timeline = new ArrayList<>();
        if (json.has("timeline")) {
            for (Map.Entry<String, JsonElement> moment : json.getAsJsonObject("timeline").entrySet()) {
                timeline.add(new BedrockAnimation.TimelineEntry(
                    Float.parseFloat(moment.getKey()), readActions(moment.getValue())));
            }
            timeline.sort(Comparator.comparing(BedrockAnimation.TimelineEntry::time));
        }

        List<BedrockAnimation.EffectEntry> sounds = readEffects(json.get("sound_effects"));
        List<BedrockAnimation.EffectEntry> particles = readEffects(json.get("particle_effects"));

        return new BedrockAnimation(
            name,
            declared >= 0 ? declared : lastMoment(bones, timeline, sounds, particles),
            readLoop(json.get("loop")),
            json.has("loop_delay") ? MolangParser.parse(primitive(json.get("loop_delay"))) : null,
            json.has("start_delay") ? MolangParser.parse(primitive(json.get("start_delay"))) : null,
            json.has("blend_weight") ? MolangParser.parse(primitive(json.get("blend_weight"))) : null,
            json.has("anim_time_update") ? MolangParser.parse(primitive(json.get("anim_time_update"))) : null,
            json.has("override_previous_animation") && json.get("override_previous_animation").getAsBoolean(),
            Map.copyOf(bones),
            List.copyOf(timeline),
            sounds,
            particles);
    }

    /**
     * How far an animation that declared no length runs: to its last keyframe or its last event.
     *
     * Most published animations leave `animation_length` out and rely on this, so an animation given
     * a length of zero instead would hold its first frame and never advance.
     */
    private static float lastMoment(Map<String, BoneAnimation> bones,
        List<BedrockAnimation.TimelineEntry> timeline,
        List<BedrockAnimation.EffectEntry> sounds,
        List<BedrockAnimation.EffectEntry> particles) {
        double keyframes = bones.values().stream()
            .flatMap(bone -> bone.channels().stream())
            .mapToDouble(AnimationChannel::end)
            .max().orElse(0);
        double events = Stream.of(timeline.stream().map(BedrockAnimation.TimelineEntry::time),
                sounds.stream().map(BedrockAnimation.EffectEntry::time),
                particles.stream().map(BedrockAnimation.EffectEntry::time))
            .flatMap(moments -> moments)
            .mapToDouble(Float::doubleValue)
            .max().orElse(0);
        return (float) Math.max(keyframes, events);
    }

    /**
     * The sounds or particles an animation fires, each at the moment its key names.
     *
     * Bedrock writes a moment as either one effect or a list of them, so both shapes end up as
     * entries at the same time.
     */
    private static List<BedrockAnimation.EffectEntry> readEffects(JsonElement json) {
        if (json == null || !json.isJsonObject()) return List.of();

        List<BedrockAnimation.EffectEntry> effects = new ArrayList<>();
        for (Map.Entry<String, JsonElement> moment : json.getAsJsonObject().entrySet()) {
            float time = Float.parseFloat(moment.getKey());
            if (moment.getValue().isJsonArray()) {
                for (JsonElement each : moment.getValue().getAsJsonArray()) {
                    effects.add(readEffect(time, each));
                }
            } else {
                effects.add(readEffect(time, moment.getValue()));
            }
        }
        effects.sort(Comparator.comparing(BedrockAnimation.EffectEntry::time));
        return List.copyOf(effects);
    }

    private static BedrockAnimation.EffectEntry readEffect(float time, JsonElement json) {
        if (!json.isJsonObject()) {
            return new BedrockAnimation.EffectEntry(time, json.getAsString(), null, List.of());
        }

        JsonObject body = json.getAsJsonObject();
        return new BedrockAnimation.EffectEntry(time,
            optionalString(body, "effect"),
            optionalString(body, "locator"),
            body.has("pre_effect_script")
                ? readExpressions(body.get("pre_effect_script")) : List.of());
    }

    private static BedrockAnimation.Loop readLoop(JsonElement json) {
        if (json == null || json.isJsonNull()) return BedrockAnimation.Loop.ONCE;
        if (json.getAsJsonPrimitive().isBoolean()) {
            return json.getAsBoolean() ? BedrockAnimation.Loop.LOOP : BedrockAnimation.Loop.ONCE;
        }
        return "hold_on_last_frame".equals(json.getAsString())
            ? BedrockAnimation.Loop.HOLD_ON_LAST_FRAME
            : BedrockAnimation.Loop.LOOP;
    }

    /**
     * A moment's statements, which Bedrock writes as either one string or a list of them.
     *
     * Each is a command, an entity event or Molang, and which of the three it is comes out of the
     * string itself.
     */
    private static List<AnimationAction> readActions(JsonElement json) {
        List<AnimationAction> statements = new ArrayList<>();
        if (json.isJsonArray()) {
            for (JsonElement statement : json.getAsJsonArray()) {
                statements.add(AnimationAction.of(statement.getAsString()));
            }
        } else {
            statements.add(AnimationAction.of(json.getAsString()));
        }
        return List.copyOf(statements);
    }

    /** A pre-effect script, which computes the variables the effect reads and nothing else. */
    private static List<MolangExpression> readExpressions(JsonElement json) {
        List<MolangExpression> statements = new ArrayList<>();
        if (json.isJsonArray()) {
            for (JsonElement statement : json.getAsJsonArray()) {
                statements.add(MolangParser.parse(statement.getAsString()));
            }
        } else {
            statements.add(MolangParser.parse(json.getAsString()));
        }
        return List.copyOf(statements);
    }

    private static BoneAnimation readBone(JsonObject json, float limit) {
        boolean relativeRotation = json.has("relative_to")
            && "entity".equals(optionalString(json.getAsJsonObject("relative_to"), "rotation"));

        return new BoneAnimation(
            readChannel(json.get("rotation"), limit),
            readChannel(json.get("position"), limit),
            readChannel(json.get("scale"), limit),
            relativeRotation);
    }

    private static String optionalString(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * A channel is either one value that holds for the whole animation, or a map of times to
     * values. The single value case becomes one keyframe at time zero.
     *
     * A keyframe written past the declared length is pulled back onto it, where the engine holds it,
     * so a spline crossing the end of the animation bends towards the value the pack wrote.
     */
    private static AnimationChannel readChannel(JsonElement json, float limit) {
        if (json == null || json.isJsonNull()) return new AnimationChannel(List.of());

        if (json.isJsonObject() && !isKeyframeValue(json.getAsJsonObject())) {
            List<Keyframe> keyframes = new ArrayList<>();
            for (Map.Entry<String, JsonElement> frame : json.getAsJsonObject().entrySet()) {
                float time = Float.parseFloat(frame.getKey());
                keyframes.add(readKeyframe(limit < 0 ? time : Math.min(time, limit), frame.getValue()));
            }
            keyframes.sort(Comparator.comparing(Keyframe::time));
            return new AnimationChannel(List.copyOf(keyframes));
        }

        Vector3Expression value = readVector(json);
        return new AnimationChannel(List.of(new Keyframe(0f, value, value, LerpMode.LINEAR)));
    }

    /** Tells a `{pre, post}` keyframe body apart from a map of times to values. */
    private static boolean isKeyframeValue(JsonObject json) {
        return json.has("pre") || json.has("post") || json.has("vector") || json.has("lerp_mode");
    }

    private static Keyframe readKeyframe(float time, JsonElement json) {
        if (json.isJsonObject() && isKeyframeValue(json.getAsJsonObject())) {
            JsonObject object = json.getAsJsonObject();
            Vector3Expression pre = object.has("pre") ? readVector(object.get("pre")) : null;
            Vector3Expression post = object.has("post") ? readVector(object.get("post")) : null;
            if (pre == null && post == null && object.has("vector")) {
                pre = post = readVector(object.get("vector"));
            }
            if (pre == null) pre = post;
            if (post == null) post = pre;

            LerpMode mode = LerpMode.of(object.has("lerp_mode") ? object.get("lerp_mode").getAsString() : null);
            return new Keyframe(time, pre, post, mode);
        }

        Vector3Expression value = readVector(json);
        return new Keyframe(time, value, value, LerpMode.LINEAR);
    }

    private static Vector3Expression readVector(JsonElement json) {
        if (json == null || json.isJsonNull()) return Vector3Expression.ZERO;

        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            // A single entry means the same value on all three axes, which Bedrock allows for scale.
            if (array.size() == 1) {
                MolangExpression all = MolangParser.parse(primitive(array.get(0)));
                return new Vector3Expression(all, all, all);
            }
            return new Vector3Expression(
                MolangParser.parse(primitive(array.get(0))),
                MolangParser.parse(primitive(array.get(1))),
                MolangParser.parse(primitive(array.get(2))));
        }

        MolangExpression all = MolangParser.parse(primitive(json));
        return new Vector3Expression(all, all, all);
    }

    /** Keyframe components come through as either a number or a Molang string. */
    private static Object primitive(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) return json.getAsDouble();
        return json.getAsString();
    }

}
