package at.ioexception.mcaddon.convert;

import java.awt.image.BufferedImage;

/**
 * A texture as Bedrock's plain alpha test draws it, which drops every texel under half opacity.
 *
 * Java's cutout keeps everything from a tenth upwards, so a texel a pack left nearly clear is
 * drawn by Java where Bedrock leaves a hole: a marker pixel at an alpha of one in the corner of a
 * skin turns into a speck floating beside the model. Bedrock tests at half only where nothing else
 * reads the alpha. Under a colour mask the alpha says how much of the texel is tinted and only a
 * fully clear one is dropped, which is the form {@link AlphaMask} writes the texture itself in, so
 * this is written beside it rather than over it and the material of each pass picks between the two.
 */
public record AlphaTested(BufferedImage image) {
    /** What the file holding this form is called, after the name of the texture it was made from. */
    public static final String SUFFIX = "_alpha_tested";

    /** The lowest alpha Bedrock's plain alpha test keeps. */
    private static final int HALF = 0x80;

    /** This image as the alpha test sees it, or null where it holds no texel the test drops and Java keeps. */
    public static AlphaTested tryFrom(BufferedImage image) {
        if (image == null || !image.getColorModel().hasAlpha()) return null;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha > 0 && alpha < HALF) return new AlphaTested(image);
            }
        }
        return null;
    }

    /** The same image with every texel under half opacity cleared. */
    public BufferedImage halved() {
        BufferedImage cleared = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                cleared.setRGB(x, y, pixel >>> 24 < HALF ? 0 : pixel);
            }
        }
        return cleared;
    }
}
