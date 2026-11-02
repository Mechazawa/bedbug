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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** The step that wraps a loop still fires what lies between its last position and the end. */
    @Test
    void firesATimelineEntryNearTheEndOfALoopOnEveryLoop() throws IOException {
        AnimationPlayer player = playing("""
            {"animations": {"animation.spin": {"loop": true, "animation_length": 1.0,
              "timeline": {"0.97": "v.hits = v.hits + 1;"}}}}
            """);

        for (int tick = 0; tick < 25; tick++) player.tick(0.1f);
        assertEquals(2f, player.environment().variable("hits").asFloat(), 0.001f);
    }

    /** A clip overriding the ones before it puts its bones back to rest before adding its own pose. */
    @Test
    void anOverridingClipReplacesThePoseOfTheClipsBeforeIt() throws IOException {
        AnimationPlayer player = overriding();
        player.ensurePlaying("animation.turn", 1f, false);
        player.ensurePlaying("animation.nod", 1f, false);
        player.tick(0.05f);
        assertEquals(new Vector3f(10, 0, 0), rotation(player));
    }

    @Test
    void anOverridingClipLeavesTheClipsAfterItAlone() throws IOException {
        AnimationPlayer player = overriding();
        player.ensurePlaying("animation.nod", 1f, false);
        player.ensurePlaying("animation.turn", 1f, false);
        player.tick(0.05f);
        assertEquals(new Vector3f(10, 90, 0), rotation(player));
    }

    private AnimationPlayer overriding() throws IOException {
        return player("""
            {"animations": {
              "animation.turn": {"loop": true, "bones": {"head": {"rotation": [0, 90, 0]}}},
              "animation.nod": {"loop": true, "override_previous_animation": true,
                "bones": {"head": {"rotation": [10, 0, 0]}}}
            }}
            """);
    }

    /** A clip that does not read costs only itself, not the clips after it in the same file. */
    @Test
    void keepsTheClipsAfterOneThatDoesNotRead() throws IOException {
        AnimationPlayer player = player("""
            {"animations": {
              "animation.squid": {"loop": true, "bones": {"body": {"rotation": [{"y": "180 - this"}, 0, 0]}}},
              "animation.spin": {"loop": true, "bones": {"head": {"rotation": [0, 90, 0]}}}
            }}
            """);
        player.play("animation.spin", null, 0f);
        player.play("animation.squid", null, 0f);
        player.tick(0.05f);

        assertEquals(new Vector3f(0, 90, 0), rotation(player));
        assertNull(player.poseOf("body"));
    }

    private static Vector3f rotation(AnimationPlayer player) {
        return player.poseOf("head").rotation;
    }

    private AnimationPlayer playing(String animations) throws IOException {
        AnimationPlayer player = player(animations);
        player.play("animation.spin", null, 0f);
        return player;
    }

    private AnimationPlayer player(String animations) throws IOException {
        Path pack = directory.resolve("pack");
        Files.createDirectories(pack.resolve("animations"));
        Files.writeString(pack.resolve("animations").resolve("test.animation.json"), animations);

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {"header": {"uuid": "00000000-0000-0000-0000-000000000000", "name": "test"}}
            """).getAsJsonObject());
        return new AnimationPlayer(BedrockAssets.read(new BedrockPack(pack, manifest, "test.mcaddon")), null);
    }
}
