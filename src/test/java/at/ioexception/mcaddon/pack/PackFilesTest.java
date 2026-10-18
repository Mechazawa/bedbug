package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A resource pack shipping a kind of file nothing converts is told which, and why. */
class PackFilesTest {

    private static final List<String> UNREAD = List.of("ui/server_form.json",
        "textures/texture_list.json", "water/water.json");

    @Test
    void anUnreadKindIsNamedWithItsReason(@TempDir Path root) throws IOException {
        for (String path : UNREAD) {
            Path file = root.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{}", StandardCharsets.UTF_8);
        }

        PackFiles.report(new BedrockPack(root, PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "00000000-0000-0000-0000-0000000000eb", "version": [1, 0, 0]},
              "modules": [{"type": "resources", "uuid": "00000000-0000-0000-0000-0000000000ea"}]
            }
            """).getAsJsonObject()), "test"));

        for (String kind : List.of("ui", "textures/texture_list.json", "water")) {
            assertTrue(AddonEntities.report().stream()
                .anyMatch(line -> line.trim().startsWith("resource pack " + kind + ", ")),
                kind + " was not named with a reason: " + AddonEntities.report());
        }
    }
}
