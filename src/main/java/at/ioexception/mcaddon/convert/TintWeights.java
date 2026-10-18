package at.ioexception.mcaddon.convert;

import java.awt.image.BufferedImage;

/**
 * A texture as a colour mask weighs its tint by.
 *
 * Bedrock's `USE_COLOR_MASK` draws a texel as `mix(rgb, rgb * color, alpha)`, so its alpha says how
 * much of the colour it takes. {@link AlphaMask} writes the texture itself with that alpha raised,
 * so the texture as the pack painted it is written beside it. A texture holding only clear and
 * opaque texels needs no such form, because one colour over the whole pass tints it exactly.
 */
public record TintWeights(BufferedImage image) {
    /** What the file holding this form is called, after the name of the texture it was made from. */
    public static final String SUFFIX = "_tint_weights";

    private static final int OPAQUE = 0xFF;

    /** This image as tint weights, or null where every texel is clear or opaque. */
    public static TintWeights tryFrom(BufferedImage image) {
        if (image == null || !image.getColorModel().hasAlpha()) return null;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha > 0 && alpha < OPAQUE) return new TintWeights(image);
            }
        }
        return null;
    }
}
