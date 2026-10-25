package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import at.ioexception.mcaddon.bedrock.ClientBiomeDefinition;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.Names;

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

    private static final Map<String, FogDefinition> BY_BIOME = new LinkedHashMap<>();

    private AddonFogs() {
    }

    public static void register(List<ConvertedPack> packs) {
        DEFINITIONS.clear();
        BY_BIOME.clear();

        for (ConvertedPack pack : packs) {
            FogDefinition.readAll(pack.pack()).forEach(DEFINITIONS::putIfAbsent);
        }
        for (ConvertedPack pack : packs) {
            ClientBiomeDefinition.fogNames(pack.pack()).forEach((biome, fogId) -> {
                FogDefinition fog = DEFINITIONS.get(fogId);
                if (fog == null) return;

                Names.Identifier id = Names.split(biome, pack.namespace());
                BY_BIOME.putIfAbsent(id.namespace() + ":" + id.path(), fog);
            });
        }
        if (!DEFINITIONS.isEmpty()) {
            McAddon.LOGGER.info("Read {} fog settings from add-ons.", DEFINITIONS.size());
        }
    }

    /** Null when no pack describes a fog under that identifier. */
    public static FogDefinition definitionOf(String identifier) {
        return DEFINITIONS.get(identifier);
    }

    /** Null when the biome, named as Java registers it, paints no fog file. */
    public static FogDefinition forBiome(String registeredName) {
        return BY_BIOME.get(registeredName);
    }

    public static Map<String, FogDefinition> all() {
        return DEFINITIONS;
    }
}
