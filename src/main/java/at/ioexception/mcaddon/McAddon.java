package at.ioexception.mcaddon;

import at.ioexception.mcaddon.pack.PackDiscovery;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class McAddon implements ModInitializer {
    public static final String MOD_ID = "mcaddon";
    public static final Logger LOGGER = LoggerFactory.getLogger("Bedrock Add-ons");

    private static PackSet packs = new PackSet(List.of(), List.of());

    public static Identifier id(String path) {
        return Platform.id(MOD_ID, path);
    }

    /** The packs found at startup. Empty until the mod has initialised. */
    public static PackSet packs() {
        return packs;
    }

    /**
     * Scanning happens here, not lazily, because sound events, items, blocks and entity types all
     * have to be registered before the registries freeze, and every one of them comes from a pack.
     */
    @Override
    public void onInitialize() {
        packs = PackDiscovery.scan();
        packs.report().forEach(LOGGER::info);
    }
}
