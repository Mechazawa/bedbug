package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.camera.CameraShake;
import at.ioexception.mcaddon.client.render.PlayerCamera;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.List;

/**
 * A pack's script shakes one player's camera and stops it again.
 *
 * The test add-on's `tp:shake` probe calls `Camera.addShake` with a rotational shake of intensity
 * two, and `tp:still` calls `Camera.stopShaking`. Only the player's own client draws a shake, so
 * what is asserted is the shake that client is running.
 */
public final class CameraShakeClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:shake");
            context.waitFor(client -> !PlayerCamera.shakes().isEmpty());
            List<CameraShake> running = context.computeOnClient(client -> PlayerCamera.shakes());
            Assert.that(running.size() == 1 && running.get(0).rotational(),
                "The script asked for one rotational shake and the client runs " + running + ".");
            Assert.near(running.get(0).intensity(), 2, 0.001, "the shake's intensity");

            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:still");
            context.waitFor(client -> PlayerCamera.shakes().isEmpty());
        }
    }
}
