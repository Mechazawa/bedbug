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
import net.minecraft.world.level.block.SculkSpreader;
import net.minecraft.world.level.block.entity.SculkCatalystBlockEntity;
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
import java.util.HashMap;
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

    /** The side a fence reports on, against the state it carries for it. */
    private static final Map<Direction, BooleanProperty> CONNECTIONS = Map.of(
        Direction.NORTH, BlockStateProperties.NORTH,
        Direction.EAST, BlockStateProperties.EAST,
        Direction.SOUTH, BlockStateProperties.SOUTH,
        Direction.WEST, BlockStateProperties.WEST);

    private final Map<Integer, Run> runs = new LinkedHashMap<>();

    /** Every structure name a test has asked for, against what the packs turned out to hold. */
    private final Map<String, McStructure> structures = new HashMap<>();

    /** How many live runs each chunk is being kept loaded for. */
    private final Map<ChunkPos, Integer> holders = new HashMap<>();

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

        List<ChunkPos> held = hold(level, origin, width, depth);
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
            release(level, run);
        });
        runs.clear();
        nextOffset = 0;
        return cleared;
    }

    /**
     * The verdict a test reached, shown to whoever started it.
     *
     * A test that has answered needs no more ticks, so the chunks it was holding are let go here
     * rather than at `clearall`. The area itself stays standing until then, which is what a person
     * who wants to look at what a test built is asking for.
     */
    @HostAccess.Export
    public void report(int runId, boolean passed, String name, String message) {
        Run run = runs.get(runId);
        if (run == null) return;

        if (passed) {
            run.source().sendSuccess(() -> Component.literal(name + " passed."), false);
        } else {
            run.source().sendFailure(Component.literal(name + " failed: " + message));
        }
        release(level(run.dimension()), run);
    }

    /** Lets go of the chunks a run was keeping loaded, once and not again. */
    private void release(ServerLevel level, Run run) {
        if (level == null) return;
        run.held().forEach(chunk -> {
            if (holders.merge(chunk, -1, Integer::sum) <= 0) {
                holders.remove(chunk);
                Platform.releaseChunk(level, chunk);
            }
        });
        run.held().clear();
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

        EntityType<?> wanted = entityType(typeId);
        if (typeId != null && wanted == null) return new String[0];

        return level.getEntitiesOfClass(Entity.class, new AABB(fromX, fromY, fromZ, toX, toY, toZ)).stream()
            .filter(entity -> wanted == null || entity.getType() == wanted)
            .map(Entity::getStringUUID)
            .toArray(String[]::new);
    }

    /** The type a pack named, or null for a name this game has none for, which matches nothing. */
    private static EntityType<?> entityType(String typeId) {
        if (typeId == null) return null;
        Identifier parsed = Identifier.tryParse(namespaced(typeId));
        return parsed == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(parsed).orElse(null);
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

        Identifier named = itemId == null ? null : Identifier.tryParse(namespaced(itemId));
        Item wanted = named == null ? null : BuiltInRegistries.ITEM.getOptional(named).orElse(null);
        if (itemId != null && wanted == null) return "[]";

        JsonArray found = new JsonArray();
        level.getEntitiesOfClass(ItemEntity.class, new AABB(fromX, fromY, fromZ, toX, toY, toZ)).stream()
            .filter(dropped -> wanted == null || dropped.getItem().getItem() == wanted)
            .forEach(dropped -> {
                JsonObject stack = new JsonObject();
                stack.addProperty("id", dropped.getStringUUID());
                stack.addProperty("amount", dropped.getItem().getCount());
                found.add(stack);
            });
        return found.toString();
    }

    /** Removes everything in a box without dropping anything, which is `Test.killAllEntities`. */
    @HostAccess.Export
    public void killEntitiesIn(String dimension, double fromX, double fromY, double fromZ,
        double toX, double toY, double toZ) {
        ServerLevel level = level(dimension);
        if (level == null) return;

        level.getEntitiesOfClass(Entity.class, new AABB(fromX, fromY, fromZ, toX, toY, toZ)).stream()
            .filter(entity -> !(entity instanceof ServerPlayer))
            .forEach(Entity::discard);
    }

    /** Whether a point is inside an entity, which Bedrock calls touching it. */
    @HostAccess.Export
    public boolean touches(String id, double x, double y, double z) {
        Entity entity = entity(id);
        return entity != null && entity.getBoundingBox().contains(x, y, z);
    }

    /**
     * Presses a button, through the block so that everything watching one hears about it.
     *
     * A pack may subscribe to a button being pushed, and it is the block's own press that says so.
     * Setting the powered state instead would light the button and tell nobody.
     */
    @HostAccess.Export
    public void pressButton(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return;

        BlockPos at = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(at);
        if (state.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock button) {
            Platform.pressButton(button, state, level, at, null);
        } else {
            unsupported("Test.pressButton on " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
    }

    /** A lever, which flips and stays flipped rather than popping back out. */
    @HostAccess.Export
    public void pullLever(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return;

        BlockPos at = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(at);
        if (state.getBlock() instanceof net.minecraft.world.level.block.LeverBlock lever) {
            Platform.pullLever(lever, state, level, at, null);
        } else {
            unsupported("Test.pullLever on " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
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
    public void setFluidContainer(String dimension, int x, int y, int z, String type) {
        ServerLevel level = level(dimension);
        if (level == null) return;

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
            return;
        }
        if ("potion".equalsIgnoreCase(type)) unsupported("Test.setFluidContainer Potion");

        level.setBlockAndUpdate(new BlockPos(x, y, z), filled);
    }

    /** The message a block sends itself, which is how a piston or a chest is told to move. */
    @HostAccess.Export
    public void triggerBlockEvent(String dimension, int x, int y, int z, int event, int parameter) {
        ServerLevel level = level(dimension);
        if (level == null) return;

        BlockPos position = new BlockPos(x, y, z);
        level.blockEvent(position, level.getBlockState(position).getBlock(), event, parameter);
    }

    @HostAccess.Export
    public void destroyBlock(String dimension, int x, int y, int z, boolean drop) {
        ServerLevel level = level(dimension);
        if (level != null) level.destroyBlock(new BlockPos(x, y, z), drop);
    }

    /**
     * The spreader a sculk catalyst is working with, as JSON, or null where no catalyst stands.
     *
     * Java keeps the charge Bedrock calls a cursor on the catalyst's own listener, so the whole of
     * what a test asks about one comes out of that: how many there are, where each of them is and
     * how much charge it holds, and the ceiling the game puts on a single one.
     */
    @HostAccess.Export
    public String sculkSpreader(String dimension, int x, int y, int z) {
        SculkSpreader spreader = spreaderAt(dimension, new BlockPos(x, y, z));
        if (spreader == null) return null;

        JsonArray cursors = new JsonArray();
        for (SculkSpreader.ChargeCursor cursor : spreader.getCursors()) {
            JsonObject one = new JsonObject();
            one.addProperty("x", cursor.getPos().getX());
            one.addProperty("y", cursor.getPos().getY());
            one.addProperty("z", cursor.getPos().getZ());
            one.addProperty("charge", cursor.getCharge());
            cursors.add(one);
        }

        JsonObject answer = new JsonObject();
        answer.addProperty("maxCharge", SculkSpreader.MAX_CHARGE);
        answer.add("cursors", cursors);
        return answer.toString();
    }

    /** Puts charge into a catalyst's spreader at a place measured from the catalyst itself. */
    @HostAccess.Export
    public boolean addSculkCharge(String dimension, int x, int y, int z, int offsetX, int offsetY,
        int offsetZ, int charge) {

        BlockPos catalyst = new BlockPos(x, y, z);
        SculkSpreader spreader = spreaderAt(dimension, catalyst);
        if (spreader == null) return false;

        spreader.addCursors(catalyst.offset(offsetX, offsetY, offsetZ), charge);
        return true;
    }

    private SculkSpreader spreaderAt(String dimension, BlockPos position) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        return level.getBlockEntity(position) instanceof SculkCatalystBlockEntity catalyst
            ? catalyst.getListener().getSculkSpreader() : null;
    }

    /** The `north`, `east`, `south` and `west` states a fence carries, as JSON. */
    @HostAccess.Export
    public String fenceConnectivity(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        JsonObject sides = new JsonObject();
        CONNECTIONS.forEach((side, property) ->
            sides.addProperty(side.getName(), state.hasProperty(property) && state.getValue(property)));
        return sides.toString();
    }

    /**
     * The structure a test named, read from whichever pack ships it.
     *
     * Finding one walks every loaded pack's directory and parsing one reads the whole file, and a
     * suite started at once asks for the same few structures over and over. Packs do not change
     * while the game runs, so the answer is kept, a name nothing ships included.
     */
    private McStructure structure(String name) {
        if (name == null || name.isBlank()) return null;
        if (structures.containsKey(name)) return structures.get(name);

        McStructure found = search(name);
        structures.put(name, found);
        return found;
    }

    private McStructure search(String name) {
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
     * Keeps the chunks a run covers loaded, and says which ones the run now owes a release for.
     *
     * A test runs on a server that may have nobody near it, and a chunk nobody is near stops being
     * loaded within a few ticks. Every block a test reads back then comes up missing, so a test that
     * waits for one waits forever. Two runs whose areas land in the same chunk both name it, and the
     * count decides when the game is told, because the ticket itself is one thing however many times
     * it is asked for.
     */
    private List<ChunkPos> hold(ServerLevel level, BlockPos origin, int width, int depth) {
        List<ChunkPos> held = new ArrayList<>();
        int fromX = SectionPos.blockToSectionCoord(origin.getX());
        int fromZ = SectionPos.blockToSectionCoord(origin.getZ());
        int toX = SectionPos.blockToSectionCoord(origin.getX() + width);
        int toZ = SectionPos.blockToSectionCoord(origin.getZ() + depth);
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                if (holders.merge(chunk, 1, Integer::sum) == 1) Platform.holdChunk(level, chunk);
                held.add(chunk);
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

    /** One area a test was given, so `clearall` can hand it back along with the chunks it holds. */
    private record Run(CommandSourceStack source, String dimension, BlockPos origin,
        int width, int height, int depth, List<ChunkPos> held) {
    }
}
