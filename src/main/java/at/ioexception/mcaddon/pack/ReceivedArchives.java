package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.McAddon;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The add-on archives a server has sent this client, each named by its digest.
 *
 * They live apart from `addons/`, so what the player installed stays what the player installed. An
 * archive is kept for the next visit to a server that has it, and dropped once nothing has used it
 * for a month or the archives together pass a size.
 */
public record ReceivedArchives(Path directory) {
    private static final String ARCHIVE = ".mcaddon";
    private static final String PARTIAL = ".part";
    private static final String USED = ".used";
    private static final Duration KEPT_FOR = Duration.ofDays(30);
    private static final long KEPT_UP_TO = 2L << 30;

    public static ReceivedArchives inCache() {
        return new ReceivedArchives(McAddonPaths.cache().resolve("received"));
    }

    /** The digests of every archive held whole. */
    public Set<String> held() {
        if (!Files.isDirectory(directory)) return Set.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith(ARCHIVE))
                .map(name -> name.substring(0, name.length() - ARCHIVE.length()))
                .collect(Collectors.toUnmodifiableSet());
        } catch (IOException unreadable) {
            return Set.of();
        }
    }

    public boolean has(String sha256) {
        return Files.isRegularFile(archive(sha256));
    }

    /** How many bytes of an archive an interrupted transfer left, which is where the next one starts. */
    public long partial(String sha256) {
        try {
            Path part = directory.resolve(sha256 + PARTIAL);
            return Files.isRegularFile(part) ? Files.size(part) : 0;
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /** The packs inside a held archive, marking it used so the pruning keeps it. */
    public List<BedrockPack> read(String sha256, String origin, List<String> problems) throws IOException {
        Path used = directory.resolve(sha256 + USED);
        if (!Files.exists(used)) Files.createFile(used);
        Files.setLastModifiedTime(used, FileTime.from(Instant.now()));
        return PackDiscovery.readArchive(archive(sha256), sha256, origin, problems);
    }

    /** Starts or resumes receiving one archive of `size` bytes. */
    public Sink receive(String sha256, long size) throws IOException {
        Files.createDirectories(directory);
        return new Sink(sha256, size, directory.resolve(sha256 + PARTIAL), partial(sha256));
    }

    /**
     * Drops every archive unused for a month, then the least recently used ones while the rest
     * together pass two gigabytes.
     */
    public void prune() {
        Instant now = Instant.now();
        List<String> byUse = new ArrayList<>(held());
        byUse.sort(Comparator.comparing(this::lastUsed));
        long total = byUse.stream().mapToLong(this::size).sum();
        for (String sha256 : byUse) {
            if (lastUsed(sha256).plus(KEPT_FOR).isAfter(now) && total <= KEPT_UP_TO) continue;
            total -= size(sha256);
            try {
                Files.deleteIfExists(archive(sha256));
                Files.deleteIfExists(directory.resolve(sha256 + USED));
                McAddon.LOGGER.info("Dropped the received add-on {}, which no server asked for lately.",
                    sha256);
            } catch (IOException failure) {
                McAddon.LOGGER.warn("Could not drop the received add-on {}: {}", sha256, failure.toString());
            }
        }
    }

    private Path archive(String sha256) {
        return directory.resolve(sha256 + ARCHIVE);
    }

    private Instant lastUsed(String sha256) {
        Path used = directory.resolve(sha256 + USED);
        try {
            return Files.getLastModifiedTime(Files.exists(used) ? used : archive(sha256)).toInstant();
        } catch (IOException unreadable) {
            return Instant.EPOCH;
        }
    }

    private long size(String sha256) {
        try {
            return Files.size(archive(sha256));
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /**
     * One archive arriving in pieces, appended in order to a partial file that becomes the archive
     * once every byte is there and the bytes have the digest they were promised under.
     */
    public static final class Sink {
        private final String sha256;
        private final long size;
        private final Path part;
        private long held;

        private Sink(String sha256, long size, Path part, long held) {
            this.sha256 = sha256;
            this.size = size;
            this.part = part;
            this.held = held;
        }

        /** Where the next piece has to start. */
        public long held() {
            return held;
        }

        /**
         * Appends one piece and answers whether the archive is now whole.
         *
         * A piece starting anywhere but where the last ended, or running past the promised size, is
         * refused; so are whole bytes whose digest is not the promised one, which are thrown away.
         */
        public boolean accept(long offset, byte[] data) throws IOException {
            if (offset != held) {
                throw new IOException("a piece of " + sha256 + " arrived at " + offset + ", not " + held);
            }
            if (held + data.length > size) {
                throw new IOException("a piece of " + sha256 + " runs past its " + size + " bytes");
            }

            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
                out.write(data);
            }
            held += data.length;
            if (held < size) return false;

            String received = Archives.sha256(part);
            if (!received.equals(sha256)) {
                Files.deleteIfExists(part);
                throw new IOException("the archive sent as " + sha256 + " arrived as " + received);
            }
            Files.move(part, part.resolveSibling(sha256 + ARCHIVE), StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
    }
}
