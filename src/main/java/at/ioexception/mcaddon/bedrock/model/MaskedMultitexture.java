package at.ioexception.mcaddon.bedrock.model;

import java.awt.image.BufferedImage;

/**
 * Two textures a `MASKED_MULTITEXTURE` material draws as one.
 *
 * Bedrock's entity shader samples both at the same point and keeps the first wherever the second
 * holds a colour that is not fully opaque, and the second everywhere else:
 * `mix(second, first, (r + g + b) * (1 - a) > 0)`. A profession texture therefore marks where the
 * biome's clothes show through by leaving those texels clear but coloured, and drops a texel from
 * both by leaving it clear and black.
 *
 * Java binds one texture to a pass, so the two are drawn into one image here. Each is sampled at the
 * same point of the model, so the image is as large as the larger of them.
 */
public record MaskedMultitexture(BufferedImage first, BufferedImage second) {
    public BufferedImage composed() {
        int width = Math.max(first.getWidth(), second.getWidth());
        int height = Math.max(first.getHeight(), second.getHeight());
        BufferedImage drawn = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int over = sample(second, x, y, width, height);
                drawn.setRGB(x, y, showsFirst(over) ? sample(first, x, y, width, height) : over);
            }
        }
        return drawn;
    }

    private static boolean showsFirst(int texel) {
        return (texel & 0xFFFFFF) != 0 && texel >>> 24 != 0xFF;
    }

    private static int sample(BufferedImage image, int x, int y, int width, int height) {
        return image.getRGB(x * image.getWidth() / width, y * image.getHeight() / height);
    }
}
