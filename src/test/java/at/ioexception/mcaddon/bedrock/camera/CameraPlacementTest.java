package at.ioexception.mcaddon.bedrock.camera;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an eased `/camera` passes through on its way from one view to the next. */
class CameraPlacementTest {
    private static final float TOLERANCE = 1e-4f;

    @Test
    void meetsInTheMiddle() {
        CameraPlacement from = new CameraPlacement(new Vec3(0, 0, 0), 0f, 0f, false);
        CameraPlacement to = new CameraPlacement(new Vec3(10, 20, 30), 40f, 60f, false);

        CameraPlacement half = from.blend(to, 0.5f);
        assertEquals(new Vec3(5, 10, 15), half.position());
        assertEquals(20f, half.xRot(), TOLERANCE);
        assertEquals(30f, half.yRot(), TOLERANCE);
    }

    /** A turn from 350 to 10 is twenty degrees, so halfway is north and not the far side of south. */
    @Test
    void turnsTheShortWayRound() {
        CameraPlacement from = new CameraPlacement(Vec3.ZERO, 0f, 350f, false);
        CameraPlacement to = new CameraPlacement(Vec3.ZERO, 0f, 10f, false);

        assertEquals(0f, Mth.wrapDegrees(from.blend(to, 0.5f).yRot()), TOLERANCE);
    }

    /** The camera is away from the eyes for every frame of a move that starts or ends away. */
    @Test
    void countsAsDetachedWhileEitherEndIs() {
        CameraPlacement attached = new CameraPlacement(Vec3.ZERO, 0f, 0f, false);
        CameraPlacement away = new CameraPlacement(Vec3.ZERO, 0f, 0f, true);

        assertTrue(attached.blend(away, 0.1f).detached());
        assertTrue(away.blend(attached, 0.9f).detached());
    }
}
