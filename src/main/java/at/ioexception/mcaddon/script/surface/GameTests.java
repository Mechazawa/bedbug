package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptCoverage;
import at.ioexception.mcaddon.structure.McStructure;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.graalvm.polyglot.HostAccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The world a pack's own `@minecraft/server-gametest` test runs against.
 *
 * A test is started from a command and answers back to whoever typed it, so this holds both halves:
 * the area each run was given, which `/gametest clearall` takes back, and the command source waiting
 * on the verdict. Everything else here is a world operation the `@minecraft/server` surface has no
 * member for, because only a test needs it.
 */
public final class GameTests extends Surface {
    /** The floor a run with no structure gets, wide enough for what a test usually spawns. */
    private static final int DEFAULT_SIDE = 5;

    /** Headroom cleared above a run, so what it spawns has somewhere to stand. */
    private static final int HEADROOM = 4;

    private final Map<Integer, Run> runs = new LinkedHashMap<>();
    private int nextRun = 1;
    private int nextOffset;

    public GameTests(MinecraftServer server, ScriptCoverage coverage) {
        super(server, coverage);
    }

    /**
     * Takes an area beside whoever asked, builds the test's structure into it and remembers both.
     *
     * Runs are laid out in a row so a whole suite started at once does not build on top of itself.
     * The answer is what the script needs to place its test: the run it belongs to, the dimension,
     * and the world position the test's own coordinates count from.
     */
    public String begin(CommandSourceStack source, String structureName, int padding) {
        ServerLevel level = source.getLevel();
        McStructure structure = structure(structureName);
        int width = structure == null ? DEFAULT_SIDE : structure.width();
        int height = structure == null ? DEFAULT_SIDE : structure.height();
        int depth = structure == null ? DEFAULT_SIDE : structure.depth();

        BlockPos origin = BlockPos.containing(source.getPosition()).offset(nextOffset + 2, 0, 2);
        nextOffset += width + Math.max(padding, 1) + 1;

        List<Long> held = hold(level, origin, width, depth);
        clear(level, origin, width, height + HEADROOM, depth);
        if (structure == null) {
            if (structureName != null && !structureName.isBlank()) {
                unsupported("GameTest structure " + structureName);
            }
            floor(level, origin, width, depth);
        } else {
            structure.place(level, origin, coverage::unsupported);
        }

        int runId = nextRun++;
        runs.put(runId, new Run(source, dimensionOf(level), origin, width, height + HEADROOM, depth, held));

        JsonObject placement = new JsonObject();
        placement.addProperty("runId", runId);
        placement.addProperty("dimension", dimensionOf(level));
        placement.addProperty("x", origin.getX());
        placement.addProperty("y", origin.getY());
        placement.addProperty("z", origin.getZ());
        placement.addProperty("width", width);
        placement.addProperty("height", height);
        placement.addProperty("depth", depth);
        return placement.toString();
    }

    /** Empties every area a run was given and forgets them, which is `/gametest clearall`. */
    public int clearAll() {
        int cleared = runs.size();
        runs.values().forEach(run -> {
            ServerLevel level = level(run.dimension());
            if (level == null) return;
            clear(level, run.origin(), run.width(), run.height(), run.depth());
            run.held().forEach(chunk -> level.setChunkForced(
                ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        });
        runs.clear();
        nextOffset = 0;
        return cleared;
    }

    /** The verdict a test reached, shown to whoever started it. */
    @HostAccess.Export
    public void report(int runId, boolean passed, String name, String message) {
        Run run = runs.get(runId);
        if (run == null) return;

        if (passed) {
            run.source().sendSuccess(() -> Component.literal(name + " passed."), false);
        } else {
            run.source().sendFailure(Component.literal(name + " failed: " + message));
        }
    }

    /** `Test.print`, which Bedrock shows to whoever is watching the test. */
    @HostAccess.Export
    public void print(int runId, String text) {
        Run run = runs.get(runId);
        if (run != null) run.source().sendSuccess(() -> Component.literal(text), false);
    }

    /** A mob with its brain switched off, which is what Bedrock spawns without behaviours. */
    @HostAccess.Export
    public String spawnWithoutAi(String dimension, String typeId, double x, double y, double z) {
        ServerLevel level = level(dimension);
        Identifier parsed = typeId == null ? null : Identifier.tryParse(typeId);
        EntityType<?> type = parsed == null ? null
            : BuiltInRegistries.ENTITY_TYPE.getOptional(parsed).orElse(null);
        if (level == null || type == null) return null;

        Entity spawned = Platform.createMob(type, level);
        if (spawned == null) return null;

        Platform.placeAt(spawned, x, y, z, 0, 0);
        if (spawned instanceof Mob mob) mob.setNoAi(true);
        if (!level.addFreshEntity(spawned)) return null;

        at.ioexception.mcaddon.script.ScriptBridge.remember(spawned);
        return spawned.getStringUUID();
    }

    /** Every entity inside a box, as ids, optionally narrowed to one type. */
    @HostAccess.Export
    public String[] entitiesIn(String dimension, double fromX, double fromY, double fromZ,
        double toX, double toY, double toZ, String typeId) {
        ServerLevel level = level(dimension);
        if (level == null) return new String[0];

        String wanted = typeId == null ? null : namespaced(typeId);
        List<String> found = new ArrayList<>();
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box(fromX, fromY, fromZ, toX, toY, toZ))) {
            if (wanted == null || wanted.equals(typeOf(entity))) found.add(entity.getStringUUID());
        }
        return found.toArray(new String[0]);
    }

