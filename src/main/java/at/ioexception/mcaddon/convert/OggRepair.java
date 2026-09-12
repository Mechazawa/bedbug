package at.ioexception.mcaddon.convert;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixes Ogg files whose final page claims a length no clip has.
 *
 * Some pack tools write a granule position on the last page that overflowed on the way out, and a
 * player reading it believes the clip runs for hours. Java Edition's sound engine rejects those files
 * outright. Rewriting that one field, and the page checksum with it, leaves the audio untouched.
 */
final class OggRepair {
    /** No voice clip is an hour long, so a granule beyond this is the overflow and not a long file. */
    private static final long IMPLAUSIBLE_SECONDS = 3600;

    private OggRepair() {
    }

    /** Null when the file is already sound, which is the usual answer. */
    static byte[] repair(byte[] bytes) {
        List<Integer> pages = pageOffsets(bytes);
        if (pages.size() < 2) return null;

        long sampleRate = sampleRate(bytes, pages.get(0));
        if (sampleRate <= 0) return null;

        int last = pages.get(pages.size() - 1);
        long declared = granule(bytes, last);
        long previous = granule(bytes, pages.get(pages.size() - 2));
        if (declared >= previous && declared <= sampleRate * IMPLAUSIBLE_SECONDS) return null;

        byte[] fixed = bytes.clone();
        writeGranule(fixed, last, Math.max(previous, 0));
        writeChecksum(fixed, last, pageLength(fixed, last));
        return fixed;
    }

    private static List<Integer> pageOffsets(byte[] bytes) {
        List<Integer> offsets = new ArrayList<>();
        int at = 0;
        while (at + 27 <= bytes.length && isPage(bytes, at)) {
            offsets.add(at);
            at += pageLength(bytes, at);
        }
        return offsets;
    }

    private static boolean isPage(byte[] bytes, int at) {
        return bytes[at] == 'O' && bytes[at + 1] == 'g' && bytes[at + 2] == 'g' && bytes[at + 3] == 'S';
    }

    private static int pageLength(byte[] bytes, int at) {
        int segments = bytes[at + 26] & 0xFF;
        int payload = 0;
        for (int i = 0; i < segments; i++) payload += bytes[at + 27 + i] & 0xFF;
        return 27 + segments + payload;
    }

    private static long granule(byte[] bytes, int at) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = value << 8 | bytes[at + 6 + i] & 0xFFL;
        return value;
    }

    private static void writeGranule(byte[] bytes, int at, long value) {
        for (int i = 0; i < 8; i++) bytes[at + 6 + i] = (byte) (value >>> i * 8);
    }

    /** The sample rate sits in the Vorbis identification header, which is the first page's payload. */
    private static long sampleRate(byte[] bytes, int at) {
        int segments = bytes[at + 26] & 0xFF;
        int payload = at + 27 + segments;
        if (payload + 16 > bytes.length || bytes[payload] != 1) return 0;
        if (bytes[payload + 1] != 'v' || bytes[payload + 2] != 'o' || bytes[payload + 3] != 'r') return 0;

        long rate = 0;
        for (int i = 3; i >= 0; i--) rate = rate << 8 | bytes[payload + 12 + i] & 0xFFL;
        return rate;
    }

    /**
     * Ogg's own checksum, which is not the one in {@link java.util.zip.CRC32}: same polynomial, but
     * computed without reflecting the input or the output and with no final inversion.
     */
    private static void writeChecksum(byte[] bytes, int at, int length) {
        for (int i = 0; i < 4; i++) bytes[at + 22 + i] = 0;

        int crc = 0;
        for (int i = at; i < at + length; i++) {
            crc = crc << 8 ^ TABLE[(crc >>> 24 ^ bytes[i] & 0xFF) & 0xFF];
        }
        for (int i = 0; i < 4; i++) bytes[at + 22 + i] = (byte) (crc >>> i * 8);
    }

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int value = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                value = (value & 0x80000000) != 0 ? value << 1 ^ 0x04C11DB7 : value << 1;
            }
            TABLE[i] = value;
        }
    }
}
