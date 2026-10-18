package at.ioexception.mcaddon.bedrock.model;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a `MASKED_MULTITEXTURE` pass draws its two textures as one: the second's texel wherever it is
 * opaque or clear and black, and the first's wherever the second holds a colour that is not opaque.
 */
class MaskedMultitextureTest {
    private static final int BASE = 0xFF2846C8;
    private static final int OUTFIT = 0xFFC82828;

    @Test
    void drawsTheSecondWhereItIsOpaque() {
        assertEquals(OUTFIT, drawn(BASE, OUTFIT));
    }

    @Test
    void showsTheFirstThroughAClearButColouredTexel() {
        assertEquals(BASE, drawn(BASE, 0x00FFFFFF));
    }

    @Test
    void showsTheFirstThroughAFaintColour() {
        assertEquals(BASE, drawn(BASE, 0x403CC83C));
    }

    @Test
    void dropsATexelTheSecondLeavesClearAndBlack() {
        assertEquals(0, drawn(BASE, 0));
    }

    @Test
    void keepsAnOpaqueBlackOfTheSecond() {
        assertEquals(0xFF000000, drawn(BASE, 0xFF000000));
    }

    /** Each image is sampled at the same point of the model, so a small one is spread over a large one. */
    @Test
    void samplesBothAtTheSamePointWhateverTheirSizes() {
        BufferedImage first = image(2, BASE);
        BufferedImage second = image(4, 0x00FFFFFF);
        second.setRGB(3, 3, OUTFIT);

        BufferedImage drawn = new MaskedMultitexture(first, second).composed();
        assertEquals(4, drawn.getWidth());
        assertEquals(BASE, drawn.getRGB(0, 0));
        assertEquals(OUTFIT, drawn.getRGB(3, 3));
    }

    private static int drawn(int first, int second) {
        return new MaskedMultitexture(image(1, first), image(1, second)).composed().getRGB(0, 0);
    }

    private static BufferedImage image(int size, int argb) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) image.setRGB(x, y, argb);
        }
        return image;
    }
}
