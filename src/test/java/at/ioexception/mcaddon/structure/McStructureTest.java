package at.ioexception.mcaddon.structure;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final byte BYTE = 1;
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

    /**
     * The states Bedrock counts rather than spells reach the Java property that means the same.
     *
     * A name that simply differs would land on the right property by luck of the table; these three
     * carry a number or a flag Java has no reading for, so a wrong rule places a block that looks
     * built but faces the wrong way.
     */
    @Test
    void readsTheStatesBedrockCountsRatherThanSpells() {
        List<String> gaps = new ArrayList<>();
        BlockState[] resolved = McStructure.read(withPalette(
            entry("minecraft:observer", Map.of("facing_direction", 3)),
            entry("minecraft:oak_stairs", Map.of("weirdo_direction", 1, "upside_down_bit", (byte) 1)),
            entry("minecraft:oak_slab", Map.of("minecraft:vertical_half", "top"))))
            .resolvedPalette(gaps::add);

        assertEquals("south", value(resolved[0], "facing"));
        assertEquals("west", value(resolved[1], "facing"));
        assertEquals("top", value(resolved[1], "half"));
        assertEquals("top", value(resolved[2], "type"));
        assertEquals(List.of(), gaps);
    }

    /** A state a pack wrote that Java has no property for is named rather than dropped. */
    @Test
    void namesAStateJavaHasNoPropertyFor() {
        List<String> gaps = new ArrayList<>();
        McStructure.read(withPalette(entry("minecraft:stone", Map.of("no_such_state", 1))))
            .resolvedPalette(gaps::add);

        assertEquals(List.of("Structure block state minecraft:stone[no_such_state]"), gaps);
    }

    private static String value(BlockState state, String property) {
        return state.getProperties().stream()
            .filter(one -> one.getName().equals(property))
            .findFirst()
            .map(one -> state.getValue(one).toString().toLowerCase(java.util.Locale.ROOT))
            .orElse(null);
    }

    /** One palette entry, as the name and states a pack would have written for it. */
    private static Map<String, Object> entry(String name, Map<String, Object> states) {
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("name", name);
        written.put("states", states);
        return written;
    }

    /** A one block structure per entry, so a palette can be read back without a world. */
    @SafeVarargs
    private static byte[] withPalette(Map<String, Object>... entries) {
        byte[] palette = new byte[0];
        for (Map<String, Object> one : entries) {
            byte[] states = new byte[0];
            for (Map.Entry<String, Object> state : castStates(one).entrySet()) {
                states = concat(states, state(state.getKey(), state.getValue()));
            }
            palette = concat(palette, compound(concat(
                named(STRING, "name", text((String) one.get("name"))),
                named(COMPOUND, "states", compound(states)))));
        }

        int count = entries.length;
        byte[] structure = compound(concat(
            named(LIST, "block_indices", listed(LIST, 2,
                concat(ints(sequence(count)), ints(filled(count, -1))))),
            named(COMPOUND, "palette", compound(named(COMPOUND, "default",
                compound(named(LIST, "block_palette", listed(COMPOUND, count, palette))))))));

        return named(COMPOUND, "", compound(concat(
            named(INT, "format_version", i32(1)),
            named(LIST, "size", ints(new int[] {count, 1, 1})),
            named(COMPOUND, "structure", structure))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStates(Map<String, Object> entry) {
        return (Map<String, Object>) entry.get("states");
    }

    /** One state, written as the tag type Bedrock uses for the kind of value it is. */
    private static byte[] state(String name, Object value) {
        if (value instanceof Byte flag) return named(BYTE, name, new byte[] {flag});
        if (value instanceof Integer number) return named(INT, name, i32(number));
        return named(STRING, name, text(String.valueOf(value)));
    }

    private static int[] sequence(int count) {
        int[] values = new int[count];
        for (int index = 0; index < count; index++) values[index] = index;
        return values;
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
