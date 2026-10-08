package at.ioexception.mcaddon.clientgametest;

import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** How many times the client has drawn each sprite, counted from the first frame. */
public final class Drawn {
    private static final Map<String, Long> SPRITES = new ConcurrentHashMap<>();

    private Drawn() {
    }

    public static void record(Identifier sprite) {
        SPRITES.merge(sprite.toString(), 1L, Long::sum);
    }

    public static long times(String sprite) {
        return SPRITES.getOrDefault(sprite, 0L);
    }
}
