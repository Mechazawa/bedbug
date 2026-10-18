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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Path archive = archive("pack.mcaddon", entry);
        Path extracted = Archives.extract(archive, Archives.sha256(archive), cache);

        assertTrue(Files.isRegularFile(extracted.resolve("manifest.json")));
        assertTrue(noEvidenceOf("evil.txt"), "evil.txt was written under " + directory);
    }

    @Test
    void unpacksTheSameBytesOnlyOnceUnderAnyName() throws IOException {
        Path archive = archive("pack.mcaddon");
        Path cache = directory.resolve("cache");

        Path first = Archives.extract(archive, Archives.sha256(archive), cache);
        Files.writeString(first.resolve("touched.txt"), "still here");
        Path renamed = Files.copy(archive, directory.resolve("renamed.mcaddon"));
        Path second = Archives.extract(renamed, Archives.sha256(renamed), cache);

        assertEquals(first, second);
        assertTrue(Files.isRegularFile(second.resolve("touched.txt")));
    }

    @Test
    void refusesAnArchiveUnpackingIntoTooManyFiles() throws IOException {
        Path archive = directory.resolve("many.mcaddon");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (int index = 0; index <= Archives.MAX_ENTRIES; index++) write(zip, "file" + index, "");
        }
        Path cache = directory.resolve("cache");

        IOException refused = assertThrows(IOException.class,
            () -> Archives.extract(archive, Archives.sha256(archive), cache));
        assertTrue(refused.getMessage().contains("files"), refused.getMessage());
        assertFalse(Files.exists(cache.resolve("extracted").resolve(Archives.sha256(archive))));
    }

    @Test
    void refusesAnArchiveUnpackingIntoTooManyBytes() throws IOException {
        Path archive = directory.resolve("bomb.mcaddon");
        byte[] zeros = new byte[1 << 20];
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("zeros"));
            for (long written = 0; written <= Archives.MAX_UNPACKED; written += zeros.length) zip.write(zeros);
            zip.closeEntry();
        }
        Path cache = directory.resolve("cache");

        IOException refused = assertThrows(IOException.class,
            () -> Archives.extract(archive, Archives.sha256(archive), cache));
        assertTrue(refused.getMessage().contains("bytes"), refused.getMessage());
    }

    /** A pack directory a server serves is named by the digest of its zip, which must not wander. */
    @Test
    void zipsADirectoryToTheSameBytesEveryTime() throws IOException {
        Path pack = directory.resolve("pack");
        Files.createDirectories(pack.resolve("textures"));
        Files.writeString(pack.resolve("manifest.json"), "{}");
        Files.writeString(pack.resolve("textures/a.png"), "a");
        Path first = directory.resolve("first.zip");
        Archives.zip(pack, first);
        Files.setLastModifiedTime(pack.resolve("manifest.json"), java.nio.file.attribute.FileTime.fromMillis(0));
        Path second = directory.resolve("second.zip");
        Archives.zip(pack, second);

        assertEquals(Archives.sha256(first), Archives.sha256(second));
        Files.writeString(pack.resolve("textures/a.png"), "b");
        Path third = directory.resolve("third.zip");
        Archives.zip(pack, third);
        assertFalse(Archives.sha256(first).equals(Archives.sha256(third)));
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
