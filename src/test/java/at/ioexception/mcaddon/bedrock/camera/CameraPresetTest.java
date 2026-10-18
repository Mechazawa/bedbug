package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a preset `/camera set` names resolves into: one of Bedrock's own, or a pack's preset written
 * over the one it inherits from.
 */
class CameraPresetTest {
    private static final CameraPreset FREE = CameraPreset.builtIn("minecraft:free");

    @ParameterizedTest
    @CsvSource({
        "free, FREE",
        "minecraft:free, FREE",
        "MINECRAFT:Free, FREE",
        "third_person_front, THIRD_PERSON_FRONT",
        "first_person, FIRST_PERSON",
        "fixed_boom, FIXED_BOOM",
        "minecraft:follow_orbit, FOLLOW_ORBIT",
        "control_scheme_camera, CONTROL_SCHEME",
    })
    void readsABuiltInPresetWithOrWithoutItsNamespace(String written, CameraView.Preset expected) {
        assertEquals(expected, CameraPreset.builtIn(written).camera());
    }

    @Test
    void answersNothingForANameThatIsNoBuiltInPreset() {
        assertNull(CameraPreset.builtIn("free_camera"));
        assertNull(CameraPreset.builtIn("tp:overlook"));
    }

    /**
     * Bedrock eases into a camera that stands still or circles an entity, and refuses an ease to one of
     * the three cameras tied to the player's own view.
     */
    @Test
    void refusesAnEaseToACameraThatFollowsThePlayersOwnView() {
        CameraRequest eased = new CameraRequest(null, null, null, null, 1f, Easing.LINEAR, null, null, null);
        assertEquals("Easing to non-stationary cameras is not supported, and minecraft:third_person follows the"
            + " player.", CameraPreset.builtIn("third_person").refusal(eased));
        assertNull(FREE.inheritedBy("tp:overlook", body("{}")).refusal(eased));
        assertNull(CameraPreset.builtIn("follow_orbit").refusal(eased));
    }

    /** A camera that follows an entity takes no place to stand, and one that does not takes no offset. */
    @Test
    void refusesWhatTheCameraHasNoUseFor() {
        CameraRequest placed = new CameraRequest(Vec3.ZERO, null, null, null, 0f, null, null, null, null);
        CameraRequest offset = new CameraRequest(null, null, null, null, 0f, null, 1f, 1f, null);
        CameraPreset orbit = CameraPreset.builtIn("follow_orbit").inheritedBy("tp:orbit", body("{\"radius\": 4}"));

        assertEquals("tp:orbit follows an entity, so it takes no position and no point to face.",
            orbit.refusal(placed));
        assertNull(orbit.refusal(offset));
        assertTrue(FREE.refusal(offset).contains("minecraft:free is not one of those"));
        assertNull(FREE.refusal(placed));
    }

    /** An orbit preset carries its radius, its offsets and its starting turn down the chain. */
    @Test
    void inheritsAnOrbit() {
        CameraPreset boom = CameraPreset.builtIn("fixed_boom").inheritedBy("tp:boom", body(
            "{\"radius\": 400, \"view_offset\": [1, 2], \"starting_rot_x\": 45}"))
            .inheritedBy("tp:aligned",
                body("{\"entity_offset\": [0, 1, 0], \"align_target_and_camera_forward\": true}"));

        assertEquals(CameraView.Preset.FIXED_BOOM, boom.camera());
        assertEquals(new CameraOrbit(100f, 1f, 2f, new Vec3(0, 1, 0), 45f, null, true), boom.orbit());
        assertEquals(new CameraOrbit(100f, 1f, 5f, new Vec3(0, 1, 0), 45f, null, true),
            boom.view(new CameraRequest(null, null, null, null, 0f, null, null, 5f, null)).orbit());
    }

    /** A free preset carries how it follows a target, and leaves what it does not state at Bedrock's defaults. */
    @Test
    void inheritsHowATargetIsFollowed() {
        CameraPreset watch = FREE.inheritedBy("tp:watch", body(
            "{\"rotation_speed\": 30, \"horizontal_rotation_limit\": [10, 20], \"continue_targeting\": true}"));

        assertEquals(new CameraTracking(30f, false, 10f, 20f, 0f, 180f, true, 50f), watch.tracking());
    }

    /**
     * Every field a preset leaves out is the one it inherits, and a position is kept as three numbers
     * together, so stating one of them puts the other two at `minecraft:free`'s zero.
     */
    @Test
    void inheritsWhatAPresetLeavesOut() {
        CameraPreset base = FREE.inheritedBy("tp:base",
            body("{\"pos_y\": 120, \"rot_x\": 20, \"listener\": \"player\"}"));
        CameraPreset east = base.inheritedBy("tp:east", body("{\"pos_x\": 4.5, \"rot_y\": -90}"));

        assertEquals(new Vec3(0, 120, 0), base.position());
        assertNull(base.yRot());
        assertEquals(new Vec3(4.5, 120, 0), east.position());
        assertEquals(20f, east.xRot());
        assertEquals(-90f, east.yRot());
        assertTrue(east.listensFromPlayer());
        assertEquals(CameraView.Preset.FREE, east.camera());
        assertEquals("tp:east", east.identifier());
    }

    /** A preset stating no position leaves the camera where Java's free camera would put it. */
    @Test
    void leavesThePlacementToTheCameraWhereNothingStatesIt() {
        CameraPreset turned = FREE.inheritedBy("tp:turned", body("{\"rot_y\": 45, \"listener\": \"camera\"}"));

        assertNull(turned.position());
        assertNull(turned.xRot());
        assertFalse(turned.listensFromPlayer());
    }

    /** What a command or a script states replaces the preset's own, and the rest is the preset's. */
    @Test
    void layersWhatWasStatedOverThePreset() {
        CameraPreset preset = FREE.inheritedBy("tp:p", body("{\"pos_x\": 1, \"pos_y\": 2, \"pos_z\": 3,"
            + " \"rot_x\": 10, \"rot_y\": 20, \"listener\": \"player\"}"));

        CameraView bare = preset.view(CameraRequest.NOTHING);
        assertEquals(new CameraView(CameraView.Preset.FREE, new Vec3(1, 2, 3), 10f, 20f, null, 0f, null, true,
            CameraOrbit.DEFAULT, CameraTracking.DEFAULT), bare);

        CameraView moved = preset.view(
            new CameraRequest(new Vec3(7, 8, 9), 30f, 40f, null, 1f, Easing.tryFrom("linear"), null, null, null));
        assertEquals(new Vec3(7, 8, 9), moved.position());
        assertEquals(30f, moved.xRot());
        assertEquals(40f, moved.yRot());
        assertTrue(moved.listensFromPlayer());
    }

    private static JsonObject body(String json) {
        return Json.parse(json).getAsJsonObject();
    }
}
