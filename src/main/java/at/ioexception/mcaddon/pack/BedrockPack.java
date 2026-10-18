package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.LangFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * One pack on disk, its manifest read.
 *
 * A `.mcaddon` holds several of these. Every pack is a directory by the time it reaches here, so an
 * archive is extracted once into the cache rather than read through a zip filesystem on every
 * access.
 */
public final class BedrockPack {
    private final Path root;
    private final PackManifest manifest;
    private final String origin;
    private final String archive;
    private final Path overlay;
    private Map<String, String> texts;

    public BedrockPack(Path root, PackManifest manifest, String origin) {
        this(root, manifest, origin, null);
    }

    /** @param archive the sha256 of the archive the pack was unpacked from, or null for a directory */
    public BedrockPack(Path root, PackManifest manifest, String origin, String archive) {
        this.root = root;
        this.manifest = manifest;
        this.origin = origin;
        this.archive = archive;
        this.overlay = overlayOf(root, manifest, origin);
    }

    /**
     * The directory of the subpack this pack is loaded as, or null when it is loaded as itself.
     *
     * A pack ships each of its forms under `subpacks/<folder_name>/`, and the files there stand in
     * for the pack's own of the same path. A folder the manifest names and the pack does not carry
     * leaves the pack loaded as itself.
     */
    private static Path overlayOf(Path root, PackManifest manifest, String origin) {
        PackManifest.Subpack chosen = manifest == null ? null : manifest.subpack();
        if (chosen == null || chosen.folderName() == null) return null;

        Path folder = root.resolve("subpacks").resolve(chosen.folderName());
        if (Files.isDirectory(folder)) return folder;

        McAddon.LOGGER.warn("{} names the subpack {} and ships no subpacks/{} to load it from.",
            origin, chosen.name(), chosen.folderName());
        return null;
    }

    /** The name of the form this pack is loaded as, or null when it is loaded as itself. */
    public String subpackName() {
        return overlay == null ? null : manifest.subpack().name();
    }

    public PackManifest manifest() {
        return manifest;
    }

    /** The `.mcaddon` or directory the user dropped in, for reporting which file a problem is in. */
    public String origin() {
        return origin;
    }

    /**
     * The sha256 of the archive the pack was unpacked from, which names one copy of an add-on
     * wherever it is: on a server, on a client and in either one's cache. Null for a pack read from
     * a directory, which has no bytes of its own to name it by.
     */
    public String archive() {
        return archive;
    }

    public PackRole role() {
        if (manifest.has(PackManifest.ModuleType.RESOURCES)) return PackRole.RESOURCE;
        if (manifest.has(PackManifest.ModuleType.DATA) || manifest.has(PackManifest.ModuleType.SCRIPT)) {
            return PackRole.BEHAVIOUR;
        }
        return PackRole.OTHER;
    }

    public Path file(String path) {
        Path inSubpack = overlay == null ? null : overlay.resolve(path);
        return inSubpack != null && Files.exists(inSubpack) ? inSubpack : root.resolve(path);
    }

    /** Where a file this pack handed out sits inside it, whichever of the pack's forms it came from. */
    public Path relative(Path file) {
        return overlay != null && file.startsWith(overlay) ? overlay.relativize(file) : root.relativize(file);
    }

    public boolean has(String path) {
        return Files.isRegularFile(file(path));
    }

    public InputStream open(String path) throws IOException {
        return Files.newInputStream(file(path));
    }

    /**
     * Every file under `directory` with the given extension, deepest paths included.
     *
     * Bedrock nests freely: `textures/oreville/vn/...` and `functions/oreville/vn/...` are both real
     * layouts in one pack, so a non-recursive listing silently sees nothing.
     *
     * A file the loaded subpack carries stands in for the pack's own of the same path, so one path
     * is listed once whichever of the two forms it came out of.
     */
    public List<Path> list(String directory, String extension) {
        Map<String, Path> found = new TreeMap<>();
        gather(root, directory, extension, found);
        if (overlay != null) gather(overlay, directory, extension, found);
        return List.copyOf(found.values());
    }

    private void gather(Path base, String directory, String extension, Map<String, Path> found) {
        Path start = base.resolve(directory);
        if (!Files.isDirectory(start)) return;

        try (Stream<Path> walk = Files.walk(start)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                .forEach(path -> found.put(base.relativize(path).toString(), path));
        } catch (IOException error) {
            McAddon.LOGGER.warn("Could not list {} in {}.", directory, origin, error);
        }
    }

    /**
     * Something that changes when anything in the pack changes.
     *
     * A pack's own version is what an author bumps for players, not for every save, so keying the
     * converted output on it alone leaves an edited pack showing its last conversion. An archive's
     * digest says it outright. For a directory the path, the size and the modification time of every
     * file answer it without reading any of them.
     */
    public String contentStamp() {
        if (archive != null) return archive;
        try (Stream<Path> walk = Files.walk(root)) {
            return Long.toHexString(walk.filter(Files::isRegularFile)
                .sorted()
                .mapToLong(BedrockPack::stampOf)
                .reduce(1L, (stamp, file) -> stamp * 31L + file));
        } catch (IOException error) {
            McAddon.LOGGER.warn("Could not read {} to see whether it changed.", origin, error);
            return "";
        }
    }

    private static long stampOf(Path file) {
        try {
            return file.toString().hashCode() * 31L
                + Files.size(file) * 31L
                + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException unreadable) {
            return file.toString().hashCode();
        }
    }

    /** The manifest name, with `pack.name` and the like resolved against the pack's own texts. */
    public String displayName() {
        String resolved = text(manifest.name());
        return resolved == null || resolved.isBlank() ? root.getFileName().toString() : resolved;
    }

    public String text(String key) {
        if (texts == null) texts = readTexts();
        return texts.getOrDefault(key, key.startsWith("pack.") ? null : key);
    }

    /**
     * The pack's own translations, English first.
     *
     * Which locale is irrelevant for a log line, and the alternative is resolving the player's
     * locale before the game has one.
     */
    private Map<String, String> readTexts() {
        List<Path> candidates = new ArrayList<>(list("texts", ".lang"));
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString().startsWith("en_") ? 0 : 1));

        for (Path candidate : candidates) {
            try {
                return LangFile.read(candidate);
            } catch (IOException error) {
                McAddon.LOGGER.warn("Could not read {}.", candidate, error);
            }
        }
        return Map.of();
    }
}
