package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Finds the packs in `<instance>/addons/` and reads their manifests. */
public final class PackDiscovery {
    /** A pack can sit a few directories down inside an archive; it never sits deeper than this. */
    private static final int MANIFEST_SEARCH_DEPTH = 5;

    private PackDiscovery() {
    }

    public static PackSet scan() {
        Path addons = McAddonPaths.addons();
        List<BedrockPack> packs = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        try {
            Files.createDirectories(addons);
        } catch (IOException error) {
            problems.add("Could not create " + addons + ": " + error.getMessage());
            return PackOrder.sort(packs, problems);
        }

        List<Path> entries = sortedEntries(addons, problems);
        // A listing that failed says nothing about what is installed, so nothing is dropped on it.
        if (entries == null) return PackOrder.sort(packs, problems);

        Set<String> unpacked = new HashSet<>(ReceivedArchives.inCache().held());
        for (Path entry : entries) {
            try {
                if (Files.isDirectory(entry)) {
                    collect(entry, entry.getFileName().toString(), null, packs, problems);
                } else if (Archives.isArchive(entry)) {
                    String sha256 = Archives.sha256(entry);
                    unpacked.add(sha256);
                    packs.addAll(readArchive(entry, sha256, entry.getFileName().toString(), problems));
                }
            } catch (IOException error) {
                problems.add(entry.getFileName() + ": " + error.getMessage());
                McAddon.LOGGER.error("Could not read {}.", entry, error);
            }
        }
        Archives.forgetUnused(McAddonPaths.cache(), unpacked);

        return PackOrder.sort(packs, problems);
    }

    /** Null where the directory could not be listed, which is not the same as holding no packs. */
    private static List<Path> sortedEntries(Path addons, List<String> problems) {
        try (Stream<Path> entries = Files.list(addons)) {
            return entries.sorted().toList();
        } catch (IOException error) {
            problems.add("Could not list " + addons + ": " + error.getMessage());
            return null;
        }
    }

    /**
     * The packs inside one archive, unpacked into the cache under its digest.
     *
     * @param origin the name the archive is reported under
     */
    public static List<BedrockPack> readArchive(Path archive, String sha256, String origin, List<String> problems)
        throws IOException {
        List<BedrockPack> packs = new ArrayList<>();
        collect(Archives.extract(archive, sha256, McAddonPaths.cache()), origin, sha256, packs, problems);
        return packs;
    }

    private static void collect(Path root, String origin, String archive, List<BedrockPack> packs,
        List<String> problems) throws IOException {
        List<Path> manifests;
        try (Stream<Path> walk = Files.walk(root, MANIFEST_SEARCH_DEPTH)) {
            manifests = walk.filter(path -> path.getFileName().toString().equals("manifest.json"))
                .sorted()
                .toList();
        }

        if (manifests.isEmpty()) {
            problems.add(origin + ": no manifest.json found");
            return;
        }

        for (Path manifestFile : manifests) {
            PackManifest manifest = readManifest(manifestFile, origin, problems);
            if (manifest == null) continue;

            BedrockPack pack = new BedrockPack(manifestFile.getParent(), manifest, origin, archive);
            if (pack.role() == PackRole.OTHER) {
                problems.add(origin + ": " + pack.displayName() + " is neither a resource nor a behaviour pack");
                continue;
            }
            packs.add(pack);
        }
    }

    private static PackManifest readManifest(Path file, String origin, List<String> problems) {
        try {
            PackManifest manifest = PackManifest.tryFrom(Json.parseObject(file));
            if (manifest == null) problems.add(origin + ": " + file.getFileName() + " has no usable header uuid");
            return manifest;
        } catch (IOException error) {
            problems.add(origin + ": " + file.getFileName() + " is not readable JSON (" + error.getMessage() + ")");
            return null;
        }
    }
}
