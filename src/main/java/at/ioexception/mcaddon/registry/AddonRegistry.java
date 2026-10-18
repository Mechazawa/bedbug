package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.pack.PackSet;

import java.util.List;

/**
 * What this process has registered from add-ons. It only grows.
 *
 * Every writer skips an id its registry already holds, so registering a set twice, or a set that
 * overlaps one registered earlier, adds only what is new. Startup goes through here as well, so the
 * path an add-on takes into a registry is the same whether the registries are frozen or not.
 */
public final class AddonRegistry {
    private AddonRegistry() {
    }

    public static void ensureRegistered(PackSet packs, List<ConvertedPack> converted) {
        RegistryEdits.adding(() -> {
            AddonSounds.register(converted);
            AddonEntities.register(packs);
            // Blocks before items: an item that places a block names it, so the block has to be in
            // the registry by the time the item asking for it is built.
            AddonBlocks.register(packs);
            AddonItems.register(packs);
            AddonParticles.register(converted);
        });
    }
}
