package at.ioexception.mcaddon.bedrock.entity;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which definition an entity is drawn from when a pack carries several of one identifier.
 *
 * A pack ships an older form of an entity beside the current one so that an older engine still has
 * something to read. `min_engine_version` on each says which engine it was written for, and the
 * engine the pack itself names decides which of them wins.
 */
class ClientEntitySelectionTest {
    @TempDir
    Path directory;

    @Test
    void keepsTheNewestDefinitionThePacksOwnEngineReaches() throws IOException {
        BedrockAssets assets = pack("1.21.0",
            entity("1.8.0", "old"),
            entity("1.16.0", "current"),
            entity("1.10.0", "between"));

        assertEquals("current", assets.entity("thing:ember").geometry().get("default"));
    }

    @Test
    void leavesOutADefinitionWrittenForANewerEngineThanThePackNames() throws IOException {
        BedrockAssets assets = pack("1.16.0",
            entity("1.8.0", "readable"),
            entity("1.21.0", "too_new"));

        assertEquals("readable", assets.entity("thing:ember").geometry().get("default"));
    }

    /** An entity drawn from nothing at all is worse than one drawn from a definition written later. */
    @Test
    void fallsBackToADefinitionEveryOneOfWhichIsTooNew() throws IOException {
        BedrockAssets assets = pack("1.8.0", entity("1.21.0", "only"));

        assertEquals("only", assets.entity("thing:ember").geometry().get("default"));
    }

    private static String entity(String engine, String geometry) {
        return """
            {
              "format_version": "1.10.0",
              "minecraft:client_entity": {
                "description": {
                  "identifier": "thing:ember",
                  "min_engine_version": "%s",
                  "geometry": {"default": "%s"}
                }
              }
            }
            """.formatted(engine, geometry);
    }

    private BedrockAssets pack(String engine, String... files) throws IOException {
        Path root = directory.resolve("pack");
        Files.createDirectories(root.resolve("entity"));
        for (int index = 0; index < files.length; index++) {
            Files.writeString(root.resolve("entity").resolve(index + ".entity.json"), files[index]);
        }

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {"header": {"uuid": "00000000-0000-0000-0000-000000000000", "name": "test",
                        "min_engine_version": "%s"}}
            """.formatted(engine)).getAsJsonObject());
        return BedrockAssets.read(new BedrockPack(root, manifest, "test.mcaddon"));
    }
}
