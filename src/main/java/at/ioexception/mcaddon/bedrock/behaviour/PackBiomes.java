package at.ioexception.mcaddon.bedrock.behaviour;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tags each biome a pack declares carries, by the Java id it was converted to.
 *
 * Bedrock selects biomes by tag in every file that names some, and a pack's own biome carries
 * whichever tags its pack gave it, `nether` and `overworld` among them. No table of Java's own
 * biomes can know those, so a filter asks here first.
 */
public final class PackBiomes {
    private static final Map<String, List<String>> TAGS = new ConcurrentHashMap<>();

    private PackBiomes() {
    }

    public static void declare(String id, List<String> tags) {
        TAGS.put(id, List.copyOf(tags));
    }

    /** Null for a biome no pack declared, which is every one the game ships. */
    public static List<String> tagsOf(String id) {
        return TAGS.get(id);
    }

    /** Whether any pack's biome carries the tag, which makes it a tag a filter can be answered on. */
    public static boolean anyCarries(String tag) {
        return TAGS.values().stream().anyMatch(tags -> tags.contains(tag));
    }
}
