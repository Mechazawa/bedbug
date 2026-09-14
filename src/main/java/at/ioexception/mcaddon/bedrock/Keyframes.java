package at.ioexception.mcaddon.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * One value a Bedrock renderer settings file states across the day, in the ticks Java counts.
 *
 * Bedrock writes such a value three ways: a bare number, a colour, or a map from a time of day to
 * either of those. All three arrive here as a list of stops, so a field that turns out to be
 * constant and a field that sweeps the whole day are read by the same code and written as the same
 * `KeyframeTrack`.
 *
 * A stop carries one channel for a number and three for a colour, which is what tells a written
 * track whether its values are numbers or `#rrggbb` strings.
 *
 * The two editions start the day in different places. Bedrock's time of day runs from nought to one
 * with noon at nought, which is what puts the vanilla sunset at 0.25 and the sunrise at 0.75. Java
 * counts ticks from sunrise with noon at 6000, so a Bedrock time reaches Java a quarter of a day
 * later.
 */
public record Keyframes(List<Stop> stops) {

    /** One moment, and the value at it. */
    public record Stop(int ticks, List<Float> channels) {
    }

    /** How long Java's day is, which is the period a converted track repeats over. */
    public static final int DAY_TICKS = 24000;

    /** The tick Java is at noon, which is where Bedrock's time of day starts. */
    public static final int NOON = 6000;

    /** How many channels a colour carries, before an alpha is put in front of it. */
    private static final int COLOUR_CHANNELS = 3;

    /**
     * The stops a field states, or nothing where it states none this can read.
     *
     * @param declared the field's own value, in any of the three shapes Bedrock writes it in
     */
    public static Keyframes tryFrom(JsonElement declared) {
        if (declared == null || declared.isJsonNull()) return null;

        Map<Integer, List<Float>> byTick = new LinkedHashMap<>();
        if (declared.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : declared.getAsJsonObject().entrySet()) {
                Float time = fraction(entry.getKey());
                List<Float> channels = channels(entry.getValue());
                if (time != null && channels != null) byTick.putIfAbsent(tickOf(time), channels);
            }
        } else {
            List<Float> channels = channels(declared);
            if (channels != null) byTick.put(0, channels);
        }
        if (byTick.isEmpty()) return null;

