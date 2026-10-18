package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * `/camera play_spline` and `Camera.playAnimation`, which carry a free camera along a curve.
 *
 * The test pack's `camera/splines/sweep.json` names `tp:sweep`: a linear path over eight seconds from
 * 0.5 121 0.5 ten blocks east and then ten up, turning from south to a pitch of 30 and a yaw of 90.
 * The script plays a Catmull-Rom curve through four points over eight seconds that ends at 8.5 121 8.5 turned to a pitch
 * of 10 and a yaw of -90.
 *
 * The camera is seen partway along the path, then stays where the path ends with the rotation of the
 * last key frame. A path runs on the client's clock, so each is long enough for a client drawing a
 * frame a second to be seen partway along it. Bedrock plays a spline on the free camera alone, so a player on any other camera is
 * refused, and so is a name no pack describes.
 */
public final class SplineCameraClientGameTest implements FabricClientGameTest {
    private static final Vec3 SWEPT = new Vec3(10.5, 131, 0.5);
    private static final Vec3 CURVED = new Vec3(8.5, 121, 8.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "creative");

            world.getServer().runCommand("bedrock camera @a set minecraft:free pos 0.5 121 0.5 rot 0 0");
            Cameras.placed(context, new Vec3(0.5, 121, 0.5), 0, 0, "minecraft:free before the spline");

            world.getServer().runCommand("bedrock camera @a play_spline tp:sweep");
            Worlds.waitForClient(context, client -> {
                Vec3 at = Cameras.position(client);
                return at.x > 1 && at.x < 10 && Math.abs(at.y - 121) < 1e-3;
            }, Cameras.PATIENCE, () -> "tp:sweep carries the camera east along its first leg and it was never seen"
                + " there; it is at " + Cameras.position(Minecraft.getInstance()) + ".");
            Cameras.placed(context, SWEPT, 30, 90, "the end of tp:sweep");
            context.waitTicks(10);
            Cameras.placed(context, SWEPT, 30, 90, "tp:sweep half a second after it ended");

            world.getServer().runCommand("bedrock camera @a set minecraft:free pos 0.5 121 0.5 rot 0 0");
            Cameras.placed(context, new Vec3(0.5, 121, 0.5), 0, 0, "minecraft:free before the script's curve");
            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:camera_spline");
            Worlds.waitForClient(context, client -> Cameras.position(client).y > 122, Cameras.PATIENCE,
                () -> "the script's curve rises over the platform and the camera was never seen above it; it is at "
                    + Cameras.position(Minecraft.getInstance()) + ".");
            Cameras.placed(context, CURVED, 10, -90, "the end of the script's Catmull-Rom curve");

            String missing = Worlds.said(world.getServer(), "bedrock camera @a play_spline tp:nowhere");
            Assert.that(missing.contains("tp:nowhere"),
                "play_spline naming a spline no pack describes is refused, and /camera said: " + missing);
            world.getServer().runCommand("bedrock camera @a set minecraft:third_person");
            String refused = Worlds.said(world.getServer(), "bedrock camera @a play_spline tp:sweep");
            Assert.that(refused.contains("minecraft:free"),
                "play_spline on a third person camera is refused, and /camera said: " + refused);
            world.getServer().runCommand("bedrock camera @a clear");
        }
    }
}
