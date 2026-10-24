package at.ioexception.mcaddon.convert;

import java.util.Map;

/**
 * What the two editions call the same status effect, as paths without a namespace. Most match, so
 * only the ones that differ are listed. Bedrock's fatal poison is Java's poison, which Java never
 * makes lethal, so the way back reports poison under its own name.
 */
public final class BedrockEffectNames {
    private static final Map<String, String> TO_JAVA = Map.of(
        "village_hero", "hero_of_the_village",
        "fatal_poison", "poison");

    private static final Map<String, String> TO_BEDROCK = Map.of("hero_of_the_village", "village_hero");

    private BedrockEffectNames() {
    }

    public static String toJava(String path) {
        return TO_JAVA.getOrDefault(path, path);
    }

    public static String toBedrock(String path) {
        return TO_BEDROCK.getOrDefault(path, path);
    }
}
