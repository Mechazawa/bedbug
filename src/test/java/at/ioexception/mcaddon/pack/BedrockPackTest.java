package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a pack shipping several forms of itself hands its readers.
 *
 * A file in the loaded subpack stands in for the pack's own of the same path, and a file only the
 * subpack has joins the pack. Every reader in this mod goes through `file`, `has`, `open` and
 * `list`, so a subpack the pack resolution missed would show up as a texture, a model or a
 * definition the game drew from the wrong form with nothing said about it.
 */
class BedrockPackTest {
    private static final String UUID = "00000000-0000-0000-0000-000000000001";

    @Test
    void takesEachFileFromTheLoadedSubpackAndTheRestFromThePack(@TempDir Path root) throws IOException {
        write(root, "textures/entity/sprite.png", "the pack's own");
        write(root, "textures/entity/ember.png", "untouched");
        write(root, "subpacks/plain/textures/entity/sprite.png", "the low form");
        write(root, "subpacks/bright/textures/entity/sprite.png", "the high form");
        write(root, "subpacks/bright/textures/entity/cap.png", "only the high form has this");

        BedrockPack pack = pack(root, """
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "subpacks": [
                {"folder_name": "bright", "name": "Bright", "memory_tier": 64},
                {"folder_name": "plain", "name": "Plain", "memory_tier": 6}
              ]
            }
            """.formatted(UUID));

        assertEquals("Bright", pack.subpackName());
        assertEquals("the high form", Files.readString(pack.file("textures/entity/sprite.png")));
        assertEquals("untouched", Files.readString(pack.file("textures/entity/ember.png")));
        assertEquals(List.of("textures/entity/cap.png", "textures/entity/ember.png",
                "textures/entity/sprite.png"),
            pack.list("textures", ".png").stream().map(file -> pack.relative(file).toString()).toList());
    }

    @Test
    void ignoresEverySubpackWhenTheChosenFolderIsNotThere(@TempDir Path root) throws IOException {
        write(root, "textures/entity/sprite.png", "the pack's own");
        write(root, "subpacks/plain/textures/entity/sprite.png", "the low form");

        BedrockPack pack = pack(root, """
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "subpacks": [{"folder_name": "bright", "name": "Bright", "memory_tier": 64}]
            }
            """.formatted(UUID));

        assertNull(pack.subpackName());
        assertEquals("the pack's own", Files.readString(pack.file("textures/entity/sprite.png")));
        assertEquals(List.of("textures/entity/sprite.png"),
            pack.list("textures", ".png").stream().map(file -> pack.relative(file).toString()).toList());
    }

    private static BedrockPack pack(Path root, String manifest) throws IOException {
        return new BedrockPack(root, PackManifest.tryFrom(Json.parse(
            new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8))).getAsJsonObject()),
            "test");
    }

    private static void write(Path root, String path, String body) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
