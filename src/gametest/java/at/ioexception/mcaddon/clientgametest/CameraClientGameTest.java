package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * `/camera` and `/camerashake` move the camera a frame is drawn from.
 *
 * A free camera set at a position and a rotation is drawn from exactly there, an ease moves it there
 * over the seconds asked for rather than at once, a shake moves it off that place until it is
 * stopped, and clearing gives back the player's own first person view.
 */
public final class CameraClientGameTest implements FabricClientGameTest {
    private static final double TOLERANCE = 1e-3;
    private static final int PATIENCE = 200;

    private static final Vec3 PLACED = new Vec3(0.5, 100, 0.5);
    private static final Vec3 EASED = new Vec3(0.5, 110, 0.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            CameraType chosen = context.computeOnClient(client -> client.options.getCameraType());

            world.getServer().runCommand("camera @a set minecraft:free pos 0.5 100 0.5 rot 30 45");
            Worlds.waitForClient(context, client -> at(client, PLACED), PATIENCE,
                () -> "the camera was set free at " + PLACED + " and is at " + position(Minecraft.getInstance()) + ".");
            Assert.near(context.computeOnClient(client -> client.gameRenderer.mainCamera().xRot()), 30, TOLERANCE,
                "the camera's pitch under /camera ... rot 30 45");
            Assert.near(context.computeOnClient(client -> client.gameRenderer.mainCamera().yRot()), 45, TOLERANCE,
                "the camera's yaw under /camera ... rot 30 45");

            world.getServer().runCommand("camera @a set minecraft:free ease 2 linear pos 0.5 110 0.5 rot 30 45");
            Worlds.waitForClient(context, client -> {
                double height = position(client).y;
                return height > PLACED.y + 1 && height < EASED.y - 1;
            }, PATIENCE, () -> "the camera eases over two seconds from " + PLACED + " to " + EASED
                + " and was never seen between the two.");
            Worlds.waitForClient(context, client -> at(client, EASED), PATIENCE,
                () -> "the camera's ease never reached " + EASED + "; it is at " + position(Minecraft.getInstance()) + ".");

            world.getServer().runCommand("camerashake add @a 1 10 positional");
            Worlds.waitForClient(context, client -> position(client).distanceTo(EASED) > 0.01, PATIENCE,
                () -> "a shake was added and the camera never moved off " + EASED + ".");
            world.getServer().runCommand("camerashake stop @a");
            Worlds.waitForClient(context, client -> at(client, EASED), PATIENCE,
                () -> "the shake was stopped and the camera never settled back at " + EASED + ".");

            world.getServer().runCommand("camera @a clear");
            Worlds.waitForClient(context, client -> position(client).distanceTo(client.player.getEyePosition()) < 0.01
                    && client.options.getCameraType() == chosen, PATIENCE,
                () -> "the camera was cleared and was not given back to the player's own " + chosen + " view.");
        }
    }

    private static boolean at(Minecraft client, Vec3 place) {
        return position(client).distanceTo(place) < TOLERANCE;
    }

    private static Vec3 position(Minecraft client) {
        return client.gameRenderer.mainCamera().position();
    }
}
