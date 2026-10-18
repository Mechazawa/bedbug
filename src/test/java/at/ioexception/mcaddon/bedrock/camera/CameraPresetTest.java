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
    })
    void readsABuiltInPresetWithOrWithoutItsNamespace(String written, CameraView.Preset expected) {
        assertEquals(expected, CameraPreset.builtIn(written).camera());
    }

    @Test
    void answersNothingForANameThatIsNoBuiltInPreset() {
        assertNull(CameraPreset.builtIn("free_camera"));
        assertNull(CameraPreset.builtIn("tp:overlook"));
    }

    /** A Bedrock camera Java cannot show is named as one, and so is a pack's preset built on one. */
    @Test
    void refusesABedrockCameraJavaCannotDraw() {
        CameraPreset boom = CameraPreset.builtIn("fixed_boom");
        assertEquals("minecraft:fixed_boom is a Bedrock camera that Java has no counterpart for, so it is not drawn.",
            boom.refusal(null));
        assertEquals("tp:orbit is built on minecraft:fixed_boom, a Bedrock camera that Java has no counterpart"
            + " for, so it is not drawn.", boom.inheritedBy("tp:orbit", body("{\"radius\": 4}")).refusal(null));
    }

    /** Bedrock eases only to a camera that stands still, which every preset a pack draws is. */
    @Test
    void refusesAnEaseToACameraThatFollowsThePlayer() {
        Easing linear = Easing.tryFrom("linear");
        assertEquals("Easing to non-stationary cameras is not supported, and minecraft:third_person follows the"
            + " player.", CameraPreset.builtIn("third_person").refusal(linear));
        assertNull(FREE.inheritedBy("tp:overlook", body("{}")).refusal(linear));
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

        CameraView bare = preset.view(null, null, null, null, 0f, null);
        assertEquals(new CameraView(CameraView.Preset.FREE, new Vec3(1, 2, 3), 10f, 20f, null, 0f, null, true),
            bare);

        CameraView moved = preset.view(new Vec3(7, 8, 9), 30f, 40f, null, 1f, Easing.tryFrom("linear"));
        assertEquals(new Vec3(7, 8, 9), moved.position());
        assertEquals(30f, moved.xRot());
        assertEquals(40f, moved.yRot());
        assertTrue(moved.listensFromPlayer());
    }

    private static JsonObject body(String json) {
        return Json.parse(json).getAsJsonObject();
    }
}
