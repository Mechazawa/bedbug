package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.bedrock.camera.CameraPlacement;
import at.ioexception.mcaddon.bedrock.input.ControlScheme;
import at.ioexception.mcaddon.client.render.PlayerCamera;
import at.ioexception.mcaddon.client.render.PlayerFov;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * The control scheme the server put this client's player on, and what it does to the keys and the
 * mouse.
 *
 * `KeyboardInputMixin` hands every tick's movement here once the keys are read, and
 * `MouseHandlerMixin` every turn the mouse asks for, so the game moves and turns the player by what
 * comes back. On any scheme but the locked one the camera keeps a turn of its own, which the mouse
 * moves under the two schemes that turn the camera and which stays where it was under the two that
 * move a pointer.
 */
public final class PlayerControl {
    /**
     * How far the left and right keys turn the player each tick under `player_relative`. Mojang
     * publishes no rate, so this is half a turn a second.
     */
    private static final float TURN_PER_TICK = 9f;
    /** The degrees one unit of mouse movement turns by, which is what Java's own `Entity.turn` uses. */
    private static final double DEGREES_PER_UNIT = 0.15;
    /** How far along the pointer a block is looked for before the pointer is taken to the player's height. */
    private static final double POINTER_REACH = 128;
    private static final int POINTER_ARM = 4;
    private static final int POINTER_COLOUR = 0xFFFFFFFF;

    private static ControlScheme scheme = ControlScheme.DEFAULT;
    private static float cameraXRot;
    private static float cameraYRot;
    /** Where the pointer is, in the window's own pixels. */
    private static double pointerX;
    private static double pointerY;

    private PlayerControl() {
    }

    public static void accept(String name) {
        ControlScheme named = ControlScheme.tryFrom(name);
        scheme = named == null ? ControlScheme.DEFAULT : named;
        seed();
    }

    public static void clear() {
        scheme = ControlScheme.DEFAULT;
    }

    public static ControlScheme scheme() {
        return scheme;
    }

    /** Gives the camera the player's own turn and puts the pointer in the middle of the window. */
    public static void seed() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            cameraXRot = player.getXRot();
            cameraYRot = player.getYRot();
        }
        Window window = Minecraft.getInstance().getWindow();
        pointerX = window.getScreenWidth() / 2.0;
        pointerY = window.getScreenHeight() / 2.0;
    }

    /**
     * Moves the camera or the pointer by a turn the mouse asks for, and answers whether the scheme
     * took it; a turn it did not take turns the player as Java does.
     */
    public static boolean takesMouse(double yaw, double pitch) {
        if (scheme.mouseTurnsCamera()) {
            cameraYRot += (float) (yaw * DEGREES_PER_UNIT);
            cameraXRot = Mth.clamp(cameraXRot + (float) (pitch * DEGREES_PER_UNIT), -90f, 90f);
            return true;
        }
        if (scheme.looksAtPointer()) {
            Window window = Minecraft.getInstance().getWindow();
            pointerX = Mth.clamp(pointerX + yaw, 0, window.getScreenWidth());
            pointerY = Mth.clamp(pointerY + pitch, 0, window.getScreenHeight());
            return true;
        }
        return false;
    }

    /** The camera's placement with the camera's own turn in place of the player's, where the scheme keeps one. */
    public static CameraPlacement turnedCamera(CameraPlacement natural) {
        return scheme == ControlScheme.DEFAULT ? natural
            : new CameraPlacement(natural.position(), cameraXRot, cameraYRot, natural.detached());
    }

    /**
     * One tick's movement, as a sideways and a forward amount along the player's facing, turned into
     * what the scheme makes of it; the player is turned on the way.
     */
    public static Vec2 steer(Vec2 move) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (scheme == ControlScheme.DEFAULT || player == null) return move;

        player.setXRot(0f);
        if (scheme.looksAtPointer()) lookAtPointer(player);
        if (scheme == ControlScheme.PLAYER_RELATIVE) {
            player.setYRot(player.getYRot() - move.x * TURN_PER_TICK);
            return new Vec2(0f, move.y);
        }
        CameraPlacement camera = PlayerCamera.placement();
        float length = (float) Math.hypot(move.x, move.y);
        if (!scheme.walksAlongCamera() || camera == null || length == 0f) return move;

        float heading = camera.yRot() - (float) Math.toDegrees(Math.atan2(move.x, move.y));
        if (scheme == ControlScheme.CAMERA_RELATIVE) {
            player.setYRot(heading);
            return new Vec2(0f, length);
        }
        float across = (float) Math.toRadians(heading - player.getYRot());
        return new Vec2(-length * Mth.sin(across), length * Mth.cos(across));
    }

    /** Draws the pointer the mouse moves, while the scheme has one. */
    public static void drawPointer(Platform.Painter painter) {
        if (!scheme.looksAtPointer() || Minecraft.getInstance().player == null) return;

        Window window = Minecraft.getInstance().getWindow();
        int x = (int) (pointerX * painter.width() / window.getScreenWidth());
        int y = (int) (pointerY * painter.height() / window.getScreenHeight());
        painter.fill(x - POINTER_ARM, y, x + POINTER_ARM + 1, y + 1, POINTER_COLOUR);
        painter.fill(x, y - POINTER_ARM, x + 1, y + POINTER_ARM + 1, POINTER_COLOUR);
    }

    /**
     * Turns the player to face the place under the pointer: the block the pointer's line from the
     * camera reaches, or where that line crosses the height of the player's eyes.
     */
    private static void lookAtPointer(LocalPlayer player) {
        CameraPlacement camera = PlayerCamera.placement();
        if (camera == null) return;

        Window window = Minecraft.getInstance().getWindow();
        double halfHeight = Math.tan(Math.toRadians(PlayerFov.base(Minecraft.getInstance().options.fov().get()) / 2.0));
        double halfWidth = halfHeight * window.getScreenWidth() / window.getScreenHeight();
        double across = (2 * pointerX / window.getScreenWidth() - 1) * halfWidth;
        double up = (1 - 2 * pointerY / window.getScreenHeight()) * halfHeight;

        Vec3 forward = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        float yaw = (float) Math.toRadians(camera.yRot());
        Vec3 right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
        Vec3 direction = forward.add(right.scale(across)).add(right.cross(forward).scale(up)).normalize();

        Vec3 from = camera.position();
        HitResult hit = player.level().clip(new ClipContext(from, from.add(direction.scale(POINTER_REACH)),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 eyes = player.getEyePosition();
        Vec3 point = hit.getType() != HitResult.Type.MISS ? hit.getLocation()
            : Math.abs(direction.y) > 1e-6 && (eyes.y - from.y) / direction.y > 0
                ? from.add(direction.scale((eyes.y - from.y) / direction.y))
                : from.add(direction.scale(POINTER_REACH));
        if (Math.hypot(point.x - eyes.x, point.z - eyes.z) < 1e-3) return;

        player.setYRot(new CameraPlacement(eyes, 0f, 0f, false).turnedTo(point).yRot());
    }
}
