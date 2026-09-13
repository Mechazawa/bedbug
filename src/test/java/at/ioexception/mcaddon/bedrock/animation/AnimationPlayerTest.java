package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock one clip runs on, which is where the two delays and the loop live.
 *
 * A start delay is evaluated as the clip begins and a loop delay after each loop, so neither can be
 * folded into the elapsed time. The player keeps the position it reads the pose at for that reason.
 */
class AnimationPlayerTest {
    @TempDir
    Path directory;

    @Test
    void wrapsALoopingClipRoundRatherThanHoldingItsEnd() throws IOException {
        AnimationPlayer player = playing("""
            {"animations": {"animation.spin": {"loop": true, "animation_length": 1.0,
              "bones": {"head": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 90, 0]}}}}}}
            """);

        for (int tick = 0; tick < 15; tick++) player.tick(0.1f);
        assertEquals(45f, rotation(player).y, 0.001f);
    }

    /** A clip waits out its loop delay on its last frame before it starts the next loop. */
    @Test
    void holdsTheEndOfALoopingClipForItsLoopDelay() throws IOException {
        AnimationPlayer player = playing("""
            {"animations": {"animation.spin": {"loop": true, "animation_length": 1.0,
              "loop_delay": 0.5,
              "bones": {"head": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 90, 0]}}}}}}
            """);

        for (int tick = 0; tick < 12; tick++) player.tick(0.1f);
        assertEquals(90f, rotation(player).y, 0.001f);

        for (int tick = 0; tick < 6; tick++) player.tick(0.1f);
        assertTrue(rotation(player).y < 45f, "the clip should be back near its start");
    }

    @Test
    void holdsAClipAtItsFirstFrameUntilItsStartDelayIsOver() throws IOException {
        AnimationPlayer player = playing("""
            {"animations": {"animation.spin": {"loop": true, "animation_length": 1.0,
              "start_delay": 1.0,
              "bones": {"head": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 90, 0]}}}}}}
            """);

        for (int tick = 0; tick < 9; tick++) player.tick(0.1f);
        assertEquals(0f, rotation(player).y, 0.001f);

        for (int tick = 0; tick < 4; tick++) player.tick(0.1f);
        assertEquals(27f, rotation(player).y, 0.001f);
    }

    /**
     * A clip driving its own clock is left to run it, and its position wraps without its time.
     *
     * `query.anim_time` has to read as the raw time the expression last produced, because an
     * expression counting upwards would otherwise be pulled back to the start of every loop.
     */
    @Test
    void letsAClipWithItsOwnClockKeepCountingPastItsLength() throws IOException {
        AnimationPlayer player = playing("""
            {"animations": {"animation.spin": {"loop": true, "animation_length": 1.0,
              "anim_time_update": "query.anim_time + query.delta_time",
              "bones": {"head": {"rotation": {"0.0": [0, 0, 0], "1.0": [0, 90, 0]}}}}}}
            """);

        MolangEnvironment environment = player.environment();
        environment.setQueries((name, arguments) -> switch (name) {
            case "anim_time" -> MolangValue.of(environment.animTime());
            case "delta_time" -> MolangValue.of(environment.deltaTime());
            default -> MolangValue.ZERO;
        });

        for (int tick = 0; tick < 15; tick++) player.tick(0.1f);
        assertEquals(45f, rotation(player).y, 0.001f);
    }

    private static Vector3f rotation(AnimationPlayer player) {
        return player.poseOf("head").rotation;
    }

    private AnimationPlayer playing(String animations) throws IOException {
        Path pack = directory.resolve("pack");
        Files.createDirectories(pack.resolve("animations"));
        Files.writeString(pack.resolve("animations").resolve("test.animation.json"), animations);

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {"header": {"uuid": "00000000-0000-0000-0000-000000000000", "name": "test"}}
            """).getAsJsonObject());
        AnimationPlayer player = new AnimationPlayer(
            BedrockAssets.read(new BedrockPack(pack, manifest, "test.mcaddon")));
        player.play("animation.spin", null, 0f);
        return player;
    }
}
