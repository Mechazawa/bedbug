package at.ioexception.mcaddon.pack;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Where the mod looks for packs and where it keeps what it has converted. */
public final class McAddonPaths {
    private McAddonPaths() {
    }

    /** Packs the user drops in. Created on first run so it is discoverable without documentation. */
    public static Path addons() {
        return FabricLoader.getInstance().getGameDir().resolve("addons");
    }

    /** Extracted archives and converted output. Safe to delete; it is rebuilt from the packs. */
    public static Path cache() {
        return FabricLoader.getInstance().getGameDir().resolve(".mcaddon");
    }
}
