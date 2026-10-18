package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * `/camera target_entity`, `target_center_offset` and `remove_target`, and the preset fields that
 * shape how a free camera follows its target.
 *
 * A free camera keeps its place and turns to the middle of the target's box, moved by the offset,
 * and goes back to its own rotation once the target is removed. The test pack's presets all stand at
 * 0.5 121 0.5 looking south, ten blocks west of an armour stand:
 *
 * - `tp:watch` may turn ten degrees either way and look ten degrees above or below the horizon, and
 *   keeps following at the edge of that;
 * - `tp:lookout` follows nothing further than five blocks away and looks south again when the target
 *   is out of reach;
 * - `tp:slow` turns at thirty degrees a second, and `tp:snap` turns the same way once it has jumped
 *   onto the target on the first frame.
 */
public final class TargetCameraClientGameTest implements FabricClientGameTest {
    private static final Vec3 CAMERA = new Vec3(0.5, 121, 0.5);
    private static final Vec3 FAR = new Vec3(10.5, 121, 0.5);
    private static final Vec3 NEAR = new Vec3(3.5, 121, 0.5);
    private static final String STAND = "@e[type=minecraft:armor_stand,c=1]";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "creative");
            Cameras.stand(context, world, FAR, 0);
            Vec3 middle = context.computeOnClient(client -> Cameras.stand(client).getBoundingBox().getCenter());

            world.getServer().runCommand("bedrock camera @a set minecraft:free pos 0.5 121 0.5 rot 0 0");
            Cameras.placed(context, CAMERA, 0, 0, "minecraft:free pos 0.5 121 0.5 rot 0 0");
            world.getServer().runCommand("bedrock camera @a target_entity " + STAND);
            looking(context, middle, "target_entity on an armour stand ten blocks east");

            world.getServer().runCommand("bedrock camera @a target_entity " + STAND + " target_center_offset 0 5 0");
            looking(context, middle.add(0, 5, 0), "target_entity ... target_center_offset 0 5 0");

            world.getServer().runCommand("bedrock camera @a remove_target");
            Cameras.placed(context, CAMERA, 0, 0, "remove_target, which turns the camera back to its own rotation");

            world.getServer().runCommand("bedrock camera @a set minecraft:first_person");
            String refused = Worlds.said(world.getServer(), "bedrock camera @a target_entity " + STAND);
            Assert.that(refused.contains("minecraft:free"),
                "target_entity on a first person camera is refused, and /camera said: " + refused);

            limits(context, world);
            reach(context, world, middle);
            speed(context, world, middle);

            world.getServer().runCommand("bedrock camera @a clear");
            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:camera_target");
            Vec3 above = middle.add(0, 3, 0);
            Worlds.waitForClient(context, client -> {
                Vec3 towards = above.subtract(Cameras.position(client));
                return Cameras.turned(client, Cameras.pitch(towards), Cameras.yaw(towards));
            }, Cameras.PATIENCE, () -> "Camera.setCamera(\"minecraft:free\", { targetEntity, offsetFromTargetCenter:"
                + " { x: 0, y: 3, z: 0 } }) turns the camera to " + above + ", and from "
                + Cameras.position(Minecraft.getInstance()) + " it looks at "
                + Worlds.camera(Minecraft.getInstance()).xRot()
                + " and " + Worlds.camera(Minecraft.getInstance()).yRot() + ".");
            world.getServer().runCommand("bedrock camera @a clear");
        }
    }

    /** `tp:watch` stops at ten degrees round and ten above the horizon, and keeps following there. */
    private static void limits(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock camera @a set tp:watch");
        world.getServer().runCommand("bedrock camera @a target_entity " + STAND + " target_center_offset 0 20 0");
        Cameras.placed(context, CAMERA, -10, -10, "tp:watch on a target far to its left and high above");
    }

    /** `tp:lookout` looks south while the target is ten blocks away and follows it at three. */
    private static void reach(ClientGameTestContext context, TestSingleplayerContext world, Vec3 middle) {
        world.getServer().runCommand("bedrock camera @a set tp:lookout");
        world.getServer().runCommand("bedrock camera @a target_entity " + STAND);
        context.waitTicks(10);
        Cameras.placed(context, CAMERA, 0, 0, "tp:lookout on a target ten blocks away, past its five block reach");

        world.getServer().runCommand("bedrock tp " + STAND + " " + NEAR.x + " " + NEAR.y + " " + NEAR.z);
        looking(context, middle.add(NEAR.subtract(FAR)), "tp:lookout once its target came within three blocks");
        world.getServer().runCommand("bedrock tp " + STAND + " " + FAR.x + " " + FAR.y + " " + FAR.z);
        Cameras.placed(context, CAMERA, 0, 0, "tp:lookout once its target went back out of reach");
    }

    /**
     * `tp:slow` turns ninety degrees in three seconds and is seen on the way; `tp:snap` is on the target
     * well before a camera turning at that speed could be.
     */
    private static void speed(ClientGameTestContext context, TestSingleplayerContext world, Vec3 middle) {
        world.getServer().runCommand("bedrock camera @a set tp:slow");
        Cameras.placed(context, CAMERA, 0, 0, "tp:slow");
        world.getServer().runCommand("bedrock camera @a target_entity " + STAND);
        Worlds.waitForClient(context, client -> {
            float yaw = Worlds.camera(client).yRot();
            return yaw < -20 && yaw > -70;
        }, Cameras.PATIENCE, () -> "tp:slow turns at thirty degrees a second and was never seen partway round, at "
            + Worlds.camera(Minecraft.getInstance()).yRot() + ".");
        looking(context, middle, "tp:slow once it has turned all the way");

        world.getServer().runCommand("bedrock camera @a set tp:snap");
        Cameras.placed(context, CAMERA, 0, 0, "tp:snap");
        world.getServer().runCommand("bedrock camera @a target_entity " + STAND);
        Vec3 towards = middle.subtract(CAMERA);
        Worlds.waitForClient(context, client -> Cameras.turned(client, Cameras.pitch(towards), Cameras.yaw(towards)),
            15, () -> "tp:snap jumps onto its target on the first frame, and three quarters of a second on it looks"
                + " at " + Worlds.camera(Minecraft.getInstance()).yRot() + ".");
    }

    private static void looking(ClientGameTestContext context, Vec3 point, String what) {
        Vec3 towards = point.subtract(CAMERA);
        Cameras.placed(context, CAMERA, Cameras.pitch(towards), Cameras.yaw(towards), what + ", looking at " + point);
    }
}
