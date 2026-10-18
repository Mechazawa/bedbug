package at.ioexception.mcaddon.resource;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.FunctionNames;
import at.ioexception.mcaddon.convert.Functions;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.convert.PackConverter;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.McAddonPaths;
import at.ioexception.mcaddon.pack.PackFiles;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonEntities;
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
    private AddonPacks() {
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
    public static AddonSet convert(PackSet packs) {
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
        FunctionNames functions = FunctionNames.of(behavioursByNamespace);

        return new AddonSet(packs, namespaces.entrySet().stream()
            .map(entry -> PackConverter.convert(entry.getKey(), entry.getValue(), functions, McAddonPaths.cache()))
            .toList(), functions);
    }

    /** Reports each function line of every active add-on that cannot be translated, without running any. */
    public static void reportFunctionLines() {
        AddonSet active = ActiveAddons.active();
        active.converted().forEach(pack -> pack.behaviours().forEach(behaviour ->
            Functions.untranslatable(behaviour, active.functions().issuedBy(pack.namespace()))
                .forEach(AddonEntities::unsupported)));
    }

    /**
     * Contributes one pack per add-on, in the order the dependency sort decided.
     *
     * Later packs win in Minecraft's resource manager, so the list is walked in reverse: an add-on
     * that another one depends on should be the one overridden, not the one overriding.
     */
    public static void contribute(Consumer<Pack> consumer, PackType type) {
        List<ConvertedPack> offered = ActiveAddons.active().converted();
        for (int index = offered.size() - 1; index >= 0; index--) {
            ConvertedPack pack = offered.get(index);
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
     * The pack's own version separates two releases of one add-on, and the start of its archive's
     * digest two copies of one release whose bytes differ, so two copies of an add-on are never one
     * known pack: the game refuses a join outright when two packs it is offered share one, and a
     * server must not leave out content a client converted from other bytes. The mod's version is
     * here because the bytes in a converted pack are a function of the converter as much as of the
     * pack: a client converting the same add-on with a different build of this mod holds different
     * entries, and saying so costs a sync that sends everything instead of one that sends the wrong
     * thing.
     */
    private static String knownVersion(ConvertedPack pack) {
        String archive = pack.pack().archive();
        return pack.pack().manifest().version() + (archive == null ? "" : "+" + archive.substring(0, 8))
            + "+" + McAddon.VERSION;
    }
}
