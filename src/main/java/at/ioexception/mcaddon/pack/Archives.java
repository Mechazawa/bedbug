package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.McAddon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Unpacks a `.mcaddon` into the cache, once per version of the file. */
final class Archives {
    private Archives() {
    }

    static boolean isArchive(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mcaddon") || name.endsWith(".mcpack") || name.endsWith(".zip");
    }

    /**
     * Extracts into a directory named after the archive and its size and timestamp, so replacing the
     * file extracts again and restarting with the same file does not.
     */
    static Path extract(Path archive, Path cache) throws IOException {
        String stamp = Long.toHexString(Files.size(archive) * 31 + Files.getLastModifiedTime(archive).toMillis());
        Path target = cache.resolve("extracted").resolve(baseName(archive) + "-" + stamp);
        Path marker = target.resolve(".extracted");
        if (Files.isRegularFile(marker)) return target;

        if (Files.exists(target)) delete(target);
        Files.createDirectories(target);

        int files = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path destination = resolveInside(target, entry.getName());
                if (destination == null) {
                    McAddon.LOGGER.warn("{} contains an entry that escapes the archive: {}",
                        archive.getFileName(), entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                files++;
            }
        }

        Files.writeString(marker, archive.getFileName().toString());
        McAddon.LOGGER.info("Unpacked {} ({} files).", archive.getFileName(), files);
        return target;
    }

    /** Null when the entry name would write outside the target, which is how a zip attacks a host. */
    private static Path resolveInside(Path target, String entryName) {
        Path destination = target.resolve(entryName).normalize();
        return destination.startsWith(target) ? destination : null;
    }

    private static String baseName(Path archive) {
        String name = archive.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void delete(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
