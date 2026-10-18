package at.ioexception.mcaddon.bedrock.camera;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a free camera turns after its target, and where an orbit camera stands, with the camera at the
 * origin looking south and the target ten blocks east, which is a yaw of -90.
 */
class CameraTrackingTest {
    private static final CameraPlacement SOUTH = new CameraPlacement(Vec3.ZERO, 0f, 0f, true);
    private static final Vec3 EAST = new Vec3(10, 0, 0);

    @Test
    void followsATargetAtOnceByDefault() {
        CameraPlacement turned = CameraTracking.DEFAULT.follow(SOUTH, SOUTH, EAST, 0.05f, false);
        assertEquals(-90f, turned.yRot(), 1e-4f);
        assertEquals(0f, turned.xRot(), 1e-4f);
    }

    /** Beyond its limits a camera looks back the way it started, or stops at the edge if it keeps targeting. */
    @Test
    void holdsToItsLimits() {
        CameraTracking limited = new CameraTracking(0f, false, 10f, 10f, 80f, 100f, false, 50f);
        assertEquals(0f, limited.follow(SOUTH, SOUTH, EAST, 0.05f, false).yRot(), 1e-4f);

        CameraTracking continuing = new CameraTracking(0f, false, 10f, 10f, 80f, 100f, true, 50f);
        CameraPlacement atEdge = continuing.follow(SOUTH, SOUTH, new Vec3(10, 20, 0), 0.05f, false);
        assertEquals(-10f, atEdge.yRot(), 1e-4f);
        assertEquals(-10f, atEdge.xRot(), 1e-4f);
    }

    @Test
    void looksBackOnceTheTargetIsOutOfReach() {
        CameraTracking near = new CameraTracking(0f, false, 0f, 360f, 0f, 180f, false, 5f);
        assertEquals(0f, near.follow(SOUTH, SOUTH, EAST, 0.05f, false).yRot(), 1e-4f);
        assertEquals(-90f, near.follow(SOUTH, SOUTH, new Vec3(3, 0, 0), 0.05f, false).yRot(), 1e-4f);
    }

    /** A rotation speed turns the camera that many degrees a second, except on the first frame of a snap. */
    @Test
    void turnsAtItsSpeed() {
        CameraTracking slow = new CameraTracking(30f, false, 0f, 360f, 0f, 180f, false, 50f);
        assertEquals(-15f, slow.follow(SOUTH, SOUTH, EAST, 0.5f, true).yRot(), 1e-4f);

        CameraTracking snapping = new CameraTracking(30f, true, 0f, 360f, 0f, 180f, false, 50f);
        assertEquals(-90f, snapping.follow(SOUTH, SOUTH, EAST, 0.5f, true).yRot(), 1e-4f);
        assertEquals(-15f, snapping.follow(SOUTH, SOUTH, EAST, 0.5f, false).yRot(), 1e-4f);
    }

    /** An orbit stands its radius back along the view from the point it circles, then slides across its screen. */
    @Test
    void anOrbitStandsBackFromThePointItCircles() {
        CameraOrbit orbit = new CameraOrbit(6f, 1f, 2f, new Vec3(0, 1, 0), null, null, false);
        CameraPlacement placed = orbit.around(new Vec3(0, 10, 0), 0f, 0f);

        assertEquals(0, placed.position().distanceTo(new Vec3(-1, 13, -6)), 1e-6);
        assertEquals(0f, placed.yRot());
    }
}
