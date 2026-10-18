package at.ioexception.mcaddon.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An archive arriving from a server in pieces, which a client writes only in order, only up to the
 * size it was promised, and keeps only when the whole has the digest it was promised under.
 */
class ReceivedArchivesTest {
    @TempDir
    Path directory;

    private final byte[] bytes = new byte[2_500_000];

    {
        Arrays.fill(bytes, (byte) 7);
        bytes[0] = 1;
    }

    @Test
    void keepsAnArchiveWhoseWholeHasThePromisedDigest() throws IOException {
        ReceivedArchives received = new ReceivedArchives(directory);
        ReceivedArchives.Sink sink = received.receive(digest(), bytes.length);

        assertFalse(sink.accept(0, Arrays.copyOfRange(bytes, 0, 1_000_000)));
        assertFalse(sink.accept(1_000_000, Arrays.copyOfRange(bytes, 1_000_000, 2_000_000)));
        assertTrue(sink.accept(2_000_000, Arrays.copyOfRange(bytes, 2_000_000, bytes.length)));
        assertEquals(Set.of(digest()), received.held());
    }

    @Test
    void refusesAPieceOutOfOrder() throws IOException {
        ReceivedArchives.Sink sink = new ReceivedArchives(directory).receive(digest(), bytes.length);
        assertThrows(IOException.class, () -> sink.accept(1_000_000, new byte[10]));
    }

    @Test
    void refusesAPiecePastThePromisedSize() throws IOException {
        ReceivedArchives.Sink sink = new ReceivedArchives(directory).receive(digest(), 10);
        assertThrows(IOException.class, () -> sink.accept(0, new byte[11]));
    }

    @Test
    void throwsAwayBytesWithAnotherDigest() throws IOException {
        ReceivedArchives received = new ReceivedArchives(directory);
        ReceivedArchives.Sink sink = received.receive("0".repeat(64), bytes.length);

        assertThrows(IOException.class, () -> sink.accept(0, bytes));
        assertTrue(received.held().isEmpty());
        assertEquals(0, received.partial("0".repeat(64)));
    }

    @Test
    void resumesFromWhereAnInterruptedTransferStopped() throws IOException {
        ReceivedArchives received = new ReceivedArchives(directory);
        received.receive(digest(), bytes.length).accept(0, Arrays.copyOfRange(bytes, 0, 1_000_000));

        ReceivedArchives.Sink resumed = received.receive(digest(), bytes.length);
        assertEquals(1_000_000, resumed.held());
        assertTrue(resumed.accept(1_000_000, Arrays.copyOfRange(bytes, 1_000_000, bytes.length)));
        assertTrue(received.has(digest()));
    }

    /** An archive nothing has used for a month goes; one used yesterday stays. */
    @Test
    void dropsAnArchiveUnusedForAMonth() throws IOException {
        ReceivedArchives received = new ReceivedArchives(directory);
        Files.writeString(directory.resolve("old.mcaddon"), "old");
        Files.writeString(directory.resolve("new.mcaddon"), "new");
        Files.createFile(directory.resolve("old.used"));
        Files.setLastModifiedTime(directory.resolve("old.used"),
            FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        Files.createFile(directory.resolve("new.used"));
        Files.setLastModifiedTime(directory.resolve("new.used"),
            FileTime.from(Instant.now().minus(Duration.ofDays(1))));

        received.prune();
        assertEquals(Set.of("new"), received.held());
    }

    private String digest() throws IOException {
        Path whole = directory.resolveSibling(directory.getFileName() + "-whole");
        Files.write(whole, bytes);
        return Archives.sha256(whole);
    }
}
