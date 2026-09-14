package at.ioexception.mcaddon.bedrock.camera;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What `/camerashake` does to a frame's camera.
 *
 * The wobble is worked out from the time alone, so a shake sent to four players puts every one of
 * their cameras in the same place, and the answers below can be stated without running a game.
 */
class CameraShakeTest {
    private static final CameraPlacement STILL =
        new CameraPlacement(new Vec3(1, 2, 3), 10f, 20f, false);
    private static final float TOLERANCE = 1e-5f;

    @Test
    void aPositionalShakeMovesTheCameraAndLeavesItPointingWhereItWas() {
        CameraPlacement shaken = new CameraShake(1f, 2f, false).displace(STILL, 0.1f);

        assertNotEquals(STILL.position(), shaken.position());
        assertEquals(STILL.xRot(), shaken.xRot(), TOLERANCE);
        assertEquals(STILL.yRot(), shaken.yRot(), TOLERANCE);
    }

    @Test
    void aRotationalShakeTurnsTheCameraAndLeavesItWhereItWas() {
        CameraPlacement shaken = new CameraShake(1f, 2f, true).displace(STILL, 0.1f);

        assertEquals(STILL.position(), shaken.position());
        assertNotEquals(STILL.xRot(), shaken.xRot());
    }

    /** Twice the intensity is twice the displacement, which is what the command's number means. */
    @Test
    void scalesWithTheIntensityItWasGiven() {
        Vec3 once = new CameraShake(1f, 2f, false).displace(STILL, 0.3f).position();
        Vec3 twice = new CameraShake(2f, 2f, false).displace(STILL, 0.3f).position();

        assertEquals((once.x - STILL.position().x) * 2, twice.x - STILL.position().x, TOLERANCE);
        assertEquals((once.y - STILL.position().y) * 2, twice.y - STILL.position().y, TOLERANCE);
        assertEquals((once.z - STILL.position().z) * 2, twice.z - STILL.position().z, TOLERANCE);
    }

    /** Fading to nothing is what keeps the view from jumping on the frame a shake runs out. */
    @Test
    void leavesTheCameraAloneOnTheFrameItEnds() {
        assertEquals(STILL, new CameraShake(4f, 2f, false).displace(STILL, 2f));
    }

    @Test
    void saysWhenItIsOver() {
        CameraShake shake = new CameraShake(1f, 2f, false);

        assertFalse(shake.finishedAt(1.99f));
        assertTrue(shake.finishedAt(2f));
    }

    /** `/camerashake stop` reaches the client as a shake with nothing left to run. */
    @Test
    void aStopRunsNothing() {
        assertTrue(CameraShake.STOP.stops());
        assertFalse(new CameraShake(1f, 2f, false).stops());
        assertEquals(STILL, CameraShake.STOP.displace(STILL, 0f));
    }

    @Test
    void survivesTheWire() {
        CameraShake sent = new CameraShake(2.5f, 1.5f, true);
        assertEquals(sent, CameraShake.decode(sent.encode()));
        assertEquals(CameraShake.STOP, CameraShake.decode(CameraShake.STOP.encode()));
    }

    @Test
    void readsNothingSensibleAsAStop() {
        assertEquals(CameraShake.STOP, CameraShake.decode("not a shake"));
    }
}
