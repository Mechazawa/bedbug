package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;

/**
 * The full-screen fade `/camera ... fade` put over this client's view: in, held, and out again.
 *
 * Bedrock combines a fade asked for while one runs rather than starting over. The fade comes in from
 * however opaque the screen is at that moment, as fast as the quicker of the two asks; it starts going
 * out once both would have, and it is gone once both would be; and it keeps the colour of the fade
 * already running. A fade lasts half a second at least, its hold making up the difference.
 */
public final class PlayerFade {
    private static final float SHORTEST = 0.5f;
    private static final float MILLIS = 1000f;

    private static boolean running;
    private static long start;
    private static float from;
    private static long fadedIn;
    private static long fadingOut;
    private static long gone;
    private static int colour;

    private PlayerFade() {
    }

    /** One fade from the server: its three times in seconds and its colour. */
    public static void accept(String body) {
        JsonObject fade = Json.parse(body).getAsJsonObject();
        float in = fade.get("in").getAsFloat();
        float out = fade.get("out").getAsFloat();
        float hold = Math.max(fade.get("hold").getAsFloat(), SHORTEST - in - out);

        long now = System.currentTimeMillis();
        float opacity = opacity(now);
        long inEnds = now + millis(in);
        long outStarts = inEnds + millis(hold);
        long ends = outStarts + millis(out);
        if (running && now < gone) {
            inEnds = Math.min(fadedIn, inEnds);
            outStarts = Math.max(fadingOut, outStarts);
            ends = Math.max(gone, ends);
        } else {
            colour = fade.get("colour").getAsInt() & 0xFFFFFF;
        }

        running = true;
        start = now;
        from = opacity;
        fadedIn = Math.max(now, inEnds);
        fadingOut = Math.max(fadedIn, outStarts);
        gone = Math.max(fadingOut, ends);
    }

    /** How opaque the fade is now, from nothing to the whole screen. */
    public static float opacity() {
        return opacity(System.currentTimeMillis());
    }

    /** The fade's colour as red, green and blue, which is black unless the line named another. */
    public static int colour() {
        return colour;
    }

    public static void draw(Platform.Painter painter) {
        float opacity = opacity();
        if (opacity <= 0f) return;
        painter.fill(0, 0, painter.width(), painter.height(), Math.round(opacity * 255) << 24 | colour);
    }

    public static void clear() {
        running = false;
    }

    private static float opacity(long now) {
        if (!running || now >= gone) return 0f;
        if (now < fadedIn) return Mth.lerp((float) (now - start) / (fadedIn - start), from, 1f);
        if (now < fadingOut) return 1f;
        return 1f - (float) (now - fadingOut) / (gone - fadingOut);
    }

    private static long millis(float seconds) {
        return Math.round(seconds * MILLIS);
    }
}
