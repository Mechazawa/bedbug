package at.ioexception.mcaddon.convert;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which alpha channels are a Bedrock cut-out mask and what raising one does to the pixels.
 *
 * The two editions disagree over a texel that carries a little alpha: Bedrock's alpha test draws
 * it and Java's cutout shader drops it. Mojang's own entity textures are full of them, so a
 * conversion that copies such a channel through costs the model every region marked that way.
 */
class AlphaMaskTest {
    /** The alpha Mojang marks a sheep's face and bare skin with. */
    private static final int MARKED = 3;

    private static final int FACE = 0x00B4977F;
    private static final int WOOL = 0xFFECECEC;

    @Test
    void readsAChannelOfMarksAndOpaqueTexelsAsAMask() {
        BufferedImage image = image(FACE | MARKED << 24, WOOL, 0);

        assertNotNull(AlphaMask.tryFrom(image));
    }

    @Test
    void raisesAMarkedTexelToOpaqueAndKeepsItsColour() {
        BufferedImage raised = AlphaMask.tryFrom(image(FACE | MARKED << 24, WOOL, 0)).opaque();

        assertEquals(0xFF, raised.getRGB(0, 0) >>> 24);
        assertEquals(FACE | 0xFF000000, raised.getRGB(0, 0));
    }

    @Test
    void leavesACutOutTexelCutOut() {
        BufferedImage raised = AlphaMask.tryFrom(image(FACE | MARKED << 24, WOOL, 0)).opaque();

        assertEquals(0, raised.getRGB(2, 0) >>> 24);
    }

    /** A channel Java draws part of is a real fade, where every value means the number it holds. */
    @Test
    void readsAFadeAsSomethingToLeaveAlone() {
        assertNull(AlphaMask.tryFrom(image(0x80FF0000, WOOL, 0)));
    }

    @Test
    void readsAChannelJavaAlreadyKeepsAsNothingToDo() {
        assertNull(AlphaMask.tryFrom(image(WOOL, WOOL, 0)));
    }

    /** A mixture of a fade and a mark is still a fade, so the mark is left where the pack put it. */
    @Test
    void leavesAFadeAloneEvenWhereItAlsoCarriesAMark() {
        assertNull(AlphaMask.tryFrom(image(FACE | MARKED << 24, 0x80FF0000, 0)));
    }

    @Test
    void readsAnImageWithNoAlphaChannelAsNothingToDo() {
        BufferedImage opaque = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        opaque.setRGB(0, 0, FACE);

        assertNull(AlphaMask.tryFrom(opaque));
    }

    /** One row, so a case is written as the three packed pixels it is about. */
    private static BufferedImage image(int first, int second, int third) {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, first);
        image.setRGB(1, 0, second);
        image.setRGB(2, 0, third);
        return image;
    }
}
