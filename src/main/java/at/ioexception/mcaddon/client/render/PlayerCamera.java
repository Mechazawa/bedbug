package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.camera.AttachPoint;
import at.ioexception.mcaddon.bedrock.camera.CameraAttachment;
import at.ioexception.mcaddon.bedrock.camera.CameraInstruction;
import at.ioexception.mcaddon.bedrock.camera.CameraOrbit;
import at.ioexception.mcaddon.bedrock.camera.CameraPlacement;
import at.ioexception.mcaddon.bedrock.camera.CameraShake;
import at.ioexception.mcaddon.bedrock.camera.CameraSpline;
import at.ioexception.mcaddon.bedrock.camera.CameraTarget;
import at.ioexception.mcaddon.bedrock.camera.CameraTracking;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.client.PlayerControl;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * What `/camera` and `/camerashake` did to this client's own view.
 *
 * The server decides and tells each player separately, so everything here is one player's own state
 * and nothing about it is shared. The camera is set up once a frame and this is asked for the
 * placement at the end of that, which is why every order is held as a target rather than written into
 * the camera when it arrives.
 *
 * A frame's placement is built in layers: the view's own placement, which an orbit camera works out
 * from the entity it follows and an attachment from the entity it hangs off; a spline carrying a free
 * camera along its curve; a target turning a free camera after an entity; the ease from wherever the
 * camera was; and the shakes on top.
 */
public final class PlayerCamera {
    private static final List<Running> SHAKES = new ArrayList<>();

    private static CameraView view = CameraView.CLEARED;
    private static long viewStart;
    private static CameraPlacement easeFrom;
    private static CameraPlacement last;
    private static CameraType chosen;
    private static Camera ears;

    /** The turn a fixed boom keeps, which is worked out once as the view arrives. */
    private static CameraPlacement boom;
    private static CameraAttachment attachment = CameraAttachment.DETACHED;
    private static CameraTarget target = CameraTarget.REMOVED;
    /** The turn the target has brought the camera to, null until the first frame that follows it. */
    private static CameraPlacement tracked;
    private static CameraSpline spline;
    private static long splineStart;
    private static long lastFrame;

    private record Running(CameraShake shake, long start) {
    }

    private PlayerCamera() {
    }

    /** What a mixin lets this mod change about a camera the game has already set up. */
    public interface Adjustable {
        CameraPlacement mcaddon$placement();

        void mcaddon$placeAt(CameraPlacement placement);
    }

    /**
     * Takes an order from the server.
     *
     * A view replaces the camera and everything a target or a spline did to it. An attachment stays
     * through a new view until it is detached or the camera is cleared.
     */
    public static void accept(CameraInstruction instruction) {
        if (instruction instanceof CameraView incoming) {
            take(incoming);
        } else if (instruction instanceof CameraTarget incoming) {
            target = incoming;
            tracked = null;
        } else if (instruction instanceof CameraAttachment incoming) {
            attachment = incoming;
            chooseCamera();
        } else if (instruction instanceof CameraSpline incoming) {
            spline = incoming;
            splineStart = System.currentTimeMillis();
        }
    }

