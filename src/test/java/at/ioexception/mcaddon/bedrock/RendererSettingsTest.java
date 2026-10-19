package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings a resource pack writes for the deferred renderer that Java takes none of.
 *
 * Naming the directory would tell an author that their water settings did nothing. Naming each
 * setting tells them which of the ones they wrote did nothing, which is the question they have.
 */
class RendererSettingsTest {

    private static final String WATER = """
        {
          "format_version": "1.21.120",
          "minecraft:water_settings": {
            "description": {"identifier": "minecraft:default_water"},
            "particle_concentrations": {"chlorophyll": 1.4, "cdom": 6.0},
            "caustics": {"enabled": true, "scale": 1.5},
            "waves": {"enabled": true, "frequency": 0.8},
            "biome_water_color_contribution": 0.6
          }
        }
        """;

    @Test
    void everyWaterSettingIsNamed(@TempDir Path root) throws IOException {
        RendererSettings.WATER.report(packHolding(root, Map.of("water/water.json", WATER)));

        assertNamed("water particle_concentrations");
        assertNamed("water caustics");
        assertNamed("water waves");
        assertNamed("water biome_water_color_contribution");
    }

    private static void assertNamed(String opening) {
        assertTrue(AddonEntities.report().stream()
            .anyMatch(line -> line.trim().startsWith(opening)),
            opening + " was dropped without being named.");
    }

    private static BedrockPack packHolding(Path root, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "00000000-0000-0000-0000-0000000000fb", "version": [1, 0, 0]},
              "modules": [{"type": "resources", "uuid": "00000000-0000-0000-0000-0000000000fa"}]
            }
            """).getAsJsonObject());
        return new BedrockPack(root, manifest, "test");
    }
}
