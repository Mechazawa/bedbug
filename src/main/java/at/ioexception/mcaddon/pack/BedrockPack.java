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
    private Map<String, String> texts;

    public BedrockPack(Path root, PackManifest manifest, String origin) {
        this.root = root;
        this.manifest = manifest;
        this.origin = origin;
    }

    public Path root() {
        return root;
    }

    public PackManifest manifest() {
        return manifest;
    }

    /** The `.mcaddon` or directory the user dropped in, for reporting which file a problem is in. */
    public String origin() {
        return origin;
    }

    public PackRole role() {
        if (manifest.has(PackManifest.ModuleType.RESOURCES)) return PackRole.RESOURCE;
        if (manifest.has(PackManifest.ModuleType.DATA) || manifest.has(PackManifest.ModuleType.SCRIPT)) {
            return PackRole.BEHAVIOUR;
        }
        return PackRole.OTHER;
    }

    public Path file(String path) {
        return root.resolve(path);
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
     */
    public List<Path> list(String directory, String extension) {
        Path start = file(directory);
        if (!Files.isDirectory(start)) return List.of();

        try (Stream<Path> walk = Files.walk(start)) {
            return walk.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                .sorted()
                .toList();
        } catch (IOException error) {
            McAddon.LOGGER.warn("Could not list {} in {}.", directory, origin, error);
            return List.of();
        }
    }

    /**
     * A number that changes when anything in the pack changes.
     *
     * A pack's own version is what an author bumps for players, not for every save, so keying the
     * converted output on it alone leaves an edited pack showing its last conversion. The path, the
     * size and the modification time of every file answer that without reading any of them.
     */
    public long contentStamp() {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .sorted()
                .mapToLong(BedrockPack::stampOf)
                .reduce(1L, (stamp, file) -> stamp * 31L + file);
        } catch (IOException error) {
            McAddon.LOGGER.warn("Could not read {} to see whether it changed.", origin, error);
            return 0L;
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