        List<Stop> stops = byTick.entrySet().stream()
            .map(entry -> new Stop(entry.getKey(), entry.getValue()))
            .sorted((left, right) -> Integer.compare(left.ticks(), right.ticks()))
            .toList();
        return new Keyframes(stops);
    }

    /** The tick Java is at a Bedrock time of day, which is a quarter of a day behind it. */
    public static int tickOf(float time) {
        int ticks = Math.round(time * DAY_TICKS) + NOON;
        return Math.floorMod(ticks, DAY_TICKS);
    }

    /**
     * The value at one tick, interpolated between the stops on either side of it.
     *
     * The day is a loop, so a tick before the first stop or after the last one falls between the two
     * across the join rather than off the end.
     */
    public List<Float> at(int ticks) {
        int moment = Math.floorMod(ticks, DAY_TICKS);
        if (stops.size() == 1) return stops.getFirst().channels();

        int next = 0;
        while (next < stops.size() && stops.get(next).ticks() <= moment) next++;

        Stop before = stops.get(Math.floorMod(next - 1, stops.size()));
        Stop after = stops.get(next % stops.size());

        int span = Math.floorMod(after.ticks() - before.ticks(), DAY_TICKS);
        int since = Math.floorMod(moment - before.ticks(), DAY_TICKS);
        float weight = span == 0 ? 0f : (float) since / span;
        return blend(before.channels(), after.channels(), weight);
    }

    /** Every tick a stop stands at, which is where a curve folded with another one is sampled. */
    public List<Integer> times() {
        return stops.stream().map(Stop::ticks).toList();
    }

    /**
     * A curve worked out at each of these ticks, which is how two of them are folded into one.
     *
     * The ticks are the moments both curves being folded state something at, so the fold keeps every
     * corner either of them has and adds none of its own.
     */
    public static Keyframes sampled(Collection<Integer> ticks,
        IntFunction<List<Float>> value) {
        List<Stop> stops = ticks.stream().distinct().sorted()
            .map(tick -> new Stop(tick, value.apply(tick))).toList();
        return stops.isEmpty() ? null : new Keyframes(stops);
    }

    /** The highest a single-channel curve reaches, which is what a fraction of it is measured on. */
    public float peak() {
        return (float) stops.stream().mapToDouble(stop -> stop.channels().getFirst()).max().orElse(0);
    }

    /** The same colours with an alpha in front, which is what an ARGB attribute is written with. */
    public Keyframes withAlpha(float alpha) {
        return new Keyframes(stops.stream().map(stop -> {
            List<Float> channels = new ArrayList<>();
            channels.add(alpha);
            channels.addAll(stop.channels());
            return new Stop(stop.ticks(), List.copyOf(channels));
        }).toList());
    }

    /**
     * The `AttributeTrack` a timeline holds these under.
     *
     * Bedrock interpolates between its stops, so the easing is linear. A modifier of null is the
     * override a track carries when it names none, which is what a settings file stating the value
     * outright means.
     */
    public JsonObject track(String modifier) {
        JsonArray written = new JsonArray();
        for (Stop stop : stops) {
            JsonObject keyframe = new JsonObject();
            keyframe.addProperty("ticks", stop.ticks());
            if (stop.channels().size() == 1) {
                keyframe.addProperty("value", stop.channels().getFirst());
            } else {
                keyframe.addProperty("value", hex(stop.channels()));
            }
            written.add(keyframe);
        }

        JsonObject track = new JsonObject();
        track.addProperty("ease", "linear");
        track.add("keyframes", written);
        if (modifier != null) track.addProperty("modifier", modifier);
        return track;
    }

    /** A colour as the `#rrggbb` or `#aarrggbb` string Java's attribute codecs read. */
    private static String hex(List<Float> channels) {
        StringBuilder written = new StringBuilder("#");
        for (float channel : channels) {
            written.append("%02x".formatted(Math.clamp(Math.round(channel), 0, 255)));
        }
        return written.toString();
    }

    private static List<Float> blend(List<Float> before, List<Float> after, float weight) {
        List<Float> mixed = new ArrayList<>(before.size());
        for (int channel = 0; channel < before.size(); channel++) {
            float low = before.get(channel);
            float high = channel < after.size() ? after.get(channel) : low;
            mixed.add(low + (high - low) * weight);
        }
        return List.copyOf(mixed);
    }

    /**
     * One value, as the channels it holds.
     *
     * A number is one channel. A colour is three, written either as an array of components or as the
     * hex string Bedrock uses everywhere else, and an array carrying an alpha behind them is read as
     * the three: Java's own sky attributes are opaque colours.
     */
    private static List<Float> channels(JsonElement declared) {
        if (declared.isJsonArray()) {
            JsonArray components = declared.getAsJsonArray();
            if (components.size() < COLOUR_CHANNELS) return null;

            List<Float> channels = new ArrayList<>(COLOUR_CHANNELS);
            for (int index = 0; index < COLOUR_CHANNELS; index++) {
                channels.add(components.get(index).getAsFloat());
            }
            return List.copyOf(channels);
        }
        if (!declared.isJsonPrimitive()) return null;

        if (declared.getAsJsonPrimitive().isString()) {
            String text = declared.getAsString();
            int packed = at.ioexception.mcaddon.util.Json.colour(text);
            if (packed < 0) return null;

            return List.of((float) (packed >> 16 & 0xFF), (float) (packed >> 8 & 0xFF),
                (float) (packed & 0xFF));
        }
        return List.of(declared.getAsFloat());
    }

    private static Float fraction(String key) {
        try {
            return Float.parseFloat(key);
        } catch (NumberFormatException notATime) {
            return null;
        }
    }
}