    /**
     * Takes the view the server set, easing from wherever the camera is at this moment.
     *
     * An orbit camera that turns with the player turns the player to the rotation the view states; a
     * fixed boom keeps that rotation, or the player's own as it is now, for as long as it is set.
     */
    private static void take(CameraView incoming) {
        if (incoming.cleared()) {
            clear();
            return;
        }

        easeFrom = incoming.easing() == null ? null : last;
        viewStart = System.currentTimeMillis();
        view = incoming;
        target = CameraTarget.REMOVED;
        tracked = null;
        spline = null;
        boom = null;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && incoming.preset().orbits()) {
            CameraOrbit orbit = incoming.orbit();
            Float pitch = incoming.xRot() != null ? incoming.xRot() : orbit.startXRot();
            Float yaw = incoming.yRot() != null ? incoming.yRot() : orbit.startYRot();
            if (incoming.preset().turnsWithPlayer()) {
                turn(player, pitch == null ? player.getXRot() : pitch, yaw == null ? player.getYRot() : yaw);
            } else {
                boom = new CameraPlacement(Vec3.ZERO, pitch == null ? player.getXRot() : pitch,
                    yaw == null ? player.getYRot() : yaw, true);
                if (orbit.alignsFollowed()) turn(player, player.getXRot(), boom.yRot());
            }
        }
        PlayerControl.seed();
        chooseCamera();
    }

    public static void shake(CameraShake incoming) {
        if (incoming.stops()) {
            SHAKES.clear();
            return;
        }
        SHAKES.add(new Running(incoming, System.currentTimeMillis()));
    }

    /** The shakes the server has started on this view and not yet seen run out or stopped. */
    public static List<CameraShake> shakes() {
        return SHAKES.stream().map(Running::shake).toList();
    }

    /** Gives back the camera the player was playing with, and drops every order and running shake. */
    public static void clear() {
        view = CameraView.CLEARED;
        easeFrom = null;
        boom = null;
        attachment = CameraAttachment.DETACHED;
        target = CameraTarget.REMOVED;
        tracked = null;
        spline = null;
        SHAKES.clear();
        chooseCamera();
    }

    /**
     * Moves a camera the game has finished setting up to where the server asked for it.
     *
     * Called once a frame from the mixin. With nothing set, or with no world to follow anything in,
     * this only records where the camera ended up, which is what an ease that starts on a later frame
     * reads as its first placement.
     */
    public static void apply(Adjustable camera, float partialTick) {
        long now = System.currentTimeMillis();
        float frame = lastFrame == 0 ? 0f : elapsed(lastFrame, now);
        lastFrame = now;
        SHAKES.removeIf(running -> running.shake().finishedAt(elapsed(running.start(), now)));

        CameraPlacement natural = PlayerControl.turnedCamera(camera.mcaddon$placement());
        boolean unset = view.cleared() && attachment.detached() && SHAKES.isEmpty();
        if (unset || Minecraft.getInstance().player == null || Minecraft.getInstance().level == null) {
            last = natural;
            return;
        }

        CameraPlacement placed = eased(following(framed(natural, partialTick, now), frame, partialTick), now);
        for (Running running : SHAKES) {
            placed = running.shake().displace(placed, elapsed(running.start(), now));
        }

        last = placed;
        camera.mcaddon$placeAt(placed);
    }

    /**
     * The view's own placement.
     *
     * An orbit camera circles the entity it follows: the one the camera is attached to, or the
     * player. An attachment puts any other camera but a free one at the entity's eyes. A free or a
     * gameplay camera is whatever the preset gives, with the parts the command named on top, and a
     * point to face is turned into a rotation on the first frame the view is drawn, from where its
     * camera stands on that frame. A spline then carries a free camera along its curve.
     */
    private static CameraPlacement framed(CameraPlacement natural, float partialTick, long now) {
        Entity attached = attached();
        boolean set = !view.cleared();
        if (set && view.preset().orbits()) {
            Vec3 pivot = attached != null ? attachment.pivotOn(attached, partialTick)
                : AttachPoint.BODY.on(Minecraft.getInstance().player, partialTick);
            return view.orbit().around(pivot, boom != null ? boom.xRot() : natural.xRot(),
                boom != null ? boom.yRot() : natural.yRot());
        }
        if (attached != null && (!set || view.preset() != CameraView.Preset.FREE)) {
            return attachment.viewFrom(attached, partialTick);
        }
        if (!set) return natural;

        view = view.facingFrom(natural.position());
        CameraPlacement placed = new CameraPlacement(view.position() == null ? natural.position() : view.position(),
            view.xRot() == null ? natural.xRot() : view.xRot(),
            view.yRot() == null ? natural.yRot() : view.yRot(),
            view.preset() != CameraView.Preset.FIRST_PERSON);
        return spline != null && view.preset() == CameraView.Preset.FREE
            ? spline.placementAt(elapsed(splineStart, now), placed) : placed;
    }

    /**
     * The placement turned after the target, for a free camera that has one.
     *
     * A target the client has not got holds the camera at the turn it last had, and one further than
     * Bedrock follows anything is dropped.
     */
    private static CameraPlacement following(CameraPlacement placed, float frame, float partialTick) {
        if (target.removed() || view.cleared() || view.preset() != CameraView.Preset.FREE) return placed;

        Entity entity = Minecraft.getInstance().level.getEntity(target.entity());
        CameraPlacement current = tracked == null ? placed
            : new CameraPlacement(placed.position(), tracked.xRot(), tracked.yRot(), placed.detached());
        if (entity == null) return current;

        Vec3 point = target.pointOn(entity, partialTick);
        if (point.distanceTo(placed.position()) > CameraTracking.LOST) {
            target = CameraTarget.REMOVED;
            tracked = null;
            return placed;
        }
        tracked = view.tracking().follow(current, placed, point, frame, tracked == null);
        return tracked;
    }

    private static CameraPlacement eased(CameraPlacement target, long now) {
        if (easeFrom == null || view.easing() == null || view.seconds() <= 0f) return target;

        float progress = elapsed(viewStart, now) / view.seconds();
        if (progress >= 1f) {
            easeFrom = null;
            return target;
        }
        return easeFrom.blend(target, view.easing().at(progress));
    }

    /** The entity the camera hangs off, or null while it is not attached or the client has not got it. */
    private static Entity attached() {
        if (attachment.detached()) return null;
        Entity entity = Minecraft.getInstance().level.getEntity(attachment.entity());
        return entity == null || entity.isRemoved() ? null : entity;
    }

    /** Where the camera stood on the last frame drawn under an order, or null before any. */
    public static CameraPlacement placement() {
        return view.cleared() ? null : last;
    }

    /**
     * The camera the sound engine hears from: the one it was handed, or one at the player's eyes
     * while the view asks to hear from the player.
     */
    public static Camera listener(Camera camera) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!view.listensFromPlayer() || player == null) return camera;

        if (ears == null) ears = new Camera();
        ((Adjustable) ears).mcaddon$placeAt(
            new CameraPlacement(player.getEyePosition(), player.getXRot(), player.getYRot(), false));
        return ears;
    }

    /**
     * Switches the client to the Java camera the orders call for, keeping the player's own choice the
     * first time one replaces it so clearing gives back the view they were playing with.
     *
     * Every camera away from the player's eyes is Java's third person one, which draws the player's
     * body and no hand.
     */
    private static void chooseCamera() {
        Minecraft client = Minecraft.getInstance();
        CameraType wanted = !view.cleared() ? cameraTypeOf(view.preset())
            : attachment.detached() ? null : CameraType.THIRD_PERSON_BACK;
        if (wanted == null) {
            if (chosen != null) client.options.setCameraType(chosen);
            chosen = null;
            return;
        }
        if (chosen == null) chosen = client.options.getCameraType();
        client.options.setCameraType(wanted);
    }

    private static CameraType cameraTypeOf(CameraView.Preset preset) {
        return switch (preset) {
            case FIRST_PERSON -> CameraType.FIRST_PERSON;
            case THIRD_PERSON_FRONT -> CameraType.THIRD_PERSON_FRONT;
            case THIRD_PERSON, FREE, FOLLOW_ORBIT, FIXED_BOOM, CONTROL_SCHEME -> CameraType.THIRD_PERSON_BACK;
        };
    }

    /** Turns the player at once, without the frame between drawing the turn under way. */
    private static void turn(LocalPlayer player, float pitch, float yaw) {
        player.setXRot(pitch);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.xRotO = pitch;
        player.yRotO = yaw;
    }

    private static float elapsed(long start, long now) {
        return (now - start) / 1000f;
    }
}
