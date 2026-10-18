package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.PlayerFade;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * `/camera ... fade` covers the whole screen in the colour it names and takes it away again, and
 * `fov_set` and `fov_clear` move the field of view the camera is drawn with.
 *
 * The fade is read back as the last rectangle the heads-up display filled across the whole screen,
 * and the field of view off the camera the frame was drawn with.
 */
public final class CameraFadeClientGameTest implements FabricClientGameTest {
    private static final int RED = 0xFF0000;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("bedrock camera @a fade time 0.2 1.5 0.2 color 255 0 0");
            context.waitFor(client -> PlayerFade.opacity() >= 1f);
            context.waitTicks(2);
            int drawn = context.computeOnClient(client -> Drawn.lastScreenFill());
            Assert.that((drawn & 0xFFFFFF) == RED && drawn >>> 24 == 0xFF,
                "the screen was not covered in opaque red: " + Integer.toHexString(drawn));
            context.waitFor(client -> PlayerFade.opacity() <= 0f);

            world.getServer().runCommand("bedrock camera @a fade");
            context.waitFor(client -> PlayerFade.opacity() > 0f);
            Assert.that(context.computeOnClient(client -> PlayerFade.colour()) == 0,
                "a fade naming no colour is not black.");
            context.waitFor(client -> PlayerFade.opacity() <= 0f);

            world.getServer().runCommand("bedrock camera @a fov_set 40");
            context.waitFor(client -> Math.abs(fov(client) - 40) < 0.5);
            world.getServer().runCommand("bedrock camera @a fov_clear 0.5 in_out_sine");
            context.waitFor(client -> Math.abs(fov(client) - client.options.fov().get()) < 0.5);
        }
    }

    private static float fov(net.minecraft.client.Minecraft client) {
        return Worlds.camera(client).getFov();
    }
}