    /**
     * The dropped stacks of one kind inside a box, as JSON.
     *
     * Each carries its count as well as its id, because Bedrock's count assertion measures the items
     * and not the entities holding them.
     */
    @HostAccess.Export
    public String itemEntitiesIn(String dimension, double fromX, double fromY, double fromZ,
        double toX, double toY, double toZ, String itemId) {
        ServerLevel level = level(dimension);
        if (level == null) return "[]";

        String wanted = itemId == null ? null : namespaced(itemId);
        JsonArray found = new JsonArray();
        for (ItemEntity dropped
            : level.getEntitiesOfClass(ItemEntity.class, box(fromX, fromY, fromZ, toX, toY, toZ))) {
            Item item = dropped.getItem().getItem();
            if (wanted != null && !wanted.equals(BuiltInRegistries.ITEM.getKey(item).toString())) continue;

            JsonObject stack = new JsonObject();
            stack.addProperty("id", dropped.getStringUUID());
            stack.addProperty("amount", dropped.getItem().getCount());
            found.add(stack);
        }
        return found.toString();
    }

    /** Removes everything in a box without dropping anything, which is `Test.killAllEntities`. */
    @HostAccess.Export
    public int killEntitiesIn(String dimension, double fromX, double fromY, double fromZ,
        double toX, double toY, double toZ) {
        ServerLevel level = level(dimension);
        if (level == null) return 0;

        List<Entity> doomed = level.getEntitiesOfClass(Entity.class, box(fromX, fromY, fromZ, toX, toY, toZ));
        doomed.stream().filter(entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
        return doomed.size();
    }

    /** Whether a point is inside an entity, which Bedrock calls touching it. */
    @HostAccess.Export
    public boolean touches(String id, double x, double y, double z) {
        Entity entity = entity(id);
        return entity != null && entity.getBoundingBox().contains(x, y, z);
    }

    /**
     * Presses a button and lets it pop back out on its own.
     *
     * Written as the state change with a neighbour update rather than through the block class,
     * because the block's own press method takes a different argument list on each version and this
     * is what it does with them.
     */
    @HostAccess.Export
    public boolean pressButton(String dimension, int x, int y, int z) {
        return powered(dimension, x, y, z, "Test.pressButton", true, 30);
    }

    /** A lever, which flips instead of popping back out and so is never rescheduled. */
    @HostAccess.Export
    public boolean pullLever(String dimension, int x, int y, int z) {
        return powered(dimension, x, y, z, "Test.pullLever", null, 0);
    }

    @HostAccess.Export
    public boolean setTntFuse(String id, int ticks) {
        if (!(entity(id) instanceof PrimedTnt tnt)) return false;
        tnt.setFuse(ticks);
        return true;
    }

    /**
     * Fills a cauldron, which is the only container Java has for a fluid.
     *
     * Bedrock's potion and powder snow both name a cauldron too, and Java has a block for each apart
     * from potion, which it keeps as water.
     */
    @HostAccess.Export
    public boolean setFluidContainer(String dimension, int x, int y, int z, String type) {
        ServerLevel level = level(dimension);
        if (level == null) return false;

        BlockState filled = switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "lava" -> Blocks.LAVA_CAULDRON.defaultBlockState();
            case "powdersnow" -> Blocks.POWDER_SNOW_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3);
            case "water", "potion" -> Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3);
            default -> null;
        };
        if (filled == null) {
            unsupported("Test.setFluidContainer " + type);
            return false;
        }
        if ("potion".equalsIgnoreCase(type)) unsupported("Test.setFluidContainer Potion");

        level.setBlockAndUpdate(new BlockPos(x, y, z), filled);
        return true;
    }

    /** The message a block sends itself, which is how a piston or a chest is told to move. */
    @HostAccess.Export
    public boolean triggerBlockEvent(String dimension, int x, int y, int z, int event, int parameter) {
        ServerLevel level = level(dimension);
        if (level == null) return false;

        BlockPos position = new BlockPos(x, y, z);
        level.blockEvent(position, level.getBlockState(position).getBlock(), event, parameter);
        return true;
    }

    @HostAccess.Export
    public boolean destroyBlock(String dimension, int x, int y, int z, boolean drop) {
        ServerLevel level = level(dimension);
        return level != null && level.destroyBlock(new BlockPos(x, y, z), drop);
    }

    /** The `north`, `east`, `south` and `west` states a fence carries, as JSON. */
    @HostAccess.Export
    public String fenceConnectivity(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        JsonObject sides = new JsonObject();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BooleanProperty property = connection(side);
            sides.addProperty(side.getName(),
                property != null && state.hasProperty(property) && state.getValue(property));
        }
        return sides.toString();
    }

    private static BooleanProperty connection(Direction side) {
        return switch (side) {
            case NORTH -> BlockStateProperties.NORTH;
            case EAST -> BlockStateProperties.EAST;
            case SOUTH -> BlockStateProperties.SOUTH;
            case WEST -> BlockStateProperties.WEST;
            default -> null;
        };
    }

    /**
     * Switches a block's powered state and tells its neighbours.
     *
     * A null choice flips what is there, which is a lever, and a delay above zero schedules the
     * block's own tick, which is how a button pops back out.
     */
    private boolean powered(String dimension, int x, int y, int z, String member, Boolean choice, int delay) {
        ServerLevel level = level(dimension);
        if (level == null) return false;

        BlockPos position = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(position);
        if (!state.hasProperty(BlockStateProperties.POWERED)) {
            unsupported(member + " on " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            return false;
        }

        boolean wanted = choice != null ? choice : !state.getValue(BlockStateProperties.POWERED);
        level.setBlock(position, state.setValue(BlockStateProperties.POWERED, wanted), Block.UPDATE_ALL);
        if (delay > 0) level.scheduleTick(position, state.getBlock(), delay);
        return true;
    }

    /** The structure a test named, read from whichever pack ships it. */
    private McStructure structure(String name) {
        if (name == null || name.isBlank()) return null;

        String wanted = name.replace(':', '/').toLowerCase(Locale.ROOT);
        for (BedrockPack pack : McAddon.packs().packs()) {
            for (Path file : pack.list("structures", ".mcstructure")) {
                String relative = pack.root().relativize(file).toString()
                    .replace('\\', '/').toLowerCase(Locale.ROOT);
                String bare = relative.substring("structures/".length(),
                    relative.length() - ".mcstructure".length());
                if (bare.equals(wanted) || bare.endsWith("/" + wanted)) return read(pack, file);
            }
        }
        return null;
    }

    private McStructure read(BedrockPack pack, Path file) {
        try (InputStream stream = pack.open(pack.root().relativize(file).toString())) {
            return McStructure.read(stream.readAllBytes());
        } catch (IOException error) {
            McAddon.LOGGER.error("Could not read the structure {} from {}.", file, pack.displayName());
            return null;
        }
    }

    /**
     * Keeps the chunks a run covers loaded, and says which of them this run is the one holding.
     *
     * A test runs on a server that may have nobody near it, and a chunk nobody is near stops being
     * loaded within a few ticks. Every block a test reads back then comes up missing, so a test that
     * waits for one waits forever. A chunk somebody else already forced is left out of the answer,
     * because letting it go at the end of the run is not this run's to do.
     */
    private static List<Long> hold(ServerLevel level, BlockPos origin, int width, int depth) {
        List<Long> held = new ArrayList<>();
        int fromX = SectionPos.blockToSectionCoord(origin.getX());
        int fromZ = SectionPos.blockToSectionCoord(origin.getZ());
        int toX = SectionPos.blockToSectionCoord(origin.getX() + width);
        int toZ = SectionPos.blockToSectionCoord(origin.getZ() + depth);
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                if (level.setChunkForced(x, z, true)) held.add(ChunkPos.pack(x, z));
            }
        }
        return held;
    }

    private static void clear(ServerLevel level, BlockPos origin, int width, int height, int depth) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    level.setBlock(origin.offset(x, y, z), air, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static void floor(ServerLevel level, BlockPos origin, int width, int depth) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                level.setBlock(origin.offset(x, 0, z), stone, Block.UPDATE_CLIENTS);
            }
        }
    }

    private static AABB box(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        return new AABB(fromX, fromY, fromZ, toX, toY, toZ);
    }

    private static String typeOf(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static String namespaced(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** One area a test was given, so `clearall` can hand it back along with the chunks it holds. */
    private record Run(CommandSourceStack source, String dimension, BlockPos origin,
        int width, int height, int depth, List<Long> held) {
    }
}
