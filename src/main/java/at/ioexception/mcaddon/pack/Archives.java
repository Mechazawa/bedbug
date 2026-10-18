package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.McAddon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Unpacks a `.mcaddon` into the cache, once per distinct archive. */
public final class Archives {
    /** The most files one archive may unpack into. */
    static final int MAX_ENTRIES = 50_000;

    /** The most bytes one archive may unpack into, whatever its own size. */
    static final long MAX_UNPACKED = 1L << 30;

    private Archives() {
    }

    static boolean isArchive(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mcaddon") || name.endsWith(".mcpack") || name.endsWith(".zip");
    }

    /** The sha256 of a file's bytes, in lower case hex, which is what names an archive everywhere. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /**
     * Extracts into a directory named after the archive's digest, so the same bytes are unpacked
     * once however many names they arrive under, and other bytes never land on top of them.
     *
     * An archive may come from a server rather than from the player, so one unpacking into more
     * than `MAX_ENTRIES` files or `MAX_UNPACKED` bytes is refused, and an entry naming a place
     * outside the directory is skipped.
     */
    static Path extract(Path archive, String sha256, Path cache) throws IOException {
        Path target = cache.resolve("extracted").resolve(sha256);
        Path marker = target.resolve(".extracted");
        if (Files.isRegularFile(marker)) return target;

        if (Files.exists(target)) delete(target);
        Files.createDirectories(target);

        int files = 0;
        long unpacked = 0;
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
                if (++files > MAX_ENTRIES) throw refused(target, archive, "more than " + MAX_ENTRIES + " files");
                Files.createDirectories(destination.getParent());
                unpacked += copy(zip, destination, MAX_UNPACKED - unpacked);
                if (unpacked > MAX_UNPACKED) throw refused(target, archive, "more than " + MAX_UNPACKED + " bytes");
            }
        }

        Files.writeString(marker, archive.getFileName().toString());
        McAddon.LOGGER.info("Unpacked {} ({} files).", archive.getFileName(), files);
        return target;
    }

    /** Copies at most one byte past `room`, so a caller can tell the limit was passed. */
    private static long copy(InputStream from, Path to, long room) throws IOException {
        long written = 0;
        try (OutputStream out = Files.newOutputStream(to)) {
            byte[] buffer = new byte[1 << 16];
            for (int read = from.read(buffer); read >= 0 && written <= room; read = from.read(buffer)) {
                out.write(buffer, 0, read);
                written += read;
            }
        }
        return written;
    }

    private static IOException refused(Path target, Path archive, String why) throws IOException {
        delete(target);
        return new IOException(archive.getFileName() + " unpacks into " + why);
    }

    /**
     * Drops every unpacked archive except the ones named in `inUse`.
     *
     * An archive is unpacked under its digest, so editing a pack leaves the copy made before the
     * edit behind, and deleting a pack leaves its last copy behind. Neither is read again, and a pack
     * worked on for an afternoon would otherwise leave a directory per save.
     */
    static void forgetUnused(Path cache, Collection<String> inUse) {
        Path extracted = cache.resolve("extracted");
        if (!Files.isDirectory(extracted)) return;

        try (var unpacked = Files.list(extracted)) {
            for (Path copy : unpacked.toList()) {
                if (inUse.contains(copy.getFileName().toString())) continue;
                delete(copy);
                McAddon.LOGGER.info("Dropped the unpacked copy of {}, which nothing asks for.",
                    copy.getFileName());
            }
        } catch (IOException failure) {
            McAddon.LOGGER.warn("Could not tidy {}: {}", extracted, failure.toString());
        }
    }

    /**
     * Writes a directory as an archive whose bytes depend only on the names and contents of its
     * files, so the same directory zips to the same digest every time and on every machine.
     */
    public static void zip(Path directory, Path target) throws IOException {
        List<Path> files;
        try (var walk = Files.walk(directory)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(directory.relativize(file).toString());
                entry.setTimeLocal(FIXED_TIME);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    /** The one time every entry of a zipped directory carries. */
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    /** Null when the entry name would write outside the target, which is how a zip attacks a host. */
    private static Path resolveInside(Path target, String entryName) {
        Path destination = target.resolve(entryName).normalize();
        return destination.startsWith(target) ? destination : null;
    }

    static void delete(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
