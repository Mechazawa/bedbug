package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

/**
 * `minecraft:follow_orbit` and `minecraft:control_scheme_camera`, the cameras that circle the player
 * at a radius and turn as the player turns.
 *
 * The camera stands `radius` blocks back from the middle of the player's box along the way the player
 * looks, ten blocks unless a preset says otherwise, and looks the same way. `entity_offset` moves the
 * point it circles and `view_offset` slides it across its own screen. `rot` turns the player, which
 * turns the orbit with them.
 *
 * The test pack's `tp:orbit` inherits from `minecraft:follow_orbit` with a radius of six, a view
 * offset of one block right and one up, and an entity offset of one block up. Its script sets the
 * same preset with a view offset of three blocks up in place of the preset's own.
 */
public final class OrbitCameraClientGameTest implements FabricClientGameTest {
    private static final Vec3 RAISED = new Vec3(0, 1, 0);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "creative");

            world.getServer().runCommand("bedrock camera @a set minecraft:follow_orbit");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 0, 0, 10), 0, 0,
                "minecraft:follow_orbit, ten blocks behind a player looking south");

            world.getServer().runCommand("bedrock camera @a set minecraft:follow_orbit rot 30 90");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 30, 90, 10), 30, 90,
                "minecraft:follow_orbit rot 30 90, which turns the player and the orbit with them");
            Assert.near(context.computeOnClient(client -> client.player.getYRot()), 90, 0.1,
                "the player's yaw after /camera ... set minecraft:follow_orbit rot 30 90");

            world.getServer().runCommand("bedrock camera @a set tp:orbit");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client).add(RAISED), 30, 90, 6)
                    .add(Cameras.right(90)).add(Cameras.up(30, 90)), 30, 90,
                "tp:orbit, six blocks back from a point a block over the player's middle and slid a block right"
                    + " and a block up");

            world.getServer().runCommand(
                "bedrock camera @a set minecraft:follow_orbit view_offset 2 0 entity_offset 0 2 0");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client).add(0, 2, 0), 30, 90, 10)
                    .add(Cameras.right(90).scale(2)), 30, 90,
                "minecraft:follow_orbit view_offset 2 0 entity_offset 0 2 0");

            world.getServer().runCommand("tp @a 3.5 120 0.5 0 0");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client).add(0, 2, 0), 0, 0, 10)
                    .add(Cameras.right(0).scale(2)), 0, 0,
                "minecraft:follow_orbit after the player it circles was moved and turned");

            world.getServer().runCommand("bedrock camera @a set minecraft:control_scheme_camera");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 0, 0, 10), 0, 0,
                "minecraft:control_scheme_camera, which circles the player as minecraft:follow_orbit does");

            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:camera_orbit");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client).add(RAISED), 0, 0, 6)
                    .add(Cameras.up(0, 0).scale(3)), 0, 0,
                "Camera.setCamera(\"tp:orbit\", { viewOffset: { x: 0, y: 3 } })");

            refused(world);
            world.getServer().runCommand("bedrock camera @a clear");
        }
    }

    /** A position belongs to a camera that stands still and an offset to one that follows. */
    private static void refused(TestSingleplayerContext world) {
        String placed = Worlds.said(world.getServer(), "bedrock camera @a set minecraft:follow_orbit pos 1 2 3");
        Assert.that(placed.contains("minecraft:follow_orbit follows"),
            "a position given to minecraft:follow_orbit is refused, and /camera said: " + placed);
        String offset = Worlds.said(world.getServer(), "bedrock camera @a set minecraft:free view_offset 1 1");
        Assert.that(offset.contains("view_offset"),
            "a view offset given to minecraft:free is refused, and /camera said: " + offset);
    }
}
