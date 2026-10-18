package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a camera spline puts a free camera, and how a pack's spline file is read. */
class CameraSplineTest {
    private static final List<Vec3> SQUARE = List.of(
        new Vec3(0, 0, 0), new Vec3(10, 0, 0), new Vec3(10, 0, 10), new Vec3(0, 0, 10));
    private static final CameraPlacement BEFORE = new CameraPlacement(Vec3.ZERO, 5f, 15f, true);

    /** A Catmull-Rom curve passes through every control point, the first and the last included. */
    @Test
    void aCatmullRomCurvePassesThroughEveryPoint() {
        CameraSpline curve = new CameraSpline(true, SQUARE, 3f, List.of(), List.of());

        for (int i = 0; i < SQUARE.size(); i++) {
            Vec3 at = curve.placementAt(i, BEFORE).position();
            assertEquals(0, at.distanceTo(SQUARE.get(i)), 1e-9, "point " + i);
        }
        assertTrue(curve.placementAt(0.5f, BEFORE).position().z < 0, "the first stretch bends out as it leaves");
    }

    /** A linear one runs straight, and with no key frames moves evenly and keeps the camera's own turn. */
    @Test
    void aLinearCurveRunsStraightAndEvenly() {
        CameraSpline line = new CameraSpline(false, SQUARE, 3f, List.of(), List.of());
        CameraPlacement halfway = line.placementAt(0.5f, BEFORE);

        assertEquals(new Vec3(5, 0, 0), halfway.position());
        assertEquals(5f, halfway.xRot());
        assertEquals(15f, halfway.yRot());
        assertEquals(SQUARE.get(3), line.placementAt(9f, BEFORE).position());
    }

    /**
     * Between two key frames the value moves by the later frame's easing, before the first it is the
     * first, and after the last it is the last.
     */
    @Test
    void keyFramesEaseByTheFrameTheyMoveTo() {
        CameraSpline line = new CameraSpline(false, List.of(Vec3.ZERO, new Vec3(100, 0, 0)), 4f,
            List.of(key(1f, 0, Easing.LINEAR), key(3f, 1, Easing.tryFrom("in_quad"))),
            List.of(new CameraSpline.Key(0f, new Vec3(0, 0, 0), Easing.LINEAR),
                new CameraSpline.Key(2f, new Vec3(20, 90, 0), Easing.LINEAR)));

        assertEquals(0, line.placementAt(0.5f, BEFORE).position().x, 1e-9);
        assertEquals(25, line.placementAt(2f, BEFORE).position().x, 1e-4);
        assertEquals(100, line.placementAt(4f, BEFORE).position().x, 1e-9);
        assertEquals(45f, line.placementAt(1f, BEFORE).yRot(), 1e-4f);
        assertEquals(20f, line.placementAt(3f, BEFORE).xRot(), 1e-4f);
    }

    /** A spline file holds its definition under one key, named by its own `name` and spelt as Bedrock spells it. */
    @Test
    void readsASplineFile() {
        var file = Json.parse("{\"format_version\": \"1.26.0\", \"minecraft:camera_spline\": {\"name\": \"tp:sweep\","
            + " \"spline_type\": \"catmull_rom\", \"total_time\": 2, \"control_points\": [{\"position\": [1, 2, 3]},"
            + " [4, 5, 6]], \"progress_key_frames\": [{\"progress\": 1, \"time\": 2, \"easing\": \"InOutSine\"}],"
            + " \"rotation_key_frames\": [{\"rotation\": [10, 20, 30], \"time\": 1}]}}").getAsJsonObject();
        CameraSpline spline = CameraSpline.fromFile(file);

        assertNotNull(spline);
        assertEquals("tp:sweep", CameraSpline.nameIn(file));
        assertTrue(spline.catmullRom());
        assertEquals(List.of(new Vec3(1, 2, 3), new Vec3(4, 5, 6)), spline.points());
        assertEquals(Easing.tryFrom("in_out_sine"), spline.progress().get(0).easing());
        assertTrue(spline.rolls());
    }

    private static CameraSpline.Key key(float seconds, double progress, Easing easing) {
        return new CameraSpline.Key(seconds, new Vec3(progress, 0, 0), easing);
    }
}
