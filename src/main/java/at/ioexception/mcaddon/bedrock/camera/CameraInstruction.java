package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One thing the server tells a player's client to do with its camera.
 *
 * Bedrock sends a camera instruction with a part per order, and so does this: a view replaces the
 * camera, a target turns a free camera after an entity, an attachment hangs the camera off an entity
 * and a spline carries a free camera along a curve. Each reaches the client as one message naming
 * its kind beside its own fields.
 */
public sealed interface CameraInstruction permits CameraView, CameraTarget, CameraAttachment, CameraSpline {
    String FIELD = "instruction";

    String kind();

    /** The instruction's own fields, which `decode` reads back once the kind has picked the type. */
    JsonObject json();

    default String encode() {
        JsonObject json = json();
        json.addProperty(FIELD, kind());
        return json.toString();
    }

    /** What arrived, or a cleared view for a message that is not one. */
    static CameraInstruction decode(String encoded) {
        JsonElement parsed = Json.parse(encoded);
        if (!parsed.isJsonObject()) return CameraView.CLEARED;

        JsonObject json = parsed.getAsJsonObject();
        return switch (Json.text(json, FIELD, "")) {
            case CameraTarget.KIND -> CameraTarget.read(json);
            case CameraAttachment.KIND -> CameraAttachment.read(json);
            case CameraSpline.KIND -> CameraSpline.read(json);
            default -> CameraView.read(json);
        };
    }
}
