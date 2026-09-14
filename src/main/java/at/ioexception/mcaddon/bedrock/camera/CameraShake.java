package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One shake, as `/camerashake add` describes it.
 *
 * The wobble is a sum of sines rather than a random walk, so every client told about the same shake
 * draws the same one and a test can say where it puts the camera. Its amplitude falls to zero over
 * the length the command gave, which is what keeps the view from jumping back when it runs out.
 *
 * A length of zero is how `/camerashake stop` reaches the client: there is nothing left to run.
 */
public record CameraShake(float intensity, float seconds, boolean rotational) {
    public static final CameraShake STOP = new CameraShake(0f, 0f, false);

    /** How far a shake of intensity one moves the view, and how far it turns it. */
    private static final float BLOCKS = 0.35f;
    private static final float DEGREES = 8f;

    private static final float[] RATES = {13.1f, 17.3f, 21.7f};
    private static final float[] PHASES = {0f, 1.7f, 3.1f};

    public boolean stops() {
        return seconds <= 0f;
    }

    public boolean finishedAt(float elapsedSeconds) {
        return elapsedSeconds >= seconds;
    }

    /** The placement this shake leaves, given how long it has been running. */
    public CameraPlacement displace(CameraPlacement placement, float elapsedSeconds) {
        if (stops()) return placement;

        float amplitude = intensity * Math.max(0f, 1f - elapsedSeconds / seconds);
        if (amplitude <= 0f) return placement;

        float first = wave(elapsedSeconds, 0) * amplitude;
        float second = wave(elapsedSeconds, 1) * amplitude;
        float third = wave(elapsedSeconds, 2) * amplitude;

        if (rotational) {
            return new CameraPlacement(placement.position(), placement.xRot() + first * DEGREES,
                placement.yRot() + second * DEGREES, placement.detached());
        }
        return new CameraPlacement(placement.position().add(first * BLOCKS, second * BLOCKS,
            third * BLOCKS), placement.xRot(), placement.yRot(), placement.detached());
    }

    /** Three rates that share no common period, so the wobble does not repeat visibly. */
    private static float wave(float elapsedSeconds, int axis) {
        return (float) Math.sin(elapsedSeconds * RATES[axis] + PHASES[axis]);
    }

    public String encode() {
        JsonObject json = new JsonObject();
        json.addProperty("intensity", intensity);
        json.addProperty("seconds", seconds);
        json.addProperty("rotational", rotational);
        return json.toString();
    }

    public static CameraShake decode(String encoded) {
        JsonElement parsed = Json.parse(encoded);
        if (!parsed.isJsonObject()) return STOP;

        JsonObject json = parsed.getAsJsonObject();
        return new CameraShake(number(json, "intensity"), number(json, "seconds"),
            json.has("rotational") && json.get("rotational").getAsBoolean());
    }

    private static float number(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? 0f : value.getAsFloat();
    }
}
