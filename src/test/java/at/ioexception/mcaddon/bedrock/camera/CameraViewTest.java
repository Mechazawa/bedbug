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
    @ParameterizedTest
    @CsvSource({
        "free, FREE",
        "minecraft:free, FREE",
        "MINECRAFT:Free, FREE",
        "third_person_front, THIRD_PERSON_FRONT",
        "first_person, FIRST_PERSON",
    })
    void readsAPresetWithOrWithoutItsNamespace(String written, CameraView.Preset expected) {
        assertEquals(expected, CameraView.Preset.tryFrom(written));
    }

    @Test
    void answersNothingForAPresetBedrockHasNotGot() {
        assertNull(CameraView.Preset.tryFrom("minecraft:follow_orbit"));
        assertNull(CameraView.Preset.tryFrom("free_camera"));
    }

    @Test
    void survivesTheWire() {
        CameraView sent = new CameraView(CameraView.Preset.FREE, new Vec3(1.5, 2.5, -3.5), 12f,
            -45f, 2.5f, Easing.tryFrom("in_out_cubic"));

        assertEquals(sent, CameraView.decode(sent.encode()));
    }

    /** Everything after the preset may be left out, and the parts left out stay left out. */
    @Test
    void carriesOnlyTheOrdersThatWereGiven() {
        CameraView sent = new CameraView(CameraView.Preset.THIRD_PERSON, null, null, null, 0f, null);
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
