package at.ioexception.mcaddon.bedrock.model;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * Which texels of a texture are drawn, which is the shape a texture mesh extrudes.
 *
 * A texel carrying any alpha at all counts, which is what Bedrock's alpha tested materials draw and
 * what Java's own item extrusion treats as solid.
 */
public record TexelMask(int width, int height, BitSet texels) {
    public static TexelMask of(BufferedImage image) {
        BitSet texels = new BitSet(image.getWidth() * image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) >>> 24 != 0) texels.set(y * image.getWidth() + x);
            }
        }
        return new TexelMask(image.getWidth(), image.getHeight(), texels);
    }

    /** The mask of the image at this path, or null where there is no image there to read. */
    public static TexelMask tryRead(Path image) {
        try {
            BufferedImage read = ImageIO.read(image.toFile());
            return read == null ? null : of(read);
        } catch (IOException error) {
            return null;
        }
    }

    /** Whether the texel is drawn; one outside the texture is not. */
    public boolean drawn(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height && texels.get(y * width + x);
    }
}
