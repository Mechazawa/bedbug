package at.ioexception.mcaddon.block;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The columns a border block stands in, which no entity moves into from beside at any height.
 *
 * Each loaded chunk's columns are worked out once, from the sections whose palette holds a border
 * block, and forgotten when a border block is set or cleared in that chunk or the client is sent
 * the chunk again.
 */
public final class BorderColumns {
    private static final int SECTION = 16;

    /** Each chunk's columns holding a border block, by `x * 16 + z` within the chunk. */
    private static final Map<LevelChunk, BitSet> HELD = Collections.synchronizedMap(new WeakHashMap<>());

    private BorderColumns() {
    }

    public static boolean is(BlockState state) {
        return state.getBlock() instanceof BorderBlock;
    }

    public static void forget(LevelChunk chunk) {
        HELD.remove(chunk);
    }

    /**
     * The part of `movement` an entity makes before a border column beside it stops it.
     *
     * Only the horizontal part is cut, because a column has no top or bottom to land on. A column the
     * entity already stands in is left out, so an entity put inside one can still walk out.
     */
    public static Vec3 clip(Entity entity, Vec3 movement) {
        if (movement.x == 0 && movement.z == 0) return movement;

        Level level = entity.level();
        AABB box = entity.getBoundingBox();
        AABB swept = box.expandTowards(movement);
        List<VoxelShape> columns = new ArrayList<>();
        for (int x = Mth.floor(swept.minX); x <= Mth.floor(swept.maxX); x++) {
            for (int z = Mth.floor(swept.minZ); z <= Mth.floor(swept.maxZ); z++) {
                AABB column = new AABB(x, swept.minY - 1, z, x + 1, swept.maxY + 1, z + 1);
                if (!column.intersects(box) && holds(level, x, z)) columns.add(Shapes.create(column));
            }
        }
        if (columns.isEmpty()) return movement;

        double dx = movement.x;
        double dz = movement.z;
        if (Math.abs(dx) < Math.abs(dz)) {
            dz = Shapes.collide(Direction.Axis.Z, box, columns, dz);
            dx = Shapes.collide(Direction.Axis.X, box.move(0, 0, dz), columns, dx);
        } else {
            dx = Shapes.collide(Direction.Axis.X, box, columns, dx);
            dz = Shapes.collide(Direction.Axis.Z, box.move(dx, 0, 0), columns, dz);
        }
        return new Vec3(dx, movement.y, dz);
    }

    private static boolean holds(Level level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        return chunk != null && HELD.computeIfAbsent(chunk, BorderColumns::scan).get((x & 15) * SECTION + (z & 15));
    }

    private static BitSet scan(LevelChunk chunk) {
        BitSet columns = new BitSet(SECTION * SECTION);
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.hasOnlyAir() || !section.maybeHas(BorderColumns::is)) continue;
            for (int x = 0; x < SECTION; x++) {
                for (int z = 0; z < SECTION; z++) {
                    for (int y = 0; y < SECTION; y++) {
                        if (is(section.getBlockState(x, y, z))) columns.set(x * SECTION + z);
                    }
                }
            }
        }
        return columns;
    }
}
