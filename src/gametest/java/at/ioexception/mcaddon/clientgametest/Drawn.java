package at.ioexception.mcaddon.clientgametest;

import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many times the client has drawn each sprite, counted from the first frame, the colour of the
 * last rectangle filled over the whole screen, and every line of text drawn since it was cleared.
 */
public final class Drawn {
    private static final Map<String, Long> SPRITES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> TEXTS = ConcurrentHashMap.newKeySet();
    private static volatile int screenFill;

    private Drawn() {
    }

    public static void record(Identifier sprite) {
        SPRITES.merge(sprite.toString(), 1L, Long::sum);
    }

    public static long times(String sprite) {
        return SPRITES.getOrDefault(sprite, 0L);
    }

    public static void recordScreenFill(int argb) {
        screenFill = argb;
    }

    /** The colour, opacity included, of the last rectangle filled over the whole screen. */
    public static int lastScreenFill() {
        return screenFill;
    }

    public static void recordText(String line) {
        TEXTS.add(line);
    }

    /** Whether a line holding this was drawn since the texts were last cleared. */
    public static boolean drewText(String part) {
        return TEXTS.stream().anyMatch(line -> line.contains(part));
    }

    public static void clearTexts() {
        TEXTS.clear();
    }
}
