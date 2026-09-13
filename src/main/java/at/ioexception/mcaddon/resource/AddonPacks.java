package at.ioexception.mcaddon.resource;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.PackConverter;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.McAddonPaths;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The converted packs, and how the game gets told about them.
 *
 * Conversion happens during mod initialisation because the sound events a pack defines have to be
 * registered before the registries freeze. By the time either pack repository is built, the
 * directories already exist and are only pointed at.
 */
public final class AddonPacks {
    private static List<ConvertedPack> converted = List.of();

    private AddonPacks() {
    }

    public static List<ConvertedPack> all() {
        return converted;
    }

    /**
     * Converts each resource pack, with the behaviour packs that came out of the same file.
     *
     * An item is declared in a behaviour pack and drawn from a texture the resource pack ships, and
     * the same holds for a block, so the two halves are paired before either is converted.
     *
     * An add-on that ships only a behaviour pack is converted as well, with itself standing in for
     * the half it has not got. Its items and blocks are registered either way, and without the
     * conversion each of them is a model the game looks for and never finds.
     */
    public static void convert(PackSet packs) {
        Map<String, List<BedrockPack>> behavioursByOrigin = new LinkedHashMap<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() == PackRole.BEHAVIOUR) {
                behavioursByOrigin.computeIfAbsent(pack.origin(), origin -> new ArrayList<>()).add(pack);
            }
        }

        List<ConvertedPack> results = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() == PackRole.RESOURCE) {
                List<BedrockPack> behaviours = behavioursByOrigin.remove(pack.origin());
                results.add(PackConverter.convert(new PackConverter.Bundle(pack,
                    behaviours == null ? List.of() : behaviours), McAddonPaths.cache()));
            }
        }
        for (List<BedrockPack> alone : behavioursByOrigin.values()) {
            results.add(PackConverter.convert(
                new PackConverter.Bundle(alone.get(0), alone), McAddonPaths.cache()));
        }
        converted = List.copyOf(results);
    }

    /**
     * Contributes one pack per add-on, in the order the dependency sort decided.
     *
     * Later packs win in Minecraft's resource manager, so the list is walked in reverse: an add-on
     * that another one depends on should be the one overridden, not the one overriding.
     */
    public static void contribute(Consumer<Pack> consumer, PackType type) {
        for (int index = converted.size() - 1; index >= 0; index--) {
            ConvertedPack pack = converted.get(index);
            try {
                consumer.accept(Platform.directoryPack(
                    McAddon.MOD_ID + "/" + pack.namespace(), pack.displayName(), pack.root(), type));
            } catch (RuntimeException error) {
                McAddon.LOGGER.error("Could not offer {} to the game.", pack.displayName(), error);
            }
        }
    }
}
