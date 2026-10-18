package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A path a free camera is carried along: a behaviour pack's `camera/splines/` file that
 * `/camera play_spline` names, or the curve a script hands `Camera.playAnimation`.
 *
 * The fields are spelt as Bedrock's own spline definition spells them. `control_points` are places
 * in the world; a Catmull-Rom curve passes through every one of them, the first and the last
 * included, and a linear one runs straight from each to the next. How far along the curve the camera
 * is at a moment is what `progress_key_frames` says, from 0 at the first point to 1 at the last, and
 * `rotation_key_frames` say how it is turned, a pitch, a yaw and a roll. Between two key frames the
 * value moves by the easing the later one names, and before the first and after the last it stays
 * at that frame's value. With no progress frames the camera moves evenly over `total_time`, and with
 * no rotation frames it keeps the rotation it had.
 *
 * The curve is spread evenly over the points rather than over the distance, so the camera moves
 * faster along a long stretch between two points than along a short one.
 */
public record CameraSpline(boolean catmullRom, List<Vec3> points, float totalSeconds, List<Key> progress,
    List<Key> rotation) implements CameraInstruction {

    static final String KIND = "spline";

    /** A value the curve is at by a moment, and the easing that takes it there from the frame before. */
    public record Key(float seconds, Vec3 value, Easing easing) {
    }

    private static final double HALF = 0.5;

    /** The definition a spline file holds, or null for a file holding none. */
    public static CameraSpline fromFile(JsonObject file) {
        JsonObject body = body(file);
        return body == null ? null : read(body);
    }

    /** The name a spline file gives its spline, or null where it states none. */
    public static String nameIn(JsonObject file) {
        JsonObject body = body(file);
        if (body == null) return null;
        String name = Json.text(body, "name", null);
        return name != null ? name : Json.text(body, "identifier", null);
    }

    /** Whether any rotation frame turns the camera about its own forward axis, which Java's camera cannot. */
    public boolean rolls() {
        return rotation.stream().anyMatch(key -> key.value().z != 0);
    }

    /** The file's own object when it carries the points, or else the one object inside it that does. */
    private static JsonObject body(JsonObject file) {
        if (file.has("control_points")) return file;
        return file.keySet().stream().map(file::get)
            .filter(value -> value.isJsonObject() && value.getAsJsonObject().has("control_points"))
            .map(JsonElement::getAsJsonObject).findFirst().orElse(null);
    }

    /** Why this curve cannot be played, or null when it can. */
    public String refusal() {
        if (points.size() < 2) return "A camera spline needs at least two control points.";
        if (!(totalSeconds > 0)) return "A camera spline needs a total time above zero.";
        return null;
    }

    /** Where the camera is, and how it is turned, this many seconds into the curve. */
    public CameraPlacement placementAt(float elapsed, CameraPlacement before) {
        float along = progress.isEmpty() ? elapsed / totalSeconds : (float) valueAt(progress, elapsed).x;
        Vec3 position = positionAt(Math.max(0f, Math.min(1f, along)));
        if (rotation.isEmpty()) return new CameraPlacement(position, before.xRot(), before.yRot(), true);

        Vec3 turned = valueAt(rotation, elapsed);
        return new CameraPlacement(position, (float) turned.x, (float) turned.y, true);
    }

    /** The point `along` of the way down the curve, spread evenly over its stretches. */
    Vec3 positionAt(float along) {
        int stretches = points.size() - 1;
        float scaled = along * stretches;
        int stretch = Math.min(stretches - 1, (int) Math.floor(scaled));
        double within = scaled - stretch;

        Vec3 from = points.get(stretch);
        Vec3 to = points.get(stretch + 1);
        if (!catmullRom) return from.lerp(to, within);

        Vec3 before = stretch > 0 ? points.get(stretch - 1) : from.scale(2).subtract(to);
        Vec3 after = stretch + 2 < points.size() ? points.get(stretch + 2) : to.scale(2).subtract(from);
        double squared = within * within;
        double cubed = squared * within;
        return from.scale(2)
            .add(to.subtract(before).scale(within))
            .add(before.scale(2).subtract(from.scale(5)).add(to.scale(4)).subtract(after).scale(squared))
            .add(from.scale(3).subtract(before).subtract(to.scale(3)).add(after).scale(cubed))
            .scale(HALF);
    }

    private static Vec3 valueAt(List<Key> keys, float elapsed) {
        Key first = keys.get(0);
        if (elapsed <= first.seconds()) return first.value();
        for (int i = 1; i < keys.size(); i++) {
            Key from = keys.get(i - 1);
            Key to = keys.get(i);
            if (elapsed < to.seconds()) {
                float span = to.seconds() - from.seconds();
                float eased = to.easing().at(span <= 0f ? 1f : (elapsed - from.seconds()) / span);
                return from.value().add(to.value().subtract(from.value()).scale(eased));
            }
        }
        return keys.get(keys.size() - 1).value();
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject json() {
        JsonObject json = new JsonObject();
        json.addProperty("spline_type", catmullRom ? "catmull_rom" : "linear");
        json.addProperty("total_time", totalSeconds);
        JsonArray controlPoints = new JsonArray();
        points.forEach(point -> {
            JsonObject entry = new JsonObject();
            entry.add("position", Vectors.write(point));
            controlPoints.add(entry);
        });
        json.add("control_points", controlPoints);
        json.add("progress_key_frames", keys(progress, "progress", true));
        json.add("rotation_key_frames", keys(rotation, "rotation", false));
        return json;
    }

    private static JsonArray keys(List<Key> keys, String field, boolean single) {
        JsonArray array = new JsonArray();
        keys.forEach(key -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("time", key.seconds());
            if (single) {
                entry.addProperty(field, key.value().x);
            } else {
                entry.add(field, Vectors.write(key.value()));
            }
            entry.addProperty("easing", key.easing().bedrockName());
            array.add(entry);
        });
        return array;
    }

    /**
     * A definition in Bedrock's spelling. A Catmull-Rom curve is named `catmull_rom` or `CatmullRom`,
     * and anything else is linear. An easing nobody has heard of is linear too.
     */
    public static CameraSpline read(JsonObject json) {
        String type = Json.text(json, "spline_type", "linear").replace("_", "").toLowerCase(Locale.ROOT);
        List<Vec3> points = new ArrayList<>();
        for (JsonElement point : Json.list(json, "control_points")) {
            Vec3 position = Vectors.read(point.isJsonObject() && point.getAsJsonObject().has("position")
                ? point.getAsJsonObject().get("position") : point);
            if (position != null) points.add(position);
        }

        List<Key> progress = new ArrayList<>();
        for (JsonElement frame : Json.list(json, "progress_key_frames")) {
            if (!frame.isJsonObject()) continue;
            JsonObject body = frame.getAsJsonObject();
            progress.add(new Key((float) Json.number(body, "time", 0),
                new Vec3(Json.number(body, "progress", 0), 0, 0), easing(body)));
        }

        List<Key> rotation = new ArrayList<>();
        for (JsonElement frame : Json.list(json, "rotation_key_frames")) {
            if (!frame.isJsonObject()) continue;
            JsonObject body = frame.getAsJsonObject();
            Vec3 turned = Vectors.read(body.get("rotation"));
            if (turned != null) rotation.add(new Key((float) Json.number(body, "time", 0), turned, easing(body)));
        }

        return new CameraSpline(type.equals("catmullrom"), List.copyOf(points),
            (float) Json.number(json, "total_time", 0), List.copyOf(progress), List.copyOf(rotation));
    }

    private static Easing easing(JsonObject frame) {
        Easing easing = Easing.tryFrom(Json.text(frame, "easing", "linear"));
        return easing == null ? Easing.LINEAR : easing;
    }
}
