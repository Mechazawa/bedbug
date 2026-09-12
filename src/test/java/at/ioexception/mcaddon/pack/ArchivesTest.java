package at.ioexception.mcaddon.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is written to disk when a `.mcaddon` is unpacked, and what is refused.
 *
 * An entry name is a string chosen by whoever built the archive, so one holding `..` would write
 * outside the directory being extracted into. Such an entry is skipped and the rest of the archive
 * still lands: a pack with one hostile entry is more likely to be a badly built pack than an attack,
 * and either way the other files are worth having.
 */
class ArchivesTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"../../evil.txt", "../evil.txt", "sub/../../../evil.txt"})
    void refusesAnEntryThatWouldWriteOutsideTheTarget(String entry) throws IOException {
        Path cache = directory.resolve("cache");
        Path extracted = Archives.extract(archive("pack.mcaddon", entry), cache);

        assertTrue(Files.isRegularFile(extracted.resolve("manifest.json")));
        assertTrue(noEvidenceOf("evil.txt"), "evil.txt was written under " + directory);
    }

    @Test
    void unpacksTheSameArchiveOnlyOnce() throws IOException {
        Path archive = archive("pack.mcaddon");
        Path cache = directory.resolve("cache");

        Path first = Archives.extract(archive, cache);
        Files.writeString(first.resolve("touched.txt"), "still here");
        Path second = Archives.extract(archive, cache);

        assertEquals(first, second);
        assertTrue(Files.isRegularFile(second.resolve("touched.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pack.mcaddon", "pack.mcpack", "pack.zip", "PACK.MCADDON"})
    void recognisesTheExtensionsAPackArrivesIn(String name) {
        assertTrue(Archives.isArchive(Path.of(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pack", "pack.json", "pack.mcworld"})
    void leavesAFileThatIsNotAnArchiveAlone(String name) {
        assertFalse(Archives.isArchive(Path.of(name)));
    }

    private boolean noEvidenceOf(String name) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.noneMatch(path -> path.getFileName().toString().equals(name));
        }
    }

    private Path archive(String name, String... extraEntries) throws IOException {
        Path archive = directory.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            write(zip, "manifest.json", "{}");
            for (String entry : extraEntries) write(zip, entry, "owned");
        }
        return archive;
    }

    private static void write(ZipOutputStream zip, String name, String body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
