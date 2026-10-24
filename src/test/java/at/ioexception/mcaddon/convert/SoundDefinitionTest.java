package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundDefinitionTest {
    private static final String DEFINITIONS = """
        {
          "sound_definitions": {
            "tp:far": {
              "subtitle": "subtitles.tp.far",
              "min_distance": 4.0,
              "max_distance": 24.0,
              "sounds": ["sounds/tp/chime"]
            },
            "tp:plain": {
              "min_distance": null,
              "max_distance": null,
              "sounds": ["sounds/tp/chime"]
            }
          }
        }
        """;

    @Test
    void subtitleAndMaxDistanceReachJavasSoundsJson(@TempDir Path root) throws IOException {
        JsonObject events = convert(root);

        JsonObject far = events.getAsJsonObject("far");
        assertEquals("subtitles.tp.far", far.get("subtitle").getAsString());
        assertEquals(24, far.getAsJsonArray("sounds").get(0).getAsJsonObject()
            .get("attenuation_distance").getAsInt());

        JsonObject plain = events.getAsJsonObject("plain");
        assertFalse(plain.has("subtitle"));
        assertFalse(plain.getAsJsonArray("sounds").get(0).getAsJsonObject().has("attenuation_distance"));
    }

    @Test
    void aFullVolumeRadiusIsReportedBecauseJavaHasNone(@TempDir Path root) throws IOException {
        convert(root);

        assertTrue(AddonEntities.report().stream()
            .anyMatch(line -> line.trim().startsWith("sound min_distance, tp:far")));
        assertFalse(AddonEntities.report().stream()
            .anyMatch(line -> line.trim().startsWith("sound min_distance, tp:plain")));
    }

    private static JsonObject convert(Path root) throws IOException {
        Path sounds = root.resolve("sounds/tp");
        Files.createDirectories(sounds);
        Files.write(sounds.resolve("chime.ogg"), new byte[] {1});
        Files.writeString(root.resolve("sounds/sound_definitions.json"), DEFINITIONS, StandardCharsets.UTF_8);

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "00000000-0000-0000-0000-0000000000fb", "version": [1, 0, 0]},
              "modules": [{"type": "resources", "uuid": "00000000-0000-0000-0000-0000000000fa"}]
            }
            """).getAsJsonObject());
        Path output = root.resolve("out");
        new PackConverter.Sounds("tp").convert(new BedrockPack(root, manifest, "test"), output, true);
        return Json.parseObject(output.resolve("assets/tp/sounds.json"));
    }
}
