package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.model.PartVisibility;
import at.ioexception.mcaddon.bedrock.model.UvShift;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What light one drawing pass is handed.
 *
 * Java packs the two light channels into one integer as levels rather than as a colour, so the
 * multiplier a render controller asks for is arithmetic on that integer: both channels move and
 * neither goes past the brightest level there is.
 */
class PassLightingTest {
    /** Block light 8 and sky light 15, in the form Minecraft hands a renderer. */
    private static final int HALF_LIT = 0x00F00080;

    @Test
    void handsOnTheLightAroundTheEntityWhenNothingAsksOtherwise() {
        assertEquals(HALF_LIT, pass(false, 1f).light(HALF_LIT));
    }

    @Test
    void movesBothChannelsByTheMultiplier() {
        assertEquals(0x00780040, pass(false, 0.5f).light(HALF_LIT));
    }

    @Test
    void stopsAChannelAtTheBrightestLevelThereIs() {
        assertEquals(0x00F000E6, pass(false, 1.8f).light(HALF_LIT));
    }

    /** A controller may ask for both, which Bedrock's own breeze and powered blocks do. */
    @Test
    void dimsTheFullBrightnessAPassAlreadyIgnoringLightingDrawsAt() {
        assertEquals(0x00780078, pass(true, 0.5f).light(HALF_LIT));
    }

    private static AddonModels.Pass pass(boolean ignoresLighting, float multiplier) {
        return new AddonModels.Pass(null, null, PartVisibility.EVERYTHING, PartVisibility.EVERYTHING, UvShift.NONE,
            null, 0xFFFFFFFF, ignoresLighting, multiplier, true);
    }
}
