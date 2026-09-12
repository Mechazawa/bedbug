package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a particle is drawn as: the quad, the patch of texture it wears, and the colour on top.
 *
 * The tint is the awkward one. A pack writes a colour as four Molang values between 0 and 1, as a
 * hexadecimal string counted in 255ths, or as a gradient, and a gradient is either a map of
 * positions to colours or a plain list whose colours are spread evenly across the range. All of
 * them are read into the same 0 to 1 channels, so a renderer sees one shape rather than four.
 */
class ParticleAppearanceTest {
    private final MolangEnvironment environment = new MolangEnvironment();

    @TempDir
    Path directory;

    @Test
    void readsTheQuadAndThePatchOfTextureItWears() throws IOException {
        Billboard billboard = effect("""
            {"minecraft:particle_appearance_billboard": {
              "size": [0.5, "0.25 * 2"],
              "facing_camera_mode": "lookat_y",
              "uv": {"texture_width": 128, "texture_height": 64, "uv": [0, 16], "uv_size": [8, 8]}
            }}
            """).particle().billboard();

        assertEquals(0.5f, value(billboard.size().x()), 1e-5);
        assertEquals(0.5f, value(billboard.size().y()), 1e-5);
        assertEquals(FacingCameraMode.LOOKAT_Y, billboard.facing());
        assertEquals(128, billboard.uv().textureWidth());
        assertEquals(64, billboard.uv().textureHeight());
        assertEquals(16, value(billboard.uv().uv().y()));
        assertEquals(8, value(billboard.uv().uvSize().x()));
        assertNull(billboard.uv().flipbook());
    }

    @ParameterizedTest
    @CsvSource({
        "rotate_xyz, ROTATE_XYZ",
        "emitter_transform_xz, EMITTER_TRANSFORM_XZ",
        "direction_y, DIRECTION_Y",
        "sideways, ROTATE_XYZ",
    })
    void readsTheFacingModeAndFallsBackToTheOneBedrockUses(String written, FacingCameraMode expected)
        throws IOException {
        Billboard billboard = effect("""
            {"minecraft:particle_appearance_billboard": {"facing_camera_mode": "%s"}}
            """.formatted(written)).particle().billboard();

        assertEquals(expected, billboard.facing());
    }

    @Test
    void readsAFlipbook() throws IOException {
        Billboard.Flipbook flipbook = effect("""
            {"minecraft:particle_appearance_billboard": {
              "uv": {
                "texture_width": 32, "texture_height": 32,
                "flipbook": {
                  "base_UV": [0, 0], "size_UV": [8, 8], "step_UV": [8, 0],
                  "frames_per_second": 10, "max_frame": "2 + 2",
                  "stretch_to_lifetime": true, "loop": false
                }
              }
            }}
            """).particle().billboard().uv().flipbook();

        assertEquals(8, value(flipbook.sizeUv().x()));
        assertEquals(8, value(flipbook.stepUv().x()));
        assertEquals(0, value(flipbook.stepUv().y()));
        assertEquals(10, value(flipbook.framesPerSecond()));
        assertEquals(4, value(flipbook.maxFrame()));
        assertTrue(flipbook.stretchToLifetime());
        assertFalse(flipbook.loop());
    }

    @Test
    void readsAColourWrittenAsFourMolangValues() throws IOException {
        ParticleTint.Solid solid = (ParticleTint.Solid) tint("[1, \"0.25 * 2\", 0, 0.5]");

        assertColour(1, 0.5f, 0, 0.5f, solid);
    }

    /** A colour written without an alpha is drawn at full, which is the value the array form omits. */
    @Test
    void readsAColourWrittenAsThreeValues() throws IOException {
        assertColour(1, 0, 0, 1, (ParticleTint.Solid) tint("[1, 0, 0]"));
    }

    /** A hexadecimal colour counts each channel in 255ths, and the array form counts in ones. */
    @Test
    void readsAColourWrittenAsHexadecimal() throws IOException {
        assertColour(1, 0, 1, 1, (ParticleTint.Solid) tint("\"#FF00FF\""));
    }

    /** Eight digits put the alpha in front of the colour. */
    @Test
    void readsTheAlphaOfAnEightDigitHexadecimalColour() throws IOException {
        assertColour(0, 1, 0, 0.5019608f, (ParticleTint.Solid) tint("\"#8000FF00\""));
    }

    @Test
    void hasNoTintForAColourThatIsNotOneOfTheThreeForms() throws IOException {
        assertNull(tint("\"sparkly\""));
        assertNull(tint("[1, 0]"));
        assertNull(effect("{\"minecraft:particle_appearance_tinting\": {}}").particle().tint());
    }

    /** A pack may write its stops in any order, and an unsorted gradient interpolates backwards. */
    @Test
    void readsAGradientWrittenAsPositionsAndSortsIt() throws IOException {
        ParticleTint.Gradient gradient = (ParticleTint.Gradient) tint("""
            {"gradient": {"1.0": "#FF0000", "0.0": [0, 0, 1, 1]}, "interpolant": "0.5"}
            """);

        assertEquals(List.of(0f, 1f), gradient.stops().stream().map(ParticleTint.Gradient.Stop::position).toList());
        assertColour(0, 0, 1, 1, gradient.stops().get(0).colour());
        assertColour(1, 0, 0, 1, gradient.stops().get(1).colour());
        assertEquals(0.5f, value(gradient.interpolant()), 1e-5);
    }

    /** A gradient written as a plain list spreads its colours evenly across the whole range. */
    @Test
    void spreadsAGradientWrittenAsAList() throws IOException {
        ParticleTint.Gradient gradient = (ParticleTint.Gradient) tint("""
            {"gradient": ["#FF0000", "#00FF00", "#0000FF"], "interpolant": "v.particle_age"}
            """);

        assertEquals(List.of(0f, 0.5f, 1f),
            gradient.stops().stream().map(ParticleTint.Gradient.Stop::position).toList());
        assertColour(0, 1, 0, 1, gradient.stops().get(1).colour());
    }

    @Test
    void putsTheOneColourOfASingleStopGradientAtTheStart() throws IOException {
        ParticleTint.Gradient gradient = (ParticleTint.Gradient) tint("{\"gradient\": [\"#FFFFFF\"]}");

        assertEquals(1, gradient.stops().size());
        assertEquals(0f, gradient.stops().get(0).position());
    }

    private ParticleTint tint(String colour) throws IOException {
        return effect("""
            {"minecraft:particle_appearance_tinting": {"color": %s}}
            """.formatted(colour)).particle().tint();
    }

    private ParticleDefinition effect(String components) throws IOException {
        Path file = directory.resolve("particles/sparkle.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {
              "format_version": "1.10.0",
              "particle_effect": {
                "description": {"identifier": "vn:sparkle"},
                "components": %s
              }
            }
            """.formatted(components));
        return ParticleDefinition.tryFrom(file);
    }

    private void assertColour(float red, float green, float blue, float alpha, ParticleTint.Solid solid) {
        assertEquals(red, value(solid.red()), 1e-5);
        assertEquals(green, value(solid.green()), 1e-5);
        assertEquals(blue, value(solid.blue()), 1e-5);
        assertEquals(alpha, value(solid.alpha()), 1e-5);
    }

    private float value(MolangExpression expression) {
        return expression.asFloat(environment);
    }
}
