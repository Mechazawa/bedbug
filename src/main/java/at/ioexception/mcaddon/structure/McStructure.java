package at.ioexception.mcaddon.structure;

import at.ioexception.mcaddon.registry.AddonBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A Bedrock `.mcstructure`, and the world it builds.
 *
 * Bedrock writes its NBT little endian and uncompressed, so Java's own reader cannot open one. The
 * palette carries Bedrock's block names and states, which {@link AddonBlocks#stateOf} turns into
 * the Java state they mean, a pack's own blocks included. A name no block answers, or a state no
 * property matches, is reported and the rest of the structure is still built.
 *
 * A cell holds two blocks: the block itself and what liquid shares its space, which is the only use
 * Bedrock puts the second layer to. That layer becomes Java's waterlogged state.
 */
public final class McStructure {
    private static final String DEFAULT_NAMESPACE = "mystructure";

    /** A palette entry a cell has none of, which Bedrock writes to mean "leave what is there". */
    private static final int KEEP = -1;

    private final int width;
    private final int height;
    private final int depth;
    private final List<Palette> palette;
    private final int[] blocks;
    private final int[] liquids;

    /** Positions whose block carries saved contents, and entities saved with the structure. */
    private final int carried;
    private final int living;

    private McStructure(int width, int height, int depth, List<Palette> palette,
        int[] blocks, int[] liquids, int carried, int living) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.palette = palette;
        this.blocks = blocks;
        this.liquids = liquids;
        this.carried = carried;
        this.living = living;
    }

    /**
     * The Java id a structure name a pack writes means.
     *
     * Bedrock reads a name with no namespace as one of its own default namespace, the one a file
     * written straight into `structures/` takes, and folds case, where Java reads such a name as
     * the game's and refuses a capital letter.
     */
    public static String javaName(String bedrock) {
        String name = bedrock.toLowerCase(Locale.ROOT);
        return name.indexOf(':') < 0 ? DEFAULT_NAMESPACE + ":" + name : name;
    }

    /** Null when the bytes are not a structure this can read, which a caller reports as a miss. */
    public static McStructure read(byte[] bytes) {
        Object root;
        try {
            root = new Reader(bytes).root();
        } catch (RuntimeException error) {
            return null;
        }

        List<?> size = listOf(member(root, "size"));
        Object structure = member(root, "structure");
        List<?> layers = listOf(member(structure, "block_indices"));
        List<?> entries = listOf(member(member(member(structure, "palette"), "default"), "block_palette"));
        if (size.size() != 3 || layers.isEmpty()) return null;

        int width = number(size.get(0));
        int height = number(size.get(1));
        int depth = number(size.get(2));
        if (width <= 0 || height <= 0 || depth <= 0) return null;

        List<Palette> palette = new ArrayList<>();
        for (Object entry : entries) palette.add(Palette.of(entry));

        // What a chest, a sign or a spawner holds, and the entities saved inside the structure. Both
        // are counted rather than read, so a structure that places empty says why.
        int carried = sizeOf(member(member(member(structure, "palette"), "default"),
            "block_position_data"));
        int living = sizeOf(member(structure, "entities"));

        return new McStructure(width, height, depth, palette, layer(layers, 0), layer(layers, 1),
            carried, living);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    /**
     * Writes the structure into the world with its lower north-west corner at the given position.
     *
     * Every block is written without a neighbour update, the way Java's own structure placement
     * writes one, so a redstone contraption the pack built does not fire while it is half there.
     */
    public void place(ServerLevel level, BlockPos origin, Consumer<String> report) {
        if (carried > 0) report.accept("Structure block contents");
        if (living > 0) report.accept("Structure entities");

        BlockState[] resolved = resolvedPalette(report);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    int cell = (x * height + y) * depth + z;
                    BlockState state = stateAt(resolved, cell);
                    if (state == null) continue;
                    at.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    level.setBlock(at, waterlogged(resolved, state, cell), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * The structure as the NBT a Java structure template is read from.
     *
     * A cell left alone is left out of the block list, which is how a Java template leaves the
     * world alone too, so placing the template writes what {@link #place} writes.
     */
    public CompoundTag javaTemplate(Consumer<String> report) {
        if (carried > 0) report.accept("Structure block contents");
        if (living > 0) report.accept("Structure entities");

        BlockState[] resolved = resolvedPalette(report);
        Map<BlockState, Integer> indices = new LinkedHashMap<>();
        ListTag cells = new ListTag();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    int cell = (x * height + y) * depth + z;
                    BlockState state = stateAt(resolved, cell);
                    if (state == null) continue;

                    CompoundTag block = new CompoundTag();
                    block.put("pos", ints(x, y, z));
                    block.putInt("state", indices.computeIfAbsent(waterlogged(resolved, state, cell),
                        added -> indices.size()));
                    cells.add(block);
                }
            }
        }

        ListTag states = new ListTag();
        indices.keySet().forEach(state -> states.add(NbtUtils.writeBlockState(state)));

        CompoundTag template = new CompoundTag();
        template.put("size", ints(width, height, depth));
        template.put("palette", states);
        template.put("blocks", cells);
        template.put("entities", new ListTag());
        return template;
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) list.add(IntTag.valueOf(value));
        return list;
    }

    /**
     * The palette as states, worked out once for the whole placement.
     *
     * Resolving per position would parse the same name and re-apply the same properties for every
     * cell that uses it, and would say the same thing about an unknown block as many times as the
     * structure places it.
     */
    BlockState[] resolvedPalette(Consumer<String> report) {
        BlockState[] resolved = new BlockState[palette.size()];
        for (int index = 0; index < resolved.length; index++) {
            resolved[index] = resolve(index, report);
        }
        return resolved;
    }

    /** Null where the cell says to leave the world alone, which the caller skips. */
    private BlockState stateAt(BlockState[] resolved, int cell) {
        int index = cell < blocks.length ? blocks[cell] : KEEP;
        if (index == KEEP) return null;
        return index >= 0 && index < resolved.length ? resolved[index] : Blocks.AIR.defaultBlockState();
    }

    private BlockState waterlogged(BlockState[] resolved, BlockState state, int cell) {
        int index = cell < liquids.length ? liquids[cell] : KEEP;
        if (index == KEEP || !state.hasProperty(BlockStateProperties.WATERLOGGED)) return state;

        BlockState liquid = index >= 0 && index < resolved.length ? resolved[index] : null;
        return liquid != null && !liquid.getFluidState().isEmpty()
            ? state.setValue(BlockStateProperties.WATERLOGGED, true) : state;
    }

    /** Air for a name this game has no block for, so one unknown block costs one position. */
    private BlockState resolve(int index, Consumer<String> report) {
        if (index < 0 || index >= palette.size()) return Blocks.AIR.defaultBlockState();

        Palette entry = palette.get(index);
        BlockState state = AddonBlocks.stateOf(entry.name(), entry.states(),
            detail -> report.accept("Structure " + detail));
        if (state == null) {
            report.accept("Structure block " + entry.name());
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    /** One block the structure names, under Bedrock's spelling of it. */
    private record Palette(String name, Map<String, Object> states) {
        static Palette of(Object entry) {
            Map<String, Object> states = new LinkedHashMap<>();
            if (member(entry, "states") instanceof Map<?, ?> written) {
                written.forEach((key, value) -> states.put(String.valueOf(key), value));
            }
            return new Palette(String.valueOf(member(entry, "name")), states);
        }
    }

    /** One of the two index layers, as ints, and empty when the file has no such layer. */
    private static int[] layer(List<?> layers, int which) {
        if (which >= layers.size()) return new int[0];

        Object found = layers.get(which);
        if (found instanceof int[] packed) return packed;
        if (!(found instanceof List<?> values)) return new int[0];

        int[] indices = new int[values.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = number(values.get(i));
        return indices;
    }

    private static Object member(Object node, String key) {
        return node instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static List<?> listOf(Object node) {
        return node instanceof List<?> list ? list : List.of();
    }

    /** How many things a node holds, whether the format wrote them as a list or keyed by position. */
    private static int sizeOf(Object node) {
        if (node instanceof List<?> list) return list.size();
        return node instanceof Map<?, ?> map ? map.size() : 0;
    }

    private static int number(Object value) {
        return value instanceof Number found ? found.intValue() : 0;
    }

    /**
     * Bedrock's NBT, which is the same set of tags as Java's written the other way round.
     *
     * Every number is little endian, a string is a length and its UTF-8 bytes, and nothing is
     * compressed. A compound becomes a map and a list becomes a list, so a caller reads the tree
     * with `instanceof` and never a cast.
     */
    private static final class Reader {
        private final byte[] bytes;
        private int at;

        private Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        private Object root() {
            int type = u8();
            if (type != 10) throw new IllegalStateException("not a compound");
            text();
            return compound();
        }

        private Object value(int type) {
            return switch (type) {
                case 1 -> (int) bytes[at++];
                case 2 -> (int) (short) (u8() | u8() << 8);
                case 3 -> i32();
                case 4 -> i64();
                case 5 -> Float.intBitsToFloat(i32());
                case 6 -> Double.longBitsToDouble(i64());
                case 7 -> bytesOf(i32());
                case 8 -> text();
                case 9 -> list();
                case 10 -> compound();
                case 11 -> ints(i32());
                case 12 -> longs(i32());
                default -> throw new IllegalStateException("unknown tag " + type);
            };
        }

        private Map<String, Object> compound() {
            Map<String, Object> members = new LinkedHashMap<>();
            while (true) {
                int type = u8();
                if (type == 0) return members;
                members.put(text(), value(type));
            }
        }

        private List<Object> list() {
            int type = u8();
            int count = i32();
            List<Object> values = new ArrayList<>(Math.max(0, Math.min(count, 1024)));
            for (int i = 0; i < count; i++) values.add(type == 0 ? null : value(type));
            return values;
        }

        private String text() {
            int length = u8() | u8() << 8;
            String read = new String(bytes, at, length, StandardCharsets.UTF_8);
            at += length;
            return read;
        }

        private int[] bytesOf(int count) {
            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = bytes[at++];
            return values;
        }

        private int[] ints(int count) {
            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = i32();
            return values;
        }

        private long[] longs(int count) {
            long[] values = new long[count];
            for (int i = 0; i < count; i++) values[i] = i64();
            return values;
        }

        private int u8() {
            return bytes[at++] & 0xFF;
        }

        private int i32() {
            return u8() | u8() << 8 | u8() << 16 | u8() << 24;
        }

        private long i64() {
            return (i32() & 0xFFFFFFFFL) | (long) i32() << 32;
        }
    }
}
