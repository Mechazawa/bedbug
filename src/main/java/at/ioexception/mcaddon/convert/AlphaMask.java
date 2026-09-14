package at.ioexception.mcaddon.convert;

import java.awt.image.BufferedImage;

/**
 * A texture whose alpha channel is a cut-out mask rather than a fade.
 *
 * Bedrock's alpha tested materials draw every texel carrying any alpha at all and drop only the
 * fully transparent ones, so Mojang's own entity textures mark whole regions with an alpha of two
 * or three and rely on them being drawn opaque: the face and the bare skin of `sheep.tga` are alpha
 * three, and so are the markings on a dozen of the cats. Java's cutout shader instead drops
 * everything below a tenth, which throws those regions away and leaves the model drawn from
 * whatever is left, for a sheep the white of its wool.
 *
 * Raising them is only right where the channel carries nothing else. An image also holding alpha
 * that Java draws partially is a real fade and every value in it means what it says, so
 * {@link #tryFrom} answers null for one of those, and for one whose alpha Java already keeps.
 */
public record AlphaMask(BufferedImage image) {
    /** The lowest alpha Java's cutout shader keeps, which discards anything under a tenth of full. */
    private static final int KEPT = 26;

    private static final int OPAQUE = 0xFF;

    /**
     * This image read as a mask, or null where its alpha needs nothing done to it.
     *
     * Every texel of a mask is fully transparent, fully opaque, or carries an alpha the two editions
     * disagree about. One carrying an alpha both editions draw is a fade.
     */
    public static AlphaMask tryFrom(BufferedImage image) {
        if (image == null || !image.getColorModel().hasAlpha()) return null;

        boolean disagreed = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha == 0 || alpha == OPAQUE) continue;
                if (alpha >= KEPT) return null;

                disagreed = true;
            }
        }
        return disagreed ? new AlphaMask(image) : null;
    }

    /** The same image with every texel Bedrock draws made opaque, so Java's cutout keeps it. */
    public BufferedImage opaque() {
        BufferedImage raised = new BufferedImage(
            image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                raised.setRGB(x, y, pixel >>> 24 == 0 ? pixel : pixel | 0xFF000000);
            }
        }
        return raised;
    }
}
