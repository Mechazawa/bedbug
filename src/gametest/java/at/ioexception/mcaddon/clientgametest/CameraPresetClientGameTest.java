package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The camera presets a pack describes in `cameras/presets/`, set by `/camera` and by a script.
 *
 * The test pack's `tp:overlook` is a free camera at 0.5 120 0.5 turned to a pitch of 20 and a yaw of
 * 45, hearing from the player; `tp:overlook_east` inherits all of that and states only its own x and
 * yaw. The presets built on the orbit cameras are `OrbitCameraClientGameTest`'s.
 *
 * Each preset is read back off the camera the client draws from and the place its sound engine
 * hears from: an inherited field that went missing leaves the camera somewhere else, and a listener
 * left on the camera hears from 120 blocks up.
 */
public final class CameraPresetClientGameTest implements FabricClientGameTest {
    private static final double TOLERANCE = 1e-3;
    private static final int PATIENCE = 200;

    private static final Vec3 OVERLOOK = new Vec3(0.5, 120, 0.5);
    private static final Vec3 EAST = new Vec3(4.5, 120, 0.5);
    private static final Vec3 LOWERED = new Vec3(0.5, 110, 0.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);

            world.getServer().runCommand("bedrock camera @a set tp:overlook_east");
            placed(context, EAST, 20, -90, "/camera @a set tp:overlook_east, which inherits its height, its z and"
                + " its pitch from tp:overlook");
            Worlds.waitForClient(context, client -> hearing(client).distanceTo(client.player.getEyePosition()) < 0.01,
                PATIENCE, () -> "tp:overlook_east inherits \"listener\": \"player\", and the client hears from "
                    + hearing(Minecraft.getInstance()) + " while the player's eyes are at "
                    + Minecraft.getInstance().player.getEyePosition() + ".");

            world.getServer().runCommand("bedrock camera @a set tp:overlook ease 1 linear pos 0.5 110 0.5");
            placed(context, LOWERED, 20, 45, "/camera @a set tp:overlook ease 1 linear pos 0.5 110 0.5, which"
                + " moves the preset and keeps its rotation");

            world.getServer().runCommand("bedrock camera @a set minecraft:free pos 0.5 100 0.5 rot 0 0");
            Worlds.waitForClient(context, client -> hearing(client).distanceTo(Worlds.camera(client).position()) < 0.01,
                PATIENCE, () -> "minecraft:free hears from the camera as Bedrock's presets do by default, and the"
                    + " client hears from " + hearing(Minecraft.getInstance()) + ".");

            script(context, world);
        }
    }

    /** `Camera.setCamera` and `Camera.setDefaultCamera` set the same presets, and `Camera.clear` ends them. */
    private static void script(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock execute as @a run scriptevent tp:camera");
        placed(context, EAST, 10, 180, "Camera.setCamera(\"tp:overlook_east\", { rotation: { x: 10, y: 180 } })");

        world.getServer().runCommand("bedrock execute as @a run scriptevent tp:camera_default");
        placed(context, OVERLOOK, 20, 45, "Camera.setDefaultCamera(\"tp:overlook\")");

        world.getServer().runCommand("bedrock execute as @a run scriptevent tp:camera_clear");
        Worlds.waitForClient(context,
            client -> Worlds.camera(client).position().distanceTo(client.player.getEyePosition()) < 0.01, PATIENCE,
            () -> "Camera.clear gave the camera back to the player, and it stands at "
                + Worlds.camera(Minecraft.getInstance()).position() + ".");
    }

    private static void placed(ClientGameTestContext context, Vec3 place, float pitch, float yaw, String what) {
        Worlds.waitForClient(context, client -> Worlds.camera(client).position().distanceTo(place) < TOLERANCE
                && Math.abs(Worlds.camera(client).xRot() - pitch) < 0.1
                && Math.abs(Mth.wrapDegrees(Worlds.camera(client).yRot() - yaw)) < 0.1, PATIENCE,
            () -> "Expected the camera under " + what + " at " + place + " with a pitch of " + pitch + " and a yaw of "
                + yaw + ", and it stands at " + Worlds.camera(Minecraft.getInstance()).position() + " with "
                + Worlds.camera(Minecraft.getInstance()).xRot() + " and "
                + Worlds.camera(Minecraft.getInstance()).yRot() + ".");
    }

    private static Vec3 hearing(Minecraft client) {
        return client.getSoundManager().getListenerTransform().position();
    }
}
