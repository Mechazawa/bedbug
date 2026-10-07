package at.ioexception.mcaddon.resource;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.FunctionNames;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.convert.PackConverter;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.McAddonPaths;
import at.ioexception.mcaddon.pack.PackFiles;
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
    private static FunctionNames functions = FunctionNames.NONE;

    private AddonPacks() {
    }

    public static List<ConvertedPack> all() {
        return converted;
    }

    /** The functions every add-on ships, which a function call written in any of them may name. */
    public static FunctionNames functions() {
        return functions;
    }

    /** The namespace the add-on this pack came out of was converted to, or null for a pack of none. */
    public static String namespaceOf(BedrockPack pack) {
        return converted.stream()
            .filter(one -> one.pack().origin().equals(pack.origin()))
            .map(ConvertedPack::namespace)
            .findFirst()
            .orElse(null);
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
            PackFiles.report(pack);
            pack.manifest().report();
            if (pack.role() == PackRole.BEHAVIOUR) {
                behavioursByOrigin.computeIfAbsent(pack.origin(), origin -> new ArrayList<>()).add(pack);
            }
        }

        List<PackConverter.Bundle> bundles = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() == PackRole.RESOURCE) {
                List<BedrockPack> behaviours = behavioursByOrigin.remove(pack.origin());
                bundles.add(new PackConverter.Bundle(pack, behaviours == null ? List.of() : behaviours));
            }
        }
        behavioursByOrigin.values().forEach(alone -> bundles.add(new PackConverter.Bundle(alone.get(0), alone)));

        // Every add-on's namespace is settled before any is converted, because a function file may
        // call a function another add-on ships and has to be written naming that add-on.
        Map<PackConverter.Bundle, String> namespaces = new LinkedHashMap<>();
        bundles.forEach(bundle -> namespaces.put(bundle, Names.namespaceOf(bundle.resources(), bundle.behaviours())));
        Map<String, List<BedrockPack>> behavioursByNamespace = new LinkedHashMap<>();
        namespaces.forEach((bundle, namespace) -> behavioursByNamespace
            .computeIfAbsent(namespace, ignored -> new ArrayList<>()).addAll(bundle.behaviours()));
        functions = FunctionNames.of(behavioursByNamespace);

        converted = namespaces.entrySet().stream()
            .map(entry -> PackConverter.convert(entry.getKey(), entry.getValue(), functions, McAddonPaths.cache()))
            .toList();
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
                    McAddon.MOD_ID + "/" + pack.namespace(), pack.displayName(), pack.root(), type,
                    knownAs(pack), knownVersion(pack)));
            } catch (RuntimeException error) {
                McAddon.LOGGER.error("Could not offer {} to the game.", pack.displayName(), error);
            }
        }
    }

    /**
     * What one installation of an add-on calls itself to another.
     *
     * The uuid in a pack's own manifest header is the one thing two installations of the same
     * add-on share and two different add-ons never do, so it is what a server and a client match on.
     */
    private static String knownAs(ConvertedPack pack) {
        return pack.pack().manifest().uuid().toString();
    }

    /**
     * The version two installations have to agree on before either leaves content out of a sync.
     *
     * The pack's own version separates two releases of one add-on. The mod's version is here because
     * the bytes in a converted pack are a function of the converter as much as of the pack: a client
     * converting the same add-on with a different build of this mod holds different entries, and
     * saying so costs a sync that sends everything instead of one that sends the wrong thing.
     */
    private static String knownVersion(ConvertedPack pack) {
        return pack.pack().manifest().version() + "+" + McAddon.VERSION;
    }
}
