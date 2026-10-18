package at.ioexception.mcaddon.bedrock.grading;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One `color_grading/` settings collection: how a biome grades the finished frame.
 *
 * Each of the three tonal bands states a contrast, saturation, gain, offset and gamma per channel;
 * the midtones apply everywhere and the shadows and highlights only once switched on, below
 * `shadowsMax` and above `highlightsMin` times the average luminance. The temperature is a white
 * balance from the 6500 K of daylight to a Planckian white, built here into the matrix Bedrock's
 * shader applies.
 *
 * Bedrock grades a frame of scene light and then tone maps it into range. Java's finished frame is
 * already in range, so the grade is applied to it the way Bedrock's shader does with its tone mapper
 * off, and the operator a file names is reported.
 *
 * @param toneMapping the operator the file names, or null
 */
public record ColourGrade(Band shadows, Band midtones, Band highlights, float shadowsMax,
    float highlightsMin, boolean temperatureOn, float kelvin, boolean whiteBalance, String toneMapping) {

    /**
     * Where the shadows end and the highlights begin when a file switches a band on and leaves its
     * edge out. Mojang publishes no default for either, so both are taken at the average luminance,
     * which the documentation describes as each band covering its whole side of the average.
     */
    static final float DEFAULT_EDGE = 1f;

    /**
     * The contrast pivot as a factor of the average luminance. Bedrock's shader reads it from a
     * constant no file states; contrast about the average itself is what leaves the average grey.
     */
    static final float CONTRAST_PIVOT = 1f;

    public static final ColourGrade NONE = new ColourGrade(Band.OFF, Band.IDENTITY, Band.OFF,
        DEFAULT_EDGE, DEFAULT_EDGE, false, 6500f, true, null);

    /** The file name Bedrock reserves for the collection every unnamed biome takes. */
    public static final String WHOLE_WORLD_FILE = "color_grading.json";

    /** One band's five settings, each a factor per channel. */
    public record Band(boolean on, float[] contrast, float[] saturation, float[] gain, float[] offset,
        float[] gamma) {
        static final Band IDENTITY = new Band(true, new float[] {1, 1, 1}, new float[] {1, 1, 1},
            new float[] {1, 1, 1}, new float[] {0, 0, 0}, new float[] {2.2f, 2.2f, 2.2f});
        static final Band OFF = new Band(false, IDENTITY.contrast, IDENTITY.saturation, IDENTITY.gain,
            IDENTITY.offset, IDENTITY.gamma);

        static Band tryFrom(JsonObject body, boolean midtones) {
            if (body == null) return midtones ? IDENTITY : OFF;

            return new Band(midtones || Json.bool(body, "enabled", false),
                channels(body, "contrast", IDENTITY.contrast), channels(body, "saturation", IDENTITY.saturation),
                channels(body, "gain", IDENTITY.gain), channels(body, "offset", IDENTITY.offset),
                channels(body, "gamma", IDENTITY.gamma));
        }

        private static float[] channels(JsonObject body, String key, float[] fallback) {
            if (!body.has(key) || !body.get(key).isJsonArray()) return fallback;

            JsonArray values = body.getAsJsonArray(key);
            float[] read = fallback.clone();
            for (int i = 0; i < Math.min(3, values.size()); i++) {
                read[i] = values.get(i).getAsFloat();
            }
            return read;
        }
    }

    /** Every collection the resource pack ships, by identifier. */
    public static Map<String, ColourGrade> readAll(BedrockPack pack) {
        Map<String, ColourGrade> found = new LinkedHashMap<>();
        for (Path file : pack.list("color_grading", ".json")) {
            try {
                JsonObject body = Json.parseObject(file).getAsJsonObject("minecraft:color_grading_settings");
                String identifier = identifier(body);
                if (identifier != null) found.put(identifier, tryFrom(body));
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return found;
    }

    /**
     * The identifier of the collection in the file Bedrock gives every biome that names none, or
     * null where the pack ships no such file.
     */
    public static String wholeWorld(BedrockPack pack) {
        if (!pack.has("color_grading/" + WHOLE_WORLD_FILE)) return null;

        try {
            return identifier(Json.parseObject(pack.file("color_grading/" + WHOLE_WORLD_FILE))
                .getAsJsonObject("minecraft:color_grading_settings"));
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static String identifier(JsonObject body) {
        JsonObject description = body == null ? null : body.getAsJsonObject("description");
        return description == null ? null : Json.text(description, "identifier", null);
    }

    static ColourGrade tryFrom(JsonObject body) {
        JsonObject grading = body.has("color_grading") && body.get("color_grading").isJsonObject()
            ? body.getAsJsonObject("color_grading") : new JsonObject();
        JsonObject shadows = grading.getAsJsonObject("shadows");
        JsonObject highlights = grading.getAsJsonObject("highlights");
        JsonObject temperature = grading.getAsJsonObject("temperature");
        JsonObject toneMapping = body.getAsJsonObject("tone_mapping");

        return new ColourGrade(Band.tryFrom(shadows, false), Band.tryFrom(grading.getAsJsonObject("midtones"), true),
            Band.tryFrom(highlights, false),
            shadows == null ? DEFAULT_EDGE : (float) Json.number(shadows, "shadowsMax", DEFAULT_EDGE),
            highlights == null ? DEFAULT_EDGE : (float) Json.number(highlights, "highlightsMin", DEFAULT_EDGE),
            temperature != null && Json.bool(temperature, "enabled", false),
            temperature == null ? 6500f : (float) Json.number(temperature, "temperature", 6500),
            temperature == null || !"color_temperature".equals(Json.text(temperature, "type", "white_balance")),
            toneMapping == null ? null : Json.text(toneMapping, "operator", null));
    }

    /** Names what a pack wrote that the grading pass does not apply. */
    public void report() {
        //? if <26 {
        /*AddonEntities.unsupported("color grading (needs 26 or newer)");
        *///?}
        if (toneMapping != null) {
            AddonEntities.unsupported("color grading tone_mapping " + toneMapping
                + ", which maps a frame of scene light into range where Java's finished frame is in range already");
        }
    }

    public GradeUniforms uniforms() {
        float[] values = new float[GradeUniforms.ROWS * 4];
        Band[] bands = {shadows, midtones, highlights};
        for (int band = 0; band < 3; band++) {
            put(values, GradeUniforms.CONTRAST + band, bands[band].contrast(), bands[band].on());
            put(values, GradeUniforms.SATURATION + band, bands[band].saturation(), bands[band].on());
            put(values, GradeUniforms.GAIN + band, bands[band].gain(), bands[band].on());
            put(values, GradeUniforms.OFFSET + band, bands[band].offset(), bands[band].on());
            put(values, GradeUniforms.GAMMA + band, bands[band].gamma(), bands[band].on());
        }

        float[][] matrix = temperatureMatrix();
        for (int column = 0; column < 3; column++) {
            put(values, GradeUniforms.TEMPERATURE + column,
                new float[] {matrix[0][column], matrix[1][column], matrix[2][column]}, false);
        }
        put(values, GradeUniforms.MISC, new float[] {CONTRAST_PIVOT, highlightsMin, shadowsMax}, false);
        values[GradeUniforms.MISC * 4 + 3] = GradeUniforms.AVERAGE_LUMINANCE;
        return new GradeUniforms(values);
    }

    private static void put(float[] values, int row, float[] rgb, boolean on) {
        System.arraycopy(rgb, 0, values, row * 4, 3);
        values[row * 4 + 3] = on ? 1 : 0;
    }

    /**
     * The white balance as one matrix over linear sRGB, rows by columns.
     *
     * Bedrock's shader takes the colour into a cone response through a matrix folding the sRGB
     * primaries into CAT02, scales each cone by the ratio between the Planckian white at this
     * temperature and the D65 white, and comes back through the inverse. The Planckian white is
     * Krystek's rational approximation in the CIE 1960 UCS.
     *
     * Which way round the ratio goes is read off Mojang's own files rather than its prose: the
     * documentation says `color_temperature` warms the image as the number rises, while the vanilla
     * pack grades its hot biomes at 4000 K and its cold ones at 10000 K under that type. So
     * `color_temperature` tints the frame the colour of a light at that temperature, and
     * `white_balance` corrects for such a light, the way a camera's setting does.
     */
    float[][] temperatureMatrix() {
        if (!temperatureOn) return new float[][] {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

        double[] daylight = cones(0.31272, 0.32903);
        double kelvinSquared = (double) kelvin * kelvin;
        double u = (0.860117757 + 1.54118254e-4 * kelvin + 1.28641212e-7 * kelvinSquared)
            / (1 + 8.42420235e-4 * kelvin + 7.08145163e-7 * kelvinSquared);
        double v = (0.317398726 + 4.22806245e-5 * kelvin + 4.20481691e-8 * kelvinSquared)
            / (1 - 2.89741816e-5 * kelvin + 1.61456053e-7 * kelvinSquared);
        double divisor = 2 * u - 8 * v + 4;
        double[] planckian = cones(3 * u / divisor, 2 * v / divisor);

        double[][] intoCones = {{0.390, 0.550, 0.009}, {0.071, 0.963, 0.001}, {0.023, 0.128, 0.936}};
        double[][] outOfCones = {{2.859, -1.629, -0.025}, {-0.210, 1.158, 0.0}, {-0.042, -0.118, 1.069}};
        float[][] matrix = new float[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                double sum = 0;
                for (int cone = 0; cone < 3; cone++) {
                    double scale = whiteBalance ? daylight[cone] / planckian[cone] : planckian[cone] / daylight[cone];
                    sum += outOfCones[row][cone] * scale * intoCones[cone][column];
                }
                matrix[row][column] = (float) sum;
            }
        }
        return matrix;
    }

    /** CAT02's cone response to the white of unit luminance at chromaticity x, y. */
    private static double[] cones(double x, double y) {
        double[] xyz = {x / y, 1, (1 - x - y) / y};
        double[][] cat02 = {{0.7328, 0.4296, -0.1624}, {-0.7036, 1.6975, 0.0061}, {0.0030, 0.0136, 0.9834}};
        double[] response = new double[3];
        for (int row = 0; row < 3; row++) {
            response[row] = cat02[row][0] * xyz[0] + cat02[row][1] * xyz[1] + cat02[row][2] * xyz[2];
        }
        return response;
    }
}
