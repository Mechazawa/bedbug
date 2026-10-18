package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * `minecraft:fixed_boom`, the camera held at one angle and one distance from the player, which moves
 * with the player and does not turn with them.
 *
 * The test pack's `tp:boom` inherits from it with a radius of four and a starting rotation of 45
 * degrees down and 30 round. `tp:boom_aligned` inherits that and also turns the player to face the way
 * the camera looks. A boom with no starting rotation of its own keeps the angle the player had when it
 * was set.
 */
public final class BoomCameraClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "creative");

            world.getServer().runCommand("bedrock camera @a set tp:boom");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 45, 30, 4), 45, 30,
                "tp:boom, four blocks back along its own starting rotation");

            world.getServer().runCommand("tp @a 0.5 120 0.5 90 0");
            context.waitFor(client -> Math.abs(client.player.getYRot() - 90) < 0.1);
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 45, 30, 4), 45, 30,
                "tp:boom after the player turned, which leaves the boom where it was");

            world.getServer().runCommand("tp @a 3.5 120 0.5 90 0");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 45, 30, 4), 45, 30,
                "tp:boom after the player moved three blocks east, which the boom follows");

            world.getServer().runCommand("bedrock camera @a set minecraft:fixed_boom");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 0, 90, 10), 0, 90,
                "minecraft:fixed_boom, which keeps the turn the player had when it was set");
            world.getServer().runCommand("tp @a 3.5 120 0.5 0 0");
            context.waitFor(client -> Math.abs(client.player.getYRot()) < 0.1);
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 0, 90, 10), 0, 90,
                "minecraft:fixed_boom after the player turned back to the south");

            world.getServer().runCommand("bedrock camera @a set tp:boom rot 20 -45");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 20, -45, 4), 20, -45,
                "tp:boom rot 20 -45, whose rotation replaces the preset's starting one");

            world.getServer().runCommand("bedrock camera @a set tp:boom_aligned");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 45, 30, 4), 45, 30,
                "tp:boom_aligned");
            Worlds.waitForClient(context, client -> Math.abs(client.player.getYRot() - 30) < 0.1, Cameras.PATIENCE,
                () -> "tp:boom_aligned turns the player to the camera's yaw of 30, and the player faces "
                    + net.minecraft.client.Minecraft.getInstance().player.getYRot() + ".");

            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:camera_boom");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client).add(0, 2, 0), 0, 30, 10)
                    .add(Cameras.right(30)), 0, 30,
                "Camera.setCamera(\"minecraft:fixed_boom\", { entityOffset: { x: 0, y: 2, z: 0 },"
                    + " viewOffset: { x: 1, y: 0 } }) for a player facing a yaw of 30");

            world.getServer().runCommand("bedrock camera @a clear");
        }
    }
}
