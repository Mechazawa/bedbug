package at.ioexception.mcaddon.sync;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.Archives;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.McAddonPaths;
import at.ioexception.mcaddon.pack.PackDiscovery;
import at.ioexception.mcaddon.pack.ReceivedArchives;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The archives a server hands to a client joining it, and the manifest naming them.
 *
 * Each is a copy under `.mcaddon/served/<sha256>.mcaddon`, so an operator replacing a file in
 * `addons/` while the server runs cannot make it send bytes that do not match the digest it
 * promised. A pack directory is zipped into a copy whose bytes depend only on its files.
 *
 * A development run also serves every archive in the directory the `mcaddon.served` property
 * names, which the server never loaded, so a client test can join and be sent an add-on it lacks
 * while the server and the client share one process.
 */
public final class ServedArchives {
    private static AddonManifest manifest;
    private static final Map<String, Path> FILES = new HashMap<>();

    private ServedArchives() {
    }

    public static synchronized AddonManifest manifest() {
        if (manifest == null) manifest = prepare();
        return manifest;
    }

    /**
     * Prepares the copies as a dedicated server starts, before anybody can move a file in `addons/`
     * from under it, and forgets them as any server starts, because a world opened again after its
     * host visited another server has more registered behind it than before. A world opened alone
     * prepares nothing until a guest joins it.
     */
    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING
            .register(server -> forget());
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (server.isDedicatedServer()) manifest();
        });
    }

    /** Forgets the manifest, so the next client to need one is handed what is registered then. */
    public static synchronized void forget() {
        manifest = null;
        FILES.clear();
    }

    /** The copy of an archive the manifest lists, or null for one it does not. */
    public static synchronized Path file(String sha256) {
        return FILES.get(sha256);
    }

    private static AddonManifest prepare() {
        Path served = McAddonPaths.cache().resolve("served");
        Map<String, List<BedrockPack>> byOrigin = new LinkedHashMap<>();
        ActiveAddons.installed().packs().packs()
            .forEach(pack -> byOrigin.computeIfAbsent(pack.origin(), origin -> new ArrayList<>()).add(pack));

        List<AddonManifest.Entry> entries = new ArrayList<>();
        byOrigin.forEach((origin, packs) -> {
            try {
                Files.createDirectories(served);
                Path snapshot = snapshot(McAddonPaths.addons().resolve(origin), packs.get(0).archive(), served);
                if (snapshot != null) entries.add(entry(snapshot, true, packs));
            } catch (IOException failure) {
                McAddon.LOGGER.error("Could not prepare {} to hand to clients.", origin, failure);
            }
        });
        entries.addAll(development(served));
        entries.addAll(dormant(entries));
        McAddon.LOGGER.info("Serving {} add-ons to clients that join.", entries.size());
        return new AddonManifest(McAddon.VERSION, List.copyOf(entries));
    }

    /**
     * Copies an installed add-on into `served`, or null when the file no longer holds the bytes the
     * server loaded at startup.
     */
    private static Path snapshot(Path source, String loaded, Path served) throws IOException {
        Path copy = Files.createTempFile(served, "copy", ".part");
        if (Files.isDirectory(source)) {
            Archives.zip(source, copy);
        } else {
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        String sha256 = Archives.sha256(copy);
        if (loaded != null && !loaded.equals(sha256)) {
            Files.delete(copy);
            McAddon.LOGGER.error("{} changed since the server started, so it is not handed to clients.",
                source.getFileName());
            return null;
        }
        return Files.move(copy, served.resolve(sha256 + ".mcaddon"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static AddonManifest.Entry entry(Path snapshot, boolean active, List<BedrockPack> packs)
        throws IOException {
        String sha256 = snapshot.getFileName().toString().replace(".mcaddon", "");
        FILES.put(sha256, snapshot);
        return new AddonManifest.Entry(sha256, Files.size(snapshot), active, packs.stream()
            .map(pack -> new AddonManifest.PackId(pack.manifest().uuid().toString(),
                pack.manifest().version().toString(), pack.displayName()))
            .toList());
    }

    /**
     * The archives registered in this process that nothing above serves, which a host
     * holds from servers it visited before opening its world to LAN. A guest is sent ids for their
     * entries by Fabric's registry sync, so it is handed them too, to register and keep dormant.
     * They sit in the received cache already, under their digest, so they are served from there.
     */
    private static List<AddonManifest.Entry> dormant(List<AddonManifest.Entry> served) {
        List<AddonManifest.Entry> entries = new ArrayList<>();
        ReceivedArchives received = ReceivedArchives.inCache();
        AddonRegistry.archives().forEach((sha256, packs) -> {
            if (served.stream().anyMatch(entry -> entry.sha256().equals(sha256)) || !received.has(sha256)) return;
            try {
                entries.add(entry(received.archive(sha256), false, packs));
            } catch (IOException failure) {
                McAddon.LOGGER.error("Could not hand out the dormant add-on {}.", sha256, failure);
            }
        });
        return entries;
    }

    private static List<AddonManifest.Entry> development(Path served) {
        String named = System.getProperty("mcaddon.served");
        if (named == null || !net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return List.of();
        }
        List<AddonManifest.Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(named))) {
            for (Path archive : files.filter(file -> file.toString().endsWith(".mcaddon")).sorted().toList()) {
                Path snapshot = snapshot(archive, null, served);
                String sha256 = snapshot.getFileName().toString().replace(".mcaddon", "");
                entries.add(entry(snapshot, true,
                    PackDiscovery.readArchive(snapshot, sha256, archive.getFileName().toString(), new ArrayList<>())));
            }
        } catch (IOException failure) {
            McAddon.LOGGER.error("Could not read the archives in {}.", named, failure);
        }
        return entries;
    }
}
