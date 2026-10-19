package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.ActiveAddons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One add-on as the add-on screens show it: the file's resource pack and the behaviour packs that
 * came out of the same file, and whether this game is playing with it now.
 *
 * An add-on the player installed is listed whether it is active or not, and so is one a server
 * handed over for this visit, which is active and not installed.
 */
record AddonEntry(ConvertedPack converted, boolean active) {

    static List<AddonEntry> loaded() {
        Map<java.nio.file.Path, ConvertedPack> every = new LinkedHashMap<>();
        Stream.concat(ActiveAddons.installed().converted().stream(), ActiveAddons.active().converted().stream())
            .forEach(converted -> every.putIfAbsent(converted.root(), converted));
        List<java.nio.file.Path> active = ActiveAddons.active().converted().stream().map(ConvertedPack::root).toList();
        return every.values().stream()
            .map(converted -> new AddonEntry(converted, active.contains(converted.root())))
            .toList();
    }

    /** The resource pack first, then each behaviour pack, every pack once. */
    List<BedrockPack> packs() {
        List<BedrockPack> packs = new ArrayList<>(List.of(converted.pack()));
        converted.behaviours().stream().filter(pack -> pack != converted.pack()).forEach(packs::add);
        return packs;
    }

    /** The authors the first pack naming any gives. */
    List<String> authors() {
        return packs().stream()
            .map(pack -> pack.manifest().metadata().authors())
            .filter(authors -> !authors.isEmpty())
            .findFirst()
            .orElse(List.of());
    }

    /** The description, translated through the pack's own texts, or an empty line where it states none. */
    String description() {
        String resolved = converted.pack().text(converted.pack().manifest().description());
        return resolved == null ? "" : resolved;
    }

    boolean hasSettings() {
        return packs().stream().anyMatch(pack -> !pack.manifest().settings().isEmpty());
    }
}
