package at.ioexception.mcaddon.convert;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Decodes the Targa files Bedrock packs ship. Java Edition's texture loader reads only PNG.
 *
 * Covers the four types pack tools actually write: true colour and colour mapped, each raw or
 * run length encoded, plus grayscale. Anything else is rejected by name rather than misread.
 */
final class TgaReader {
    private TgaReader() {
    }

    static BufferedImage read(byte[] bytes) throws IOException {
        if (bytes.length < 18) throw new IOException("not a Targa file: " + bytes.length + " bytes");

        int idLength = bytes[0] & 0xFF;
        int colourMapType = bytes[1] & 0xFF;
        int imageType = bytes[2] & 0xFF;
        int colourMapLength = short16(bytes, 5);
        int colourMapDepth = bytes[7] & 0xFF;
        int width = short16(bytes, 12);
        int height = short16(bytes, 14);
        int depth = bytes[16] & 0xFF;
        int descriptor = bytes[17] & 0xFF;
        boolean topDown = (descriptor & 0x20) != 0;
        boolean rightToLeft = (descriptor & 0x10) != 0;

        if (width <= 0 || height <= 0) throw new IOException("Targa image is " + width + "x" + height);

        int offset = 18 + idLength;
        int[] palette = new int[colourMapLength];
        if (colourMapType == 1) {
            int entryBytes = Math.max(1, colourMapDepth / 8);
            for (int i = 0; i < colourMapLength; i++) {
                palette[i] = pixel(bytes, offset + i * entryBytes, colourMapDepth);
            }
            offset += colourMapLength * entryBytes;
        }

        boolean encoded = imageType >= 9;
        int kind = imageType % 8;
        if (kind != 1 && kind != 2 && kind != 3) throw new IOException("unsupported Targa type " + imageType);

        int[] pixels = new int[width * height];
        int pixelBytes = Math.max(1, depth / 8);
        int written = 0;
        int at = offset;

        while (written < pixels.length) {
            int runLength = 1;
            boolean repeat = false;
            if (encoded) {
                int packet = bytes[at++] & 0xFF;
                runLength = (packet & 0x7F) + 1;
                repeat = (packet & 0x80) != 0;
            }

            if (repeat) {
                int colour = sample(bytes, at, depth, kind, palette);
                at += pixelBytes;
                for (int i = 0; i < runLength && written < pixels.length; i++) pixels[written++] = colour;
                continue;
            }
            for (int i = 0; i < runLength && written < pixels.length; i++) {
                pixels[written++] = sample(bytes, at, depth, kind, palette);
                at += pixelBytes;
            }
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            int row = topDown ? y : height - 1 - y;
            for (int x = 0; x < width; x++) {
                int column = rightToLeft ? width - 1 - x : x;
                image.setRGB(column, row, pixels[y * width + x]);
            }
        }
        return image;
    }

    private static int sample(byte[] bytes, int at, int depth, int kind, int[] palette) {
        if (kind == 1) {
            int index = depth == 16 ? short16(bytes, at) : bytes[at] & 0xFF;
            return index < palette.length ? palette[index] : 0;
        }
        if (kind == 3) {
            int grey = bytes[at] & 0xFF;
            return 0xFF000000 | grey << 16 | grey << 8 | grey;
        }
        return pixel(bytes, at, depth);
    }

    /** Targa stores colour as blue, green, red, so the channels come back in the other order. */
    private static int pixel(byte[] bytes, int at, int depth) {
        return switch (depth) {
            case 32 -> (bytes[at + 3] & 0xFF) << 24 | (bytes[at + 2] & 0xFF) << 16
                | (bytes[at + 1] & 0xFF) << 8 | bytes[at] & 0xFF;
            case 24 -> 0xFF000000 | (bytes[at + 2] & 0xFF) << 16 | (bytes[at + 1] & 0xFF) << 8 | bytes[at] & 0xFF;
            case 16 -> {
                int packed = short16(bytes, at);
                int red = (packed >> 10 & 0x1F) * 255 / 31;
                int green = (packed >> 5 & 0x1F) * 255 / 31;
                int blue = (packed & 0x1F) * 255 / 31;
                yield ((packed & 0x8000) != 0 ? 0xFF000000 : 0) | red << 16 | green << 8 | blue;
            }
            default -> 0;
        };
    }

    private static int short16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
    }
}
