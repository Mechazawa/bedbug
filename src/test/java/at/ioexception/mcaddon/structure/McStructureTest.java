package at.ioexception.mcaddon.structure;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reading a Bedrock structure, whose NBT runs the other way round from Java's.
 *
 * Every number in one is little endian and nothing in it is compressed, so Java's own reader opens
 * none of them. Getting the byte order wrong reads a size of three as fifty million, which is why
 * the dimensions are what this asserts on.
 */
class McStructureTest {
    private static final int INT = 3;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;

    @Test
    void readsTheDimensionsBedrockWrote() {
        McStructure structure = McStructure.read(written(3, 4, 5));

        assertNotNull(structure);
        assertEquals(3, structure.width());
        assertEquals(4, structure.height());
        assertEquals(5, structure.depth());
    }

    @Test
    void refusesBytesThatAreNotAStructure() {
        assertNull(McStructure.read("not nbt at all".getBytes(StandardCharsets.UTF_8)));
        assertNull(McStructure.read(new byte[0]));
        assertNull(McStructure.read(Arrays.copyOf(written(3, 3, 3), 12)));
    }

    /** One structure of the given size, a stone floor under air, in Bedrock's own byte order. */
    private static byte[] written(int width, int height, int depth) {
        int[] blocks = new int[width * height * depth];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    blocks[(x * height + y) * depth + z] = y == 0 ? 1 : 0;
                }
            }
        }

        byte[] palette = concat(
            compound(concat(named(STRING, "name", text("minecraft:air")),
                named(COMPOUND, "states", compound(new byte[0])))),
            compound(concat(named(STRING, "name", text("minecraft:stone")),
                named(COMPOUND, "states", compound(new byte[0])))));

        byte[] structure = compound(concat(
            named(LIST, "block_indices", listed(LIST, 2, concat(ints(blocks), ints(filled(blocks.length, -1))))),
            named(COMPOUND, "palette", compound(named(COMPOUND, "default",
                compound(named(LIST, "block_palette", listed(COMPOUND, 2, palette))))))));

        return named(COMPOUND, "", compound(concat(
            named(INT, "format_version", i32(1)),
            named(LIST, "size", ints(new int[] {width, height, depth})),
            named(COMPOUND, "structure", structure))));
    }

    private static int[] filled(int length, int value) {
        int[] values = new int[length];
        Arrays.fill(values, value);
        return values;
    }

    /** A tag inside a compound, which carries its type and its name before its payload. */
    private static byte[] named(int type, String name, byte[] payload) {
        byte[] label = name.getBytes(StandardCharsets.UTF_8);
        return concat(new byte[] {(byte) type}, u16(label.length), label, payload);
    }

    private static byte[] compound(byte[] body) {
        return concat(body, new byte[] {0});
    }

    private static byte[] text(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        return concat(u16(encoded.length), encoded);
    }

    /** A list payload. Its elements carry neither a type nor a name, only the values. */
    private static byte[] listed(int elementType, int count, byte[] payloads) {
        return concat(new byte[] {(byte) elementType}, i32(count), payloads);
    }

    private static byte[] ints(int[] values) {
        byte[] body = new byte[0];
        for (int value : values) body = concat(body, i32(value));
        return listed(INT, values.length, body);
    }

    private static byte[] u16(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8)};
    }

    private static byte[] i32(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) joined.writeBytes(part);
        return joined.toByteArray();
    }
}
