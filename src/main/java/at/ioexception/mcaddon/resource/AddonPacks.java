package at.ioexception.mcaddon.resource;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.bedrock.behaviour.ItemDefinition;
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
     * Converts each resource pack, with its add-on's items in hand.
     *
     * An item is declared in a behaviour pack and drawn from a texture the resource pack ships, so
     * the two are paired by the file they came out of before either is converted.
     */
    public static void convert(PackSet packs) {
        Map<String, List<ItemDefinition>> itemsByOrigin = new LinkedHashMap<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() == PackRole.BEHAVIOUR) {
                itemsByOrigin.computeIfAbsent(pack.origin(), origin -> new ArrayList<>())
                    .addAll(ItemDefinition.readAll(pack));
            }
        }

        List<ConvertedPack> results = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() != PackRole.RESOURCE) continue;
            results.add(PackConverter.convert(pack,
                itemsByOrigin.getOrDefault(pack.origin(), List.of()), McAddonPaths.cache()));
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
