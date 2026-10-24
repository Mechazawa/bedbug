package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/**
 * The loot tables of Bedrock's own mobs, which a pack names without shipping.
 *
 * A pack that reuses a vanilla mob's behaviour writes `loot_tables/entities/sheep.json` and leaves
 * the file to the game. Java keeps a table for the same mob and the same moment, so a path no pack
 * ships is answered by Java's own: `entities/<mob>` for what the mob drops, and `shearing/<mob>`
 * for a name ending in `_shear`.
 */
public final class VanillaLoot {
    private static final String ENTITIES = "loot_tables/entities/";

    private VanillaLoot() {
    }

    public static Identifier orJavasOwn(MinecraftServer server, Identifier packs, String path) {
        if (server == null || !path.startsWith(ENTITIES) || Platform.hasLootTable(server, packs)) return packs;

        String name = path.substring(ENTITIES.length()).replaceAll("\\.json$", "");
        String javas = name.endsWith("_shear") ? "shearing/" + name.substring(0, name.length() - "_shear".length())
            : "entities/" + name.replaceAll("_sheared$", "");
        Identifier counterpart = Platform.id("minecraft", javas);
        return Platform.hasLootTable(server, counterpart) ? counterpart : packs;
    }
}
