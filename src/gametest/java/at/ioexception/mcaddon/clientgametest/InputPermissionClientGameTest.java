package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.client.PlayerInput;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

/**
 * `/inputpermission` takes walking and looking about away from a player on their own client, and
 * gives them back.
 *
 * The forward key is held and the mouse moved the way a player would, so what is measured is whether
 * the player walked or turned by them, standing on a floor laid for the purpose.
 */
public final class InputPermissionClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;
    private static final int HELD = 20;
    private static final Vec3 STANDING = new Vec3(0.5, 101, 0.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("fill -8 100 -8 8 100 8 minecraft:stone");
            world.getServer().runCommand("tp @a " + STANDING.x + " " + STANDING.y + " " + STANDING.z + " 0 0");
            Worlds.waitForClient(context, client -> client.player.onGround()
                    && client.player.position().distanceTo(STANDING) < 0.01, PATIENCE,
                () -> "Expected the player to stand on the floor at " + STANDING + ".");

            walking(context, world);
            looking(context, world);
        }
    }

    private static void walking(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock inputpermission set @a lateral_movement disabled");
        Worlds.waitForClient(context, client -> !PlayerInput.allows(InputPermission.MOVE_FORWARD), PATIENCE,
            () -> "Expected the lateral movement taken away to reach the client.");
        Assert.that(walked(context) < 0.01, "Expected the player to stay put holding the forward key with lateral"
            + " movement taken away.");

        world.getServer().runCommand("bedrock inputpermission set @a lateral_movement enabled");
        Worlds.waitForClient(context, client -> PlayerInput.allows(InputPermission.MOVE_FORWARD), PATIENCE,
            () -> "Expected the lateral movement given back to reach the client.");
        Assert.that(walked(context) > 0.5, "Expected the player to walk holding the forward key once lateral"
            + " movement was given back.");
    }

    private static void looking(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock inputpermission set @a camera disabled");
        Worlds.waitForClient(context, client -> !PlayerInput.allows(InputPermission.CAMERA), PATIENCE,
            () -> "Expected the camera taken away to reach the client.");
        Assert.that(Math.abs(turned(context)) < 0.01, "Expected the player to face the same way while the mouse"
            + " moved with the camera taken away.");

        world.getServer().runCommand("bedrock inputpermission set @a camera enabled");
        Worlds.waitForClient(context, client -> PlayerInput.allows(InputPermission.CAMERA), PATIENCE,
            () -> "Expected the camera given back to reach the client.");
        Assert.that(Math.abs(turned(context)) > 1, "Expected the player to turn with the mouse once the camera"
            + " was given back.");
    }

    /** How far the player walked, across the floor, while the forward key was held. */
    private static double walked(ClientGameTestContext context) {
        Vec3 from = context.computeOnClient(client -> client.player.position());
        context.getInput().holdKeyFor(options -> options.keyUp, HELD);
        context.waitTicks(2);
        Vec3 to = context.computeOnClient(client -> client.player.position());
        return Math.hypot(to.x - from.x, to.z - from.z);
    }

    /** How far the player turned while the mouse moved sideways. */
    private static float turned(ClientGameTestContext context) {
        float from = context.computeOnClient(client -> client.player.getYRot());
        context.getInput().moveCursor(200, 0);
        context.waitTicks(2);
        return context.computeOnClient(client -> client.player.getYRot()) - from;
    }
}
