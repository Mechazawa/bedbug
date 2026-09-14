package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.camera.CameraPlacement;
import at.ioexception.mcaddon.bedrock.camera.CameraShake;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * What `/camera` and `/camerashake` did to this client's own view.
 *
 * The server decides and tells each player separately, so everything here is one player's own state
 * and nothing about it is shared. The camera is set up once a frame and this is asked for the
 * placement at the end of that, which is why the view is held as a target rather than written into
 * the camera when the order arrives.
 */
public final class PlayerCamera {
    private static final List<Running> SHAKES = new ArrayList<>();

    private static CameraView view = CameraView.CLEARED;
    private static long viewStart;
    private static CameraPlacement easeFrom;
    private static CameraPlacement last;
    private static CameraType chosen;

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
     * Takes the view the server set, easing from wherever the camera is at this moment.
     *
     * The player's own camera choice is kept the first time a preset replaces it, so clearing gives
     * back the view they were playing with rather than first person.
     */
    public static void accept(CameraView incoming) {
        if (incoming.cleared()) {
            clear();
            return;
        }

        easeFrom = incoming.easing() == null ? null : last;
        viewStart = System.currentTimeMillis();
        view = incoming;

        Minecraft client = Minecraft.getInstance();
        if (chosen == null) chosen = client.options.getCameraType();
        client.options.setCameraType(cameraTypeOf(incoming.preset()));
    }

    public static void shake(CameraShake incoming) {
        if (incoming.stops()) {
            SHAKES.clear();
            return;
        }
        SHAKES.add(new Running(incoming, System.currentTimeMillis()));
    }

    /** Gives back the camera the player was playing with, and drops every running shake. */
    public static void clear() {
        view = CameraView.CLEARED;
        easeFrom = null;
        SHAKES.clear();
        if (chosen != null) {
            Minecraft.getInstance().options.setCameraType(chosen);
            chosen = null;
        }
    }

    /**
     * Moves a camera the game has finished setting up to where the server asked for it.
     *
     * Called once a frame from the mixin. With nothing set this only records where the camera ended
     * up, which is what an ease that starts on a later frame reads as its first placement.
     */
    public static void apply(Adjustable camera) {
        long now = System.currentTimeMillis();
        SHAKES.removeIf(running -> running.shake().finishedAt(elapsed(running.start(), now)));

        CameraPlacement natural = camera.mcaddon$placement();
        if (view.cleared() && SHAKES.isEmpty()) {
            last = natural;
            return;
        }

        CameraPlacement placed = eased(target(natural), now);
        for (Running running : SHAKES) {
            placed = running.shake().displace(placed, elapsed(running.start(), now));
        }

        last = placed;
        camera.mcaddon$placeAt(placed);
    }

    /** The view's own placement: whatever the preset gives, with the parts the command named on top. */
    private static CameraPlacement target(CameraPlacement natural) {
        if (view.cleared()) return natural;

        return new CameraPlacement(view.position() == null ? natural.position() : view.position(),
            view.xRot() == null ? natural.xRot() : view.xRot(),
            view.yRot() == null ? natural.yRot() : view.yRot(),
            view.preset() != CameraView.Preset.FIRST_PERSON);
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

    private static CameraType cameraTypeOf(CameraView.Preset preset) {
        return switch (preset) {
            case FIRST_PERSON -> CameraType.FIRST_PERSON;
            case THIRD_PERSON_FRONT -> CameraType.THIRD_PERSON_FRONT;
            case THIRD_PERSON, FREE -> CameraType.THIRD_PERSON_BACK;
        };
    }

    private static float elapsed(long start, long now) {
        return (now - start) / 1000f;
    }
}
