package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import at.ioexception.mcaddon.convert.ConvertedPack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fogs a pack describes, by the identifier a `/fog` names.
 *
 * Nothing is registered with the game: Java has no fog registry, and a fog only ever reaches a
 * client as the numbers the server resolved for it. This is the lookup the server does that with.
 */
public final class AddonFogs {
    private static final Map<String, FogDefinition> DEFINITIONS = new LinkedHashMap<>();

    private AddonFogs() {
    }

    public static void register(List<ConvertedPack> packs) {
        DEFINITIONS.clear();

        for (ConvertedPack pack : packs) {
            FogDefinition.readAll(pack.pack()).forEach(DEFINITIONS::putIfAbsent);
        }
        if (!DEFINITIONS.isEmpty()) {
            McAddon.LOGGER.info("Read {} fog settings from add-ons.", DEFINITIONS.size());
        }
    }

    /** Null when no pack describes a fog under that identifier. */
    public static FogDefinition definitionOf(String identifier) {
        return DEFINITIONS.get(identifier);
    }

    public static Map<String, FogDefinition> all() {
        return DEFINITIONS;
    }
}
