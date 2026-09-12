package at.ioexception.mcaddon.convert;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The pixels a Targa file decodes to, built from bytes rather than from a sample file.
 *
 * Two things about the format are easy to get backwards and invisible in a thumbnail: a pixel is
 * stored blue first, and the rows run bottom to top unless bit 5 of the image descriptor says
 * otherwise. Both are asserted here per pixel.
 */
class TgaReaderTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;

    /** Four pixels as blue, green, red triples: red, green, blue, white. */
    private static final int[] FOUR_PIXELS = {
        0x00, 0x00, 0xFF,
        0x00, 0xFF, 0x00,
        0xFF, 0x00, 0x00,
        0xFF, 0xFF, 0xFF,
    };

    @Test
    void readsUncompressedTrueColourBottomUp() throws IOException {
        BufferedImage image = TgaReader.read(targa(2, 2, 2, 24, 0x00, FOUR_PIXELS));

        assertEquals(RED, image.getRGB(0, 1));
        assertEquals(GREEN, image.getRGB(1, 1));
        assertEquals(BLUE, image.getRGB(0, 0));
        assertEquals(WHITE, image.getRGB(1, 0));
    }

    @Test
    void readsUncompressedTrueColourTopDown() throws IOException {
        BufferedImage image = TgaReader.read(targa(2, 2, 2, 24, 0x20, FOUR_PIXELS));

        assertEquals(RED, image.getRGB(0, 0));
        assertEquals(GREEN, image.getRGB(1, 0));
        assertEquals(BLUE, image.getRGB(0, 1));
        assertEquals(WHITE, image.getRGB(1, 1));
    }

    @Test
    void keepsTheAlphaOfAThirtyTwoBitPixel() throws IOException {
        BufferedImage image = TgaReader.read(targa(2, 1, 1, 32, 0x20, new int[] {0x33, 0x22, 0x11, 0x44}));

        assertEquals(0x44112233, image.getRGB(0, 0));
    }

    /** A run length packet carries one pixel and a count; a raw packet carries the count in full. */
    @Test
    void expandsRunLengthEncodedTrueColour() throws IOException {
        BufferedImage image = TgaReader.read(targa(10, 2, 2, 24, 0x20, new int[] {
            0x82, 0x00, 0x00, 0xFF,
            0x00, 0x00, 0xFF, 0x00,
        }));

        assertEquals(RED, image.getRGB(0, 0));
        assertEquals(RED, image.getRGB(1, 0));
        assertEquals(RED, image.getRGB(0, 1));
        assertEquals(GREEN, image.getRGB(1, 1));
    }

    @Test
    void expandsRunLengthEncodedPixelsWithAlpha() throws IOException {
        BufferedImage image = TgaReader.read(targa(10, 2, 1, 32, 0x20,
            new int[] {0x81, 0x33, 0x22, 0x11, 0x44}));

        assertEquals(0x44112233, image.getRGB(0, 0));
        assertEquals(0x44112233, image.getRGB(1, 0));
    }

    @Test
    void rejectsBytesTooShortToHoldAHeader() {
        assertThrows(IOException.class, () -> TgaReader.read(new byte[4]));
    }

    @Test
    void rejectsAnImageTypeItWouldMisread() {
        assertThrows(IOException.class, () -> TgaReader.read(targa(32, 1, 1, 24, 0x00, new int[] {0, 0, 0})));
    }

    @Test
    void rejectsAnImageWithNoPixels() {
        assertThrows(IOException.class, () -> TgaReader.read(targa(2, 0, 0, 24, 0x00, new int[0])));
    }

    private static byte[] targa(int type, int width, int height, int depth, int descriptor, int[] data) {
        byte[] bytes = new byte[18 + data.length];
        bytes[2] = (byte) type;
        bytes[12] = (byte) width;
        bytes[13] = (byte) (width >> 8);
        bytes[14] = (byte) height;
        bytes[15] = (byte) (height >> 8);
        bytes[16] = (byte) depth;
        bytes[17] = (byte) descriptor;
        for (int i = 0; i < data.length; i++) bytes[18 + i] = (byte) data[i];
        return bytes;
    }
}
