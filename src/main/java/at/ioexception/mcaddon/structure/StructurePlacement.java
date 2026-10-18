package at.ioexception.mcaddon.structure;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * How Bedrock's `/structure load` puts a saved structure into the world.
 *
 * Bedrock names the lowest corner the structure fills after it is turned and mirrored, where Java's
 * template turns about the position it is given, so the template is placed wherever puts that corner
 * there. Mirroring both ways at once is a half turn. `integrity` and `seed` are the ones Java's own
 * `place template` takes, `waterlogged` is whether a block placed into water keeps the water, and an
 * animation places the structure a block or a layer at a time over the seconds the line gives, each
 * step being the template placed inside the part of its box that step reaches.
 *
 * @param integrity the share of blocks placed, from nothing to all of them
 */
public record StructurePlacement(Rotation rotation, Mirror mirror, boolean entities, boolean blocks,
    boolean waterlogged, float integrity, long seed, Animation animation, float seconds) {

    public enum Animation { NONE, BLOCK_BY_BLOCK, LAYER_BY_LAYER }

    private static final List<Unfolding> UNFOLDING = new ArrayList<>();

    /** Every block there is, which is what a placement leaving the blocks out ignores. */
    private static List<net.minecraft.world.level.block.Block> everyBlock;

    /**
     * Places the template with the lowest corner of what it fills at `to`, at once or starting an
     * animation, and answers the box it fills.
     */
    public BoundingBox place(ServerLevel level, StructureTemplate template, BlockPos to) {
        BoundingBox unplaced = template.getBoundingBox(settings(null), BlockPos.ZERO);
        BlockPos origin = to.offset(-unplaced.minX(), -unplaced.minY(), -unplaced.minZ());
        BoundingBox box = template.getBoundingBox(settings(null), origin);

        if (animation == Animation.NONE || seconds <= 0) {
            placeWithin(level, template, origin, null);
        } else {
            UNFOLDING.add(new Unfolding(level, template, origin, box, Math.max(1, Math.round(seconds * 20))));
        }
        return box;
    }

    /** Moves every animated placement on by one tick. */
    public static void tick() {
        UNFOLDING.removeIf(Unfolding::advance);
    }

    /** Drops the animations of a server that is going away, whose levels go with it. */
    public static void forget() {
        UNFOLDING.clear();
    }

    private void placeWithin(ServerLevel level, StructureTemplate template, BlockPos origin, BoundingBox within) {
        // A fresh random from the same seed for every step, so each step decides every block the
        // same way and the steps together place what one placement would.
        template.placeInWorld(level, origin, origin, settings(within), RandomSource.create(seed), 2);
    }

    private StructurePlaceSettings settings(BoundingBox within) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setRotation(rotation)
            .setMirror(mirror)
            .setIgnoreEntities(!entities)
            .setRandom(RandomSource.create(seed));
        Platform.keepLiquids(settings, waterlogged);
        if (integrity < 1f) settings.addProcessor(new BlockRotProcessor(Math.max(0f, integrity)));
        if (!blocks) settings.addProcessor(new BlockIgnoreProcessor(everyBlock()));
        if (within != null) settings.setBoundingBox(within);
        return settings;
    }

    private static List<net.minecraft.world.level.block.Block> everyBlock() {
        if (everyBlock == null) everyBlock = BuiltInRegistries.BLOCK.stream().toList();
        return everyBlock;
    }

    /** A placement being made a part at a time, counted in blocks or in layers. */
    private final class Unfolding {
        private final ServerLevel level;
        private final StructureTemplate template;
        private final BlockPos origin;
        private final BoundingBox box;
        private final int ticks;
        private final int parts;
        private int elapsed;
        private int placed;

        private Unfolding(ServerLevel level, StructureTemplate template, BlockPos origin, BoundingBox box, int ticks) {
            this.level = level;
            this.template = template;
            this.origin = origin;
            this.box = box;
            this.ticks = ticks;
            this.parts = animation == Animation.LAYER_BY_LAYER ? box.getYSpan()
                : box.getXSpan() * box.getYSpan() * box.getZSpan();
        }

        /** True once the whole structure stands. */
        private boolean advance() {
            elapsed++;
            int due = (int) Math.min(parts, Math.ceil((double) parts * elapsed / ticks));
            if (due > placed) {
                boxes(placed, due).forEach(part -> placeWithin(level, template, origin, part));
                placed = due;
            }
            return placed >= parts;
        }

        /**
         * The parts `from` up to `to` as boxes. A layer is one box; a run of blocks, counted along x,
         * then z, then up, is at most a part row, some whole rows, some whole layers, more whole rows
         * and a part row.
         */
        private List<BoundingBox> boxes(int from, int to) {
            List<BoundingBox> found = new ArrayList<>();
            if (animation == Animation.LAYER_BY_LAYER) {
                found.add(new BoundingBox(box.minX(), box.minY() + from, box.minZ(),
                    box.maxX(), box.minY() + to - 1, box.maxZ()));
                return found;
            }

            int across = box.getXSpan();
            int layer = across * box.getZSpan();
            int at = from;
            while (at < to) {
                int x = at % across;
                int z = (at / across) % box.getZSpan();
                int y = at / layer;
                int length;
                if (x != 0 || to - at < across) {
                    length = Math.min(to - at, across - x);
                    found.add(span(x, x + length - 1, z, z, y, y));
                } else if (z != 0 || to - at < layer) {
                    int rows = Math.min((to - at) / across, box.getZSpan() - z);
                    length = rows * across;
                    found.add(span(0, across - 1, z, z + rows - 1, y, y));
                } else {
                    int layers = (to - at) / layer;
                    length = layers * layer;
                    found.add(span(0, across - 1, 0, box.getZSpan() - 1, y, y + layers - 1));
                }
                at += length;
            }
            return found;
        }

        private BoundingBox span(int fromX, int toX, int fromZ, int toZ, int fromY, int toY) {
            return new BoundingBox(box.minX() + fromX, box.minY() + fromY, box.minZ() + fromZ,
                box.minX() + toX, box.minY() + toY, box.minZ() + toZ);
        }
    }
}
