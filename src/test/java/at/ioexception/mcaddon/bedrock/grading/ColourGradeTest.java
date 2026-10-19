package at.ioexception.mcaddon.bedrock.grading;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A grade against numbers worked out by hand from Bedrock's `ColorPostProcessing` shader.
 *
 * The expected values come from a separate transcription of that shader's arithmetic with its tone
 * mapper off and the average luminance at 0.18: decode sRGB, take the luminance, apply the white
 * balance, then contrast about 0.18, saturation about the luminance, gain, offset scaled by 0.18 and
 * the gamma against 2.2, each picked from the shadows, the midtones or the highlights by the
 * pixel's luminance. The three pixels fall in the shadows' blend toward the midtones, in the
 * highlights' blend and past the highlights' edge.
 */
class ColourGradeTest {
    private static final float TOLERANCE = 2e-4f;

    private static final String GRADE = """
        {
          "description": {"identifier": "tp:test"},
          "color_grading": {
            "midtones": {
              "contrast": [1.3, 1.1, 0.9],
              "saturation": [0.6, 0.6, 0.6],
              "gain": [1.1, 0.9, 1.0],
              "offset": [0.02, 0.0, -0.01],
              "gamma": [2.4, 2.2, 2.0]
            },
            "shadows": {"enabled": true, "shadowsMax": 0.5, "gain": [0.8, 0.8, 1.2]},
            "highlights": {"enabled": true, "highlightsMin": 2.0, "saturation": [1.5, 1.5, 1.5]},
            "temperature": {"enabled": true, "temperature": 4000, "type": "color_temperature"}
          },
          "tone_mapping": {"operator": "generic"}
        }
        """;

    private static ColourGrade grade() {
        return ColourGrade.tryFrom((JsonObject) Json.parse(GRADE));
    }

    @Test
    void aColourTemperatureBelowDaylightWarmsTheFrame() {
        float[][] matrix = grade().temperatureMatrix();

        assertArrayEquals(new float[] {1.14000f, 0.26133f, 0.01330f}, matrix[0], TOLERANCE);
        assertArrayEquals(new float[] {-0.01322f, 0.93742f, -0.00101f}, matrix[1], TOLERANCE);
        assertArrayEquals(new float[] {-0.01167f, -0.05269f, 0.59874f}, matrix[2], TOLERANCE);
    }

    @Test
    void eachPixelTakesTheBandItsLuminanceFallsIn() {
        GradeUniforms uniforms = grade().uniforms();

        assertArrayEquals(new float[] {0.13741f, 0.17056f, 0.24878f}, uniforms.grade(0.1f, 0.2f, 0.3f), TOLERANCE);
        assertArrayEquals(new float[] {0.56715f, 0.36478f, 0.27562f}, uniforms.grade(0.5f, 0.4f, 0.3f), TOLERANCE);
        assertArrayEquals(new float[] {1.00000f, 0.79871f, 0.37936f}, uniforms.grade(0.9f, 0.85f, 0.8f), TOLERANCE);
    }

    @Test
    void aFileStatingNothingLeavesTheFrameAlone() {
        GradeUniforms nothing = ColourGrade.tryFrom((JsonObject) Json.parse("{}")).uniforms();

        assertTrue(nothing.leavesFrameAlone());
        assertArrayEquals(new float[] {0.3f, 0.6f, 0.9f}, nothing.grade(0.3f, 0.6f, 0.9f), TOLERANCE);
        assertFalse(grade().uniforms().leavesFrameAlone());
    }

    @Test
    void halfWayBetweenTwoBiomesIsHalfOfEachGrade() {
        GradeUniforms.Blend blend = new GradeUniforms.Blend();
        blend.add(grade().uniforms(), 1);
        blend.add(GradeUniforms.NONE, 1);
        GradeUniforms halfway = blend.result();

        assertEquals(1.15f, halfway.value(GradeUniforms.CONTRAST + 1, 0), TOLERANCE);
        assertEquals(0.8f, halfway.value(GradeUniforms.SATURATION + 1, 2), TOLERANCE);
        assertEquals(0.5f, halfway.value(GradeUniforms.GAIN, 3), TOLERANCE);
    }
}
