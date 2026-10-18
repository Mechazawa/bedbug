package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.input.ControlScheme;
import at.ioexception.mcaddon.client.PlayerControl;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * `/controlscheme`, a camera preset's `control_scheme` and `Player.setControlScheme` change what the
 * movement keys and the mouse do to the player, on the player's own client.
 *
 * Each scheme is driven the way a player would drive it, holding a key or moving the mouse, and read
 * back as where the player stands and which way they face: a key that strafes where it should turn,
 * a walk along the player's facing where it should follow the camera's, or a mouse that turns the
 * player where it should move a pointer each leaves the player somewhere else.
 */
public final class ControlSchemeClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;
    private static final int HELD = 10;
    private static final double STANDING_HEIGHT = Stage.FLOOR + 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "creative");

            refusedWithoutACamera(context, world);
            playerRelative(context, world);
            mouseTurnsTheOrbit(context, world);
            cameraRelativeFromAPreset(context, world);
            pointer(context, world);
            script(context, world);
        }
    }

    /** No camera is set, so the player is on a gameplay view, which allows the locked scheme alone. */
    private static void refusedWithoutACamera(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock controlscheme @a set player_relative");
        context.waitTicks(5);
        Assert.that(context.computeOnClient(client -> PlayerControl.scheme()) == ControlScheme.DEFAULT,
            "Expected /controlscheme ... set player_relative to be refused while no camera is set, and the client"
                + " is on " + context.computeOnClient(client -> PlayerControl.scheme()) + ".");
    }

    /** The side keys turn the player where they would strafe, and the player looks level. */
    private static void playerRelative(ClientGameTestContext context, TestSingleplayerContext world) {
        stand(context, world, 0, 30);
        world.getServer().runCommand("bedrock camera @a set minecraft:free pos 0.5 130 0.5 rot 60 0");
        on(context, world, "player_relative", ControlScheme.PLAYER_RELATIVE);

        Vec3 from = position(context);
        float yawBefore = yaw(context);
        context.getInput().holdKeyFor(options -> options.keyRight, HELD);
        context.waitTicks(2);
        Vec3 to = position(context);
        Assert.that(Math.hypot(to.x - from.x, to.z - from.z) < 0.05, "Expected the right key to turn the player"
            + " in place under player_relative, and it moved them from " + from + " to " + to + ".");
        Assert.that(Mth.wrapDegrees(yaw(context) - yawBefore) > 30, "Expected the right key to turn the player"
            + " right under player_relative, from a yaw of " + yawBefore + " to " + yaw(context) + ".");
        Assert.near(context.computeOnClient(client -> client.player.getXRot()), 0, 0.01,
            "the player's pitch under player_relative, which keeps the view level");
        Assert.that(Math.abs(turned(context)) < 0.01,
            "Expected the mouse to leave the player's facing alone under player_relative.");
    }

    /** Under `player_relative` the mouse turns a follow orbit camera round the player and not the player. */
    private static void mouseTurnsTheOrbit(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock camera @a set minecraft:follow_orbit");
        on(context, world, "player_relative", ControlScheme.PLAYER_RELATIVE);

        float cameraBefore = context.computeOnClient(client -> Worlds.camera(client).yRot());
        Assert.that(Math.abs(turned(context)) < 0.01,
            "Expected the mouse to leave the player's facing alone under follow_orbit and player_relative.");
        float cameraAfter = context.computeOnClient(client -> Worlds.camera(client).yRot());
        Assert.that(Math.abs(Mth.wrapDegrees(cameraAfter - cameraBefore)) > 10, "Expected the mouse to turn the"
            + " orbit camera under player_relative, and it stayed at a yaw of " + cameraBefore + ".");
    }

    /**
     * `tp:steered` is a free camera looking west whose preset names `camera_relative`: the forward key
     * walks the player west whichever way they faced, and turns them to face that way.
     */
    private static void cameraRelativeFromAPreset(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock controlscheme @a clear");
        stand(context, world, 0, 0);
        world.getServer().runCommand("bedrock camera @a set tp:steered");
        Worlds.waitForClient(context, client -> PlayerControl.scheme() == ControlScheme.CAMERA_RELATIVE, PATIENCE,
            () -> "Expected tp:steered's control_scheme to put the client on camera_relative.");

        Vec3 walked = walk(context);
        Assert.that(walked.x < -0.5 && Math.abs(walked.z) < 0.1, "Expected the forward key to walk the player west,"
            + " the way tp:steered looks, and it moved them by " + walked + ".");
        Assert.near(Mth.wrapDegrees(yaw(context)), 90, 1, "the player's yaw after walking west under camera_relative");
    }

    /**
     * Under `camera_relative_strafe` on a camera looking straight down, the mouse moves a pointer and the
     * player faces the place under it, while the forward key walks the way the top of the screen points.
     */
    private static void pointer(ClientGameTestContext context, TestSingleplayerContext world) {
        stand(context, world, 0, 0);
        world.getServer().runCommand("bedrock camera @a set minecraft:free pos 0.5 135 0.5 rot 90 0");
        on(context, world, "camera_relative_strafe", ControlScheme.CAMERA_RELATIVE_STRAFE);

        context.getInput().moveCursor(300, 0);
        context.waitTicks(2);
        Assert.near(Mth.wrapDegrees(yaw(context)), 90, 2,
            "the player's yaw with the pointer moved to the right of a camera looking down with south at the top");

        Vec3 walked = walk(context);
        Assert.that(walked.z > 0.5 && Math.abs(walked.x) < 0.1, "Expected the forward key to walk the player south,"
            + " toward the top of the screen, and it moved them by " + walked + ".");
        // The place under the pointer stays where it was while the player walks past it, so the
        // player keeps facing toward it rather than the way they walk.
        Assert.that(Mth.wrapDegrees(yaw(context)) > 60, "Expected the player to keep facing the pointer while"
            + " walking south under camera_relative_strafe, and they face a yaw of " + yaw(context) + ".");
    }

    /** `Player.setControlScheme` refuses a scheme the camera does not allow and reports the one in force. */
    private static void script(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock camera @a set minecraft:free pos 0.5 130 0.5 rot 60 0");
        scripted(context, world, "PlayerRelativeStrafe", "tp_control_PlayerRelativeStrafe");
        Worlds.waitForClient(context, client -> PlayerControl.scheme() == ControlScheme.PLAYER_RELATIVE_STRAFE,
            PATIENCE, () -> "Expected Player.setControlScheme to put the client on player_relative_strafe.");
        scripted(context, world, "clear", "tp_control_LockedPlayerRelativeStrafe");

        world.getServer().runCommand("bedrock camera @a clear");
        scripted(context, world, "CameraRelative", "tp_control_refused");
        Worlds.waitForClient(context, client -> PlayerControl.scheme() == ControlScheme.DEFAULT, PATIENCE,
            () -> "Expected the client back on the locked scheme once the camera was cleared.");
        Assert.that(Math.abs(turned(context)) > 1,
            "Expected the mouse to turn the player again once the camera was cleared.");
    }

    private static void scripted(ClientGameTestContext context, TestSingleplayerContext world, String scheme,
        String tag) {
        world.getServer().runCommand("bedrock execute as @a run scriptevent tp:control_scheme " + scheme);
        Worlds.waitForServer(context, world.getServer(), server -> Platform.tagsOf(Stage.player(server)).contains(tag),
            PATIENCE, "Expected tp:control_scheme " + scheme + " to tag the player " + tag + ".");
    }

    private static void on(ClientGameTestContext context, TestSingleplayerContext world, String written,
        ControlScheme scheme) {
        world.getServer().runCommand("bedrock controlscheme @a set " + written);
        Worlds.waitForClient(context, client -> PlayerControl.scheme() == scheme, PATIENCE,
            () -> "Expected /controlscheme ... set " + written + " to reach the client.");
    }

    private static void stand(ClientGameTestContext context, TestSingleplayerContext world, float yaw, float pitch) {
        world.getServer().runCommand("tp @a 0.5 " + STANDING_HEIGHT + " 0.5 " + yaw + " " + pitch);
        Worlds.waitForClient(context, client -> client.player.position().distanceTo(
                new Vec3(0.5, STANDING_HEIGHT, 0.5)) < 0.01 && client.player.onGround(), PATIENCE,
            () -> "Expected the player to stand in the middle of the stage.");
    }

    /** How far the player moved across the floor while the forward key was held. */
    private static Vec3 walk(ClientGameTestContext context) {
        Vec3 from = position(context);
        context.getInput().holdKeyFor(options -> options.keyUp, HELD);
        context.waitTicks(2);
        Vec3 to = position(context);
        return new Vec3(to.x - from.x, 0, to.z - from.z);
    }

    /** How far the player turned while the mouse moved sideways. */
    private static float turned(ClientGameTestContext context) {
        float from = yaw(context);
        context.getInput().moveCursor(200, 0);
        context.waitTicks(2);
        return yaw(context) - from;
    }

    private static Vec3 position(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player.position());
    }

    private static float yaw(ClientGameTestContext context) {
        return context.computeOnClient(client -> client.player.getYRot());
    }
}
