package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The labPBR channels a Bedrock texture set is rewritten into, and where its layers are looked up.
 *
 * Bedrock and labPBR store the same four surface properties, and none of the four is a straight
 * copy. labPBR wants perceptual smoothness where Bedrock stores roughness, reads green above 229 as
 * "this is metal" rather than as a reflectance, reserves the bottom of the blue channel so
 * subsurface scattering starts above 64, and reads a full alpha as "not emissive" instead of as the
 * brightest possible glow.
 */
class TextureSetTest {
    private static final String COLOUR = "textures/blocks/stone";
    private static final String COMBINED = "textures/blocks/stone_mer";

    @TempDir
    Path directory;

    /**
     * Each case is one pixel of the metalness, emissive, roughness and subsurface map, written as
     * alpha, red, green, blue, against the sidecar pixel it has to produce.
     */
    @ParameterizedTest
    @CsvSource({
        "00000000, FFFF0A00",
        "00FF00FF, FF00FF00",
        "007F0000, FFFF7200",
        "00800000, FFFFFF00",
        "0000FF00, FEFF0A00",
        "01000000, FFFF0A41",
        "8064C840, C87F59A0",
    })
    void encodesTheSpecularSidecar(String map, String expected) throws IOException {
        texture(COLOUR, BufferedImage.TYPE_INT_RGB, 0xFF808080);
        texture(COMBINED, BufferedImage.TYPE_INT_ARGB, hex(map));

        List<TextureSet.Sidecar> sidecars =
            TextureSet.shaderSidecars(new TextureSet(COLOUR, null, null, COMBINED).layers(pack()));

        assertEquals(1, sidecars.size());
        assertEquals("_s", sidecars.get(0).suffix());
        assertEquals(hex(expected), sidecars.get(0).image().getRGB(0, 0));
    }

    /** A map shipped without an alpha channel says nothing about subsurface scattering. */
    @Test
    void readsNoSubsurfaceFromAMapWithNoAlphaChannel() throws IOException {
        texture(COLOUR, BufferedImage.TYPE_INT_RGB, 0xFF808080);
        texture(COMBINED, BufferedImage.TYPE_INT_RGB, 0xFF000000);

        List<TextureSet.Sidecar> sidecars =
            TextureSet.shaderSidecars(new TextureSet(COLOUR, null, null, COMBINED).layers(pack()));

        assertEquals(0xFFFF0A00, sidecars.get(0).image().getRGB(0, 0));
    }

    @Test
    void writesANormalSidecarOnlyWhenThereIsANormalOrHeightLayer() throws IOException {
        texture(COLOUR, BufferedImage.TYPE_INT_RGB, 0xFF808080);
        texture(COMBINED, BufferedImage.TYPE_INT_ARGB, 0x00000000);
        texture("textures/blocks/stone_normal", BufferedImage.TYPE_INT_RGB, 0xFF8080FF);

        assertEquals(List.of("_s"),
            suffixes(new TextureSet(COLOUR, null, null, COMBINED)));
        assertEquals(List.of("_n", "_s"),
            suffixes(new TextureSet(COLOUR, "textures/blocks/stone_normal", null, COMBINED)));
    }

    @Test
    void listsEveryLayerThatIsNotTheColourOne() {
        assertEquals(List.of("normal", "height", "combined"),
            List.copyOf(new TextureSet("colour", "normal", "height", "combined").auxiliary()));
        assertEquals(List.of("combined"),
            List.copyOf(new TextureSet("colour", null, null, "combined").auxiliary()));
        assertEquals(List.of(), List.copyOf(new TextureSet("colour", null, null, null).auxiliary()));
    }

    /** A set names its layers by bare file name, which means relative to the set's own directory. */
    @Test
    void resolvesLayerNamesAgainstTheDirectoryTheSetSitsIn() throws IOException {
        Path file = write("textures/blocks/stone.texture_set.json", """
            {
              "minecraft:texture_set": {
                "color": "stone",
                "normal": "stone_normal",
                "metalness_emissive_roughness": "stone_mer"
              }
            }
            """);

        TextureSet set = TextureSet.tryFrom(pack(), file);

        assertEquals("textures/blocks/stone", set.colour());
        assertEquals("textures/blocks/stone_normal", set.normal());
        assertEquals("textures/blocks/stone_mer", set.combined());
        assertNull(set.heightmap());
    }

    /** Newer packs spell the combined layer with its fourth channel in the key. */
    @Test
    void readsEitherSpellingOfTheCombinedLayer() throws IOException {
        Path file = write("textures/blocks/stone.texture_set.json", """
            {
              "minecraft:texture_set": {
                "color": "stone",
                "metalness_emissive_roughness_subsurface": "stone_mers"
              }
            }
            """);

        assertEquals("textures/blocks/stone_mers", TextureSet.tryFrom(pack(), file).combined());
    }

    @Test
    void answersNullForAFileThatIsNotATextureSet() throws IOException {
        assertNull(TextureSet.tryFrom(pack(), write("textures/blocks/stone.json", "{\"other\": {}}")));
    }

    private List<String> suffixes(TextureSet set) {
        return TextureSet.shaderSidecars(set.layers(pack())).stream().map(TextureSet.Sidecar::suffix).toList();
    }

    private BedrockPack pack() {
        return new BedrockPack(directory, null, "test.mcaddon");
    }

    private void texture(String path, int type, int argb) throws IOException {
        BufferedImage image = new BufferedImage(1, 1, type);
        image.setRGB(0, 0, argb);

        Path file = directory.resolve(path + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    private Path write(String path, String body) throws IOException {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }

    private static int hex(String value) {
        return (int) Long.parseLong(value, 16);
    }
}
