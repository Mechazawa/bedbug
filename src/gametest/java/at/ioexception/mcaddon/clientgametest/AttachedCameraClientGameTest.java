package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * `/camera attach_to_entity`, `/camera detach_from_entity` and `Camera.attachToEntity`, which hang the
 * camera off an entity other than the player.
 *
 * With no orbit camera set the camera looks out of the entity's eyes, or from the point a script's
 * locator names. An orbit camera set while attached circles the entity instead of the player, and
 * detaching gives the orbit back to the player. A player is refused, because Bedrock attaches only to
 * an entity that is not one, and clearing the camera ends the attachment.
 */
public final class AttachedCameraClientGameTest implements FabricClientGameTest {
    private static final Vec3 FEET = new Vec3(0.5, 121, 8.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "creative");
            Cameras.stand(context, world, FEET, 180);

            world.getServer().runCommand("bedrock camera @a attach_to_entity @e[type=minecraft:armor_stand,c=1]");
            Cameras.placed(context, client -> Cameras.stand(client).getEyePosition(), 0, 180,
                "attach_to_entity on an armour stand facing north, seen from its eyes");

            world.getServer().runCommand("bedrock camera @a set minecraft:follow_orbit");
            Cameras.placed(context, client -> Cameras.behind(Cameras.stand(client).getBoundingBox().getCenter(), 0, 0,
                10), 0, 0, "minecraft:follow_orbit while attached, which circles the armour stand");

            world.getServer().runCommand("bedrock camera @a detach_from_entity");
            Cameras.placed(context, client -> Cameras.behind(Cameras.middle(client), 0, 0, 10), 0, 0,
                "minecraft:follow_orbit once detached, which circles the player again");

            world.getServer().runCommand("bedrock camera @a clear");
            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:camera_attach");
            Cameras.placed(context, client -> Cameras.stand(client).position(), 0, 180,
                "Camera.attachToEntity({ entity: <the armour stand>, locator: EntityAttachPoint.Feet })");

            world.getServer().runCommand("bedrock camera @a clear");
            Worlds.waitForClient(context, client -> Cameras.at(client, client.player.getEyePosition()),
                Cameras.PATIENCE,
                () -> "/camera clear ends an attachment and gives the camera back to the player's eyes, and it"
                    + " stands at " + Cameras.position(Minecraft.getInstance()) + ".");

            String player = Worlds.said(world.getServer(), "bedrock camera @a attach_to_entity @p");
            Assert.that(player.contains("not a player"),
                "attach_to_entity naming a player is refused, and /camera said: " + player);
        }
    }
}
