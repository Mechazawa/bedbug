package at.ioexception.mcaddon.convert;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which Ogg files get their last page rewritten, and what the rewritten page says.
 *
 * The pages here are built byte by byte, because the repair is a question about four fields: the
 * granule position of the last two pages, the sample rate in the Vorbis identification header, and
 * the page checksum that has to follow any change to the granule.
 *
 * The expected checksum is the value Ogg's algorithm gives for that exact page: polynomial
 * 0x04C11DB7 over the page with its checksum field zeroed, no reflection of input or output and no
 * final inversion. That is CRC-32/CKSUM without its final xor, so any CRC calculator reproduces it.
 */
class OggRepairTest {
    private static final int SAMPLE_RATE = 44100;
    private static final int SERIAL = 0x12345678;
    private static final long OVERFLOWED_GRANULE = 0xFFFFFFFFFFFFL;
    private static final int REPAIRED_CHECKSUM = 0x300E7C33;

    /** Where the second page starts: the identification page is 28 bytes of header and 30 of payload. */
    private static final int SECOND_PAGE = 58;

    @Test
    void rewritesAnOverflowedFinalGranuleAndTheChecksumWithIt() {
        byte[] repaired = OggRepair.repair(join(identification(0), audio(OVERFLOWED_GRANULE)));

        assertNotNull(repaired);
        assertEquals(0, granuleOf(repaired, SECOND_PAGE));
        assertEquals(REPAIRED_CHECKSUM, checksumOf(repaired, SECOND_PAGE));
    }

    @Test
    void rewritesAFinalGranuleThatRunsBackwards() {
        byte[] repaired = OggRepair.repair(join(identification(1000), audio(500)));

        assertNotNull(repaired);
        assertEquals(1000, granuleOf(repaired, SECOND_PAGE));
    }

    @Test
    void leavesAFileWhoseFinalGranuleIsPlausibleAlone() {
        assertNull(OggRepair.repair(join(identification(0), audio(SAMPLE_RATE))));
    }

    @Test
    void leavesAFileWithOnlyOnePageAlone() {
        assertNull(OggRepair.repair(identification(0)));
    }

    /** With no identification header there is no sample rate, so no granule can be called wrong. */
    @Test
    void leavesAFileWithNoVorbisHeaderAlone() {
        assertNull(OggRepair.repair(join(page(0x02, 0, 0, new byte[30]), audio(OVERFLOWED_GRANULE))));
    }

    private static byte[] identification(long granule) {
        byte[] payload = new byte[30];
        payload[0] = 1;
        System.arraycopy("vorbis".getBytes(StandardCharsets.US_ASCII), 0, payload, 1, 6);
        payload[11] = 2;
        writeInt(payload, 12, SAMPLE_RATE);
        return page(0x02, granule, 0, payload);
    }

    private static byte[] audio(long granule) {
        return page(0x04, granule, 1, new byte[] {0x11, 0x22, 0x33, 0x44});
    }

    private static byte[] page(int type, long granule, int sequence, byte[] payload) {
        byte[] bytes = new byte[28 + payload.length];
        System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        bytes[5] = (byte) type;
        for (int i = 0; i < 8; i++) bytes[6 + i] = (byte) (granule >>> i * 8);
        writeInt(bytes, 14, SERIAL);
        writeInt(bytes, 18, sequence);
        bytes[26] = 1;
        bytes[27] = (byte) payload.length;
        System.arraycopy(payload, 0, bytes, 28, payload.length);
        return bytes;
    }

    private static void writeInt(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> i * 8);
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    private static long granuleOf(byte[] bytes, int at) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = value << 8 | bytes[at + 6 + i] & 0xFFL;
        return value;
    }

    private static int checksumOf(byte[] bytes, int at) {
        int value = 0;
        for (int i = 3; i >= 0; i--) value = value << 8 | bytes[at + 22 + i] & 0xFF;
        return value;
    }
}
