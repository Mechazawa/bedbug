package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A behaviour pack on disk holding one file, which is what the readers under test take. */
final class BehaviourPackFixture {
    private BehaviourPackFixture() {
    }

    static BedrockPack holding(Path root, String path, String body) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "00000000-0000-0000-0000-0000000000ff", "version": [1, 0, 0]},
              "modules": [{"type": "data", "uuid": "00000000-0000-0000-0000-0000000000fe"}]
            }
            """).getAsJsonObject());
        return new BedrockPack(root, manifest, "test");
    }
}
