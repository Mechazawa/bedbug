package at.ioexception.mcaddon.bedrock.camera;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

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
        CameraView faced = view(CameraView.Preset.FREE, new Vec3(5, 70, 5), new Vec3(5 + x, 70 + y, 5 + z))
            .facingFrom(Vec3.ZERO);

        assertEquals(yaw, faced.yRot(), 1e-4f);
        assertEquals(pitch, faced.xRot(), 1e-4f);
        assertNull(faced.facing());
    }

    /** With no position of its own the camera faces the point from wherever it stands. */
    @Test
    void facesFromTheCameraWhenNoPositionWasGiven() {
        CameraView faced = view(CameraView.Preset.FIRST_PERSON, null, new Vec3(0, 0, -10)).facingFrom(Vec3.ZERO);

        assertEquals(180f, Math.abs(faced.yRot()), 1e-4f);
        assertNull(faced.position());
    }

    @Test
    void survivesTheWire() {
        CameraView sent = new CameraView(CameraView.Preset.FOLLOW_ORBIT, new Vec3(1.5, 2.5, -3.5), 12f,
            -45f, new Vec3(4, 5, 6), 2.5f, Easing.tryFrom("in_out_cubic"), true,
            new CameraOrbit(6f, 1f, -2f, new Vec3(0, 1, 0), 10f, null, true),
            new CameraTracking(30f, true, 45f, 30f, 45f, 120f, true, 20f));

        assertEquals(sent, CameraInstruction.decode(sent.encode()));
    }

    /** Everything after the preset may be left out, and the parts left out stay left out. */
    @Test
    void carriesOnlyTheOrdersThatWereGiven() {
        CameraView sent = view(CameraView.Preset.THIRD_PERSON, null, null);
        CameraView arrived = (CameraView) CameraInstruction.decode(sent.encode());

        assertEquals(sent, arrived);
        assertNull(arrived.position());
        assertNull(arrived.easing());
    }

    @Test
    void aClearedViewArrivesCleared() {
        assertTrue(((CameraView) CameraInstruction.decode(CameraView.CLEARED.encode())).cleared());
        assertTrue(((CameraView) CameraInstruction.decode("not a view")).cleared());
        assertTrue(((CameraView) CameraInstruction.decode("{\"preset\":\"follow_orbit\"}")).cleared());
    }

    /** A target, an attachment and a spline each arrive as what was sent, and so does their removal. */
    @Test
    void everyOtherOrderSurvivesTheWire() {
        List<CameraInstruction> sent = List.of(
            new CameraTarget(42, new Vec3(0, 5, 0)), CameraTarget.REMOVED,
            new CameraAttachment(7, AttachPoint.WEAPON_ATTACH_POINT), new CameraAttachment(7, null),
            CameraAttachment.DETACHED,
            new CameraSpline(true, List.of(Vec3.ZERO, new Vec3(1, 2, 3)), 2f,
                List.of(new CameraSpline.Key(0f, Vec3.ZERO, Easing.LINEAR),
                    new CameraSpline.Key(2f, new Vec3(1, 0, 0), Easing.tryFrom("in_out_sine"))),
                List.of(new CameraSpline.Key(1f, new Vec3(30, 90, 0), Easing.LINEAR))));

        sent.forEach(instruction -> assertEquals(instruction, CameraInstruction.decode(instruction.encode())));
    }

    private static CameraView view(CameraView.Preset preset, Vec3 position, Vec3 facing) {
        return new CameraView(preset, position, null, null, facing, 0f, null, false, CameraOrbit.DEFAULT,
            CameraTracking.DEFAULT);
    }
}
