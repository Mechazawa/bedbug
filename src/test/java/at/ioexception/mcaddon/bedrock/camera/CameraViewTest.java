package at.ioexception.mcaddon.bedrock.camera;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What `/camera` sends one player.
 *
 * The server decides and each client is told separately, so everything the command read has to reach
 * the other side intact: a part that goes missing on the wire is a camera in the wrong place with
 * nothing logged.
 */
class CameraViewTest {
    /**
     * A point is faced from the view's own position, by the angles `/teleport ... facing` would turn a
     * player by: due south is a yaw of 0, east -90, and a point below is a positive pitch.
     */
    @ParameterizedTest
    @CsvSource({
        "0, 0, 10, 0, 0",
        "10, 0, 0, -90, 0",
        "0, -10, 10, 0, 45",
        "-10, 10, 0, 90, -45",
    })
    void turnsAFacedPointIntoARotation(double x, double y, double z, float yaw, float pitch) {
        CameraView faced = new CameraView(CameraView.Preset.FREE, new Vec3(5, 70, 5), null, null,
            new Vec3(5 + x, 70 + y, 5 + z), 0f, null, false).facingFrom(Vec3.ZERO);

        assertEquals(yaw, faced.yRot(), 1e-4f);
        assertEquals(pitch, faced.xRot(), 1e-4f);
        assertNull(faced.facing());
    }

    /** With no position of its own the camera faces the point from wherever it stands. */
    @Test
    void facesFromTheCameraWhenNoPositionWasGiven() {
        CameraView faced = new CameraView(CameraView.Preset.FIRST_PERSON, null, null, null,
            new Vec3(0, 0, -10), 0f, null, false).facingFrom(Vec3.ZERO);

        assertEquals(180f, Math.abs(faced.yRot()), 1e-4f);
        assertNull(faced.position());
    }

    @Test
    void survivesTheWire() {
        CameraView sent = new CameraView(CameraView.Preset.FREE, new Vec3(1.5, 2.5, -3.5), 12f,
            -45f, new Vec3(4, 5, 6), 2.5f, Easing.tryFrom("in_out_cubic"), true);

        assertEquals(sent, CameraView.decode(sent.encode()));
    }

    /** Everything after the preset may be left out, and the parts left out stay left out. */
    @Test
    void carriesOnlyTheOrdersThatWereGiven() {
        CameraView sent = new CameraView(CameraView.Preset.THIRD_PERSON, null, null, null, null, 0f, null, false);
        CameraView arrived = CameraView.decode(sent.encode());

        assertEquals(sent, arrived);
        assertNull(arrived.position());
        assertNull(arrived.easing());
    }

    @Test
    void aClearedViewArrivesCleared() {
        assertTrue(CameraView.decode(CameraView.CLEARED.encode()).cleared());
        assertTrue(CameraView.decode("not a view").cleared());
        assertTrue(CameraView.decode("{\"preset\":\"follow_orbit\"}").cleared());
    }
}
