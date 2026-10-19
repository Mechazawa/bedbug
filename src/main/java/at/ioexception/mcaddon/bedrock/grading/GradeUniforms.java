package at.ioexception.mcaddon.bedrock.grading;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * One grade as the block of uniforms the grading pass reads, and the same arithmetic the pass runs.
 *
 * The block is nineteen four-component rows in the std140 layout: contrast, saturation, gain, offset
 * and gamma for shadows, midtones and highlights in that order, each row's fourth component saying
 * whether its band is on, then the three columns of the white balance matrix, then the contrast pivot
 * as a factor of the average luminance, the highlights minimum, the shadows maximum and the average
 * luminance itself. Two grades blend by blending their rows, which is how a frame between two biomes
 * gets a grade between theirs.
 *
 * {@link #grade} is Bedrock's `ColorPostProcessing` fragment shader with its tone mapper switched off,
 * the branch that shader takes when `TonemapParams0.y` is below one half, and with its average
 * luminance held at the 0.18 it uses when exposure does not adapt. `colour_grade.fsh` is the same
 * arithmetic on the frame.
 */
public final class GradeUniforms {
    public static final int ROWS = 19;
    public static final int BYTES = ROWS * 4 * Float.BYTES;

    public static final int CONTRAST = 0;
    public static final int SATURATION = 3;
    public static final int GAIN = 6;
    public static final int OFFSET = 9;
    public static final int GAMMA = 12;
    public static final int TEMPERATURE = 15;
    public static final int MISC = 18;

    /** The scene's average luminance, the value Bedrock's shader uses when exposure is fixed. */
    static final float AVERAGE_LUMINANCE = 0.18f;

    private static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};

    /** A grade that leaves every pixel as it was, which is what a biome no pack grades gets. */
    public static final GradeUniforms NONE = ColourGrade.NONE.uniforms();

    private final float[] values;

    GradeUniforms(float[] values) {
        this.values = values;
    }

    /**
     * Sums grades by weight, each weight a share of one. A blend of one grade alone is that grade,
     * because dividing the weighted sum back out does not give the same floats again.
     */
    public static final class Blend {
        private final float[] sum = new float[ROWS * 4];
        private float total;
        private GradeUniforms only;
        private boolean mixed;

        public void add(GradeUniforms grade, float weight) {
            for (int i = 0; i < sum.length; i++) {
                sum[i] += grade.values[i] * weight;
            }
            total += weight;
            mixed |= only != null && only != grade;
            only = grade;
        }

        public GradeUniforms result() {
            if (total <= 0) return NONE;
            if (!mixed) return only;

            float[] values = new float[sum.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = sum[i] / total;
            }
            return new GradeUniforms(values);
        }
    }

    public boolean leavesFrameAlone() {
        return Arrays.equals(values, NONE.values);
    }

    /** The block in std140 order, little endian as the GPU reads it. */
    public ByteBuffer write(ByteBuffer into) {
        into.order(ByteOrder.nativeOrder());
        for (float value : values) {
            into.putFloat(value);
        }
        return into.flip();
    }

    public float value(int row, int component) {
        return values[row * 4 + component];
    }

    /**
     * What the grading pass makes of one pixel of the finished frame, each channel from 0 to 1 as
     * the frame stores it.
     */
    public float[] grade(float red, float green, float blue) {
        float[] linear = {decode(red), decode(green), decode(blue)};
        float average = value(MISC, 3);
        float luminance = luminance(linear);

        float[] colour = new float[3];
        for (int channel = 0; channel < 3; channel++) {
            colour[channel] = value(TEMPERATURE, channel) * linear[0]
                + value(TEMPERATURE + 1, channel) * linear[1]
                + value(TEMPERATURE + 2, channel) * linear[2];
        }

        float pivot = value(MISC, 0) * average;
        float[] contrast = band(CONTRAST, luminance, average);
        for (int channel = 0; channel < 3; channel++) {
            colour[channel] = Math.max(pivot
                * (float) Math.pow(Math.max(colour[channel], 0) / pivot, contrast[channel]), 0);
        }

        float[] saturation = band(SATURATION, luminance, average);
        float grey = luminance(colour);
        for (int channel = 0; channel < 3; channel++) {
            colour[channel] = Math.max(grey + (colour[channel] - grey) * saturation[channel], 0);
        }

        float[] gain = band(GAIN, luminance, average);
        float[] offset = band(OFFSET, luminance, average);
        for (int channel = 0; channel < 3; channel++) {
            colour[channel] = Math.max(Math.max(colour[channel] * gain[channel], 0)
                + average * offset[channel], 0);
        }

        float[] gamma = band(GAMMA, luminance(colour), AVERAGE_LUMINANCE);
        for (int channel = 0; channel < 3; channel++) {
            colour[channel] = Math.min(Math.max(
                (float) Math.pow(encode(colour[channel]), 2.2f / gamma[channel]), 0f), 1f);
        }
        return colour;
    }

    /**
     * The value of one setting for a pixel of this luminance.
     *
     * A pixel at or past a band's edge takes that band's value, and one between an edge and the
     * average takes a mix of that band and the midtones by how far across it stands.
     */
    private float[] band(int setting, float luminance, float average) {
        boolean shadows = value(setting, 3) != 0;
        boolean highlights = value(setting + 2, 3) != 0;
        float shadowsEdge = average * value(MISC, 2);
        float highlightsEdge = average * value(MISC, 1);

        if (highlights && luminance >= highlightsEdge) return rgb(setting + 2);
        if (shadows && luminance <= shadowsEdge) return rgb(setting);
        if (shadows && luminance < average) {
            return mix(rgb(setting), rgb(setting + 1), (luminance - shadowsEdge) / (average - shadowsEdge));
        }
        if (highlights && luminance > average) {
            return mix(rgb(setting + 1), rgb(setting + 2), (luminance - average) / (highlightsEdge - average));
        }
        return rgb(setting + 1);
    }

    private float[] rgb(int row) {
        return new float[] {value(row, 0), value(row, 1), value(row, 2)};
    }

    private static float[] mix(float[] from, float[] to, float share) {
        return new float[] {from[0] + (to[0] - from[0]) * share, from[1] + (to[1] - from[1]) * share,
            from[2] + (to[2] - from[2]) * share};
    }

    private static float luminance(float[] colour) {
        return colour[0] * LUMA[0] + colour[1] * LUMA[1] + colour[2] * LUMA[2];
    }

    /** sRGB to linear light, the inverse of {@link #encode}. */
    private static float decode(float value) {
        return value <= 0.04045f ? value / 12.92f
            : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    /** Linear light to sRGB, as Bedrock's shader writes it. */
    private static float encode(float value) {
        return value <= 0.0031308f ? value * 12.92f
            : (float) (Math.pow(Math.abs(value), 1 / 2.4f) * 1.055f - 0.055f);
    }
}
