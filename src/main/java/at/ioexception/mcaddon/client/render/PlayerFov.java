package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.camera.Easing;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;

/**
 * The field of view `/camera ... fov_set` gave this client, eased from wherever it stood, and
 * `fov_clear`, which eases back to the player's own setting.
 *
 * What is held is the base the game works the view out from, in place of the player's setting, so
 * what sprinting, a bow drawn back or being under water do to the view still happens on top of it.
 */
public final class PlayerFov {
    private static final float MILLIS = 1000f;

    private static Float from;
    private static Float to;
    private static boolean set;
    private static long start;
    private static float seconds;
    private static Easing easing = Easing.LINEAR;

    private PlayerFov() {
    }

    /** One instruction from the server: the field of view, or none for the player's own, and its ease. */
    public static void accept(String body, float own) {
        JsonObject asked = Json.parse(body).getAsJsonObject();
        float now = base(own);
        from = now;
        to = asked.has("fov") ? asked.get("fov").getAsFloat() : null;
        set = true;
        start = System.currentTimeMillis();
        seconds = asked.has("seconds") ? asked.get("seconds").getAsFloat() : 0f;
        Easing named = asked.has("easing") ? Easing.tryFrom(Json.text(asked, "easing", "")) : null;
        easing = named == null ? Easing.LINEAR : named;
    }

    /** The base field of view in degrees where the player's own setting is `own`. */
    public static float base(float own) {
        if (!set) return own;

        float wanted = to == null ? own : to;
        float progress = seconds <= 0f ? 1f : (System.currentTimeMillis() - start) / (seconds * MILLIS);
        if (progress >= 1f) {
            if (to == null) set = false;
            return wanted;
        }
        return Mth.lerp(easing.at(progress), from, wanted);
    }

    public static void clear() {
        set = false;
    }
}
