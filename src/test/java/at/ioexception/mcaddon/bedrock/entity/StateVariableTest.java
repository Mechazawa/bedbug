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

/** A controller state's `variables`, set from their input through a remap curve on every frame. */
class StateVariableTest {
    @TempDir
    Path directory;

    @Test
    void remapsAStateVariablesInputThroughItsCurve() throws IOException {
        assertEquals(0.45f, curveAt("0.5"), 0.0001f);
    }

    @Test
    void holdsAStateVariableAtTheEndsOfItsCurve() throws IOException {
        assertEquals(0.2f, curveAt("-3"), 0.0001f);
        assertEquals(0.7f, curveAt("4"), 0.0001f);
    }

    private float curveAt(String input) throws IOException {
        Path pack = directory.resolve("pack" + input);
        Files.createDirectories(pack.resolve("animation_controllers"));
        Files.writeString(pack.resolve("animation_controllers").resolve("test.json"), """
            {"animation_controllers": {"controller.animation.test.move": {"states": {"default": {
              "variables": {"variable.ground_speed_curve": {"input": "%s",
                "remap_curve": {"1.0": 0.7, "0.0": 0.2}}}
            }}}}}
            """.formatted(input));

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {"header": {"uuid": "00000000-0000-0000-0000-000000000000", "name": "test"}}
            """).getAsJsonObject());
        ClientEntityDefinition definition = ClientEntityParser.read("test:thing", Json.parse("""
            {"animations": {"move": "controller.animation.test.move"}, "scripts": {"animate": ["move"]}}
            """).getAsJsonObject());
        EntityAnimator animator = new EntityAnimator(
            BedrockAssets.read(new BedrockPack(pack, manifest, "test.mcaddon")), null, definition);
        animator.tick(0.05f, 0.05f);
        return animator.environment().variable("ground_speed_curve").asFloat();
    }
}
