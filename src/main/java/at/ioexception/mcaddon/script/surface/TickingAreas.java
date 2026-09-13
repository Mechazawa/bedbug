package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.script.ScriptCoverage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.graalvm.polyglot.HostAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The areas a script asks the game to keep loaded and ticking.
 *
 * Each area holds a chunk ticket per chunk it covers. The ticket is deliberately not the one
 * `/forceload` uses: that one is written into the saved world, so a server stopped between a script
 * creating an area and removing it again would keep those chunks loaded for good, with nothing left
 * in the game able to say why. This one is forgotten when the server stops, the same way the game
 * test runner's is.
 *
 * The game does not count how many times a chunk was asked for, so the count is kept here: two areas
 * overlapping a chunk each name it, and the ticket is let go of when the last of them is removed.
 */
public final class TickingAreas extends Surface {
    /**
     * Chunks one server may hold at once across every area.
     *
     * Bedrock's own ceiling, which a pack asking for more is refused against rather than being
     * allowed to load a world's worth of chunks from a script.
     */
    private static final int MAX_CHUNKS = 100;

    /** Bedrock refuses an area longer than this along either horizontal edge. */
    private static final int MAX_SIDE = 128;

    /**
     * How far a ticket reaches around the chunk it names.
     *
     * A ticket drops one level of detail per chunk of radius, and a chunk only ticks its entities two
     * levels below full. Nothing short of this leaves an area loaded but standing still, which is not
     * what a pack asks a ticking area for.
     */
    private static final int HOLD_RADIUS = 2;

    /** The ticket a held chunk carries, which is not the one a forced chunk carries. */
    //? if >=26 {
    private static final net.minecraft.server.level.TicketType HELD =
        new net.minecraft.server.level.TicketType(net.minecraft.server.level.TicketType.NO_TIMEOUT,
            net.minecraft.server.level.TicketType.FLAG_LOADING
                | net.minecraft.server.level.TicketType.FLAG_SIMULATION);
    //?} else {
    /*private static final net.minecraft.server.level.TicketType<ChunkPos> HELD =
        net.minecraft.server.level.TicketType.create("mcaddon_ticking_area",
            java.util.Comparator.comparingLong(ChunkPos::toLong));
    *///?}

    private final Map<String, Area> areas = new LinkedHashMap<>();

    /** How many areas name each held chunk, so the last one to let go is the one the game hears. */
    private final Map<String, Integer> holders = new LinkedHashMap<>();

    /** One chunk of an area. Written here rather than as a `ChunkPos`, whose fields moved in 26. */
    private record Chunk(int x, int z) {
    }

    public TickingAreas(MinecraftServer server, ScriptCoverage coverage) {
        super(server, coverage);
    }

    /**
     * Adds an area, or names the reason Bedrock would refuse it.
     *
     * The reasons are Bedrock's own `TickingAreaErrorReason` values, which the script side raises as
     * the error a pack catches. Null when the area was added.
     */
    @HostAccess.Export
    public String createTickingArea(String identifier, String dimension, int fromX, int fromY, int fromZ,
        int toX, int toY, int toZ) {
        if (areas.containsKey(identifier)) return "IdentifierAlreadyExists";

        // A dimension nothing answers to is refused under the same reason a name nothing answers to
        // is, which is the only one of Bedrock's four that says a name was not found.
        ServerLevel level = level(dimension);
        if (level == null) return "UnknownIdentifier";
        if (Math.abs(toX - fromX) + 1 > MAX_SIDE || Math.abs(toZ - fromZ) + 1 > MAX_SIDE) {
            return "SideLengthExceeded";
        }

        Area area = new Area(identifier, dimension, Math.min(fromX, toX), Math.min(fromY, toY),
            Math.min(fromZ, toZ), Math.max(fromX, toX), Math.max(fromY, toY), Math.max(fromZ, toZ));
        long fresh = area.chunks().stream()
            .filter(chunk -> !holders.containsKey(key(dimension, chunk)))
            .count();
        if (chunkCount() + fresh > MAX_CHUNKS) return "OverChunkLimit";

        areas.put(identifier, area);
        area.chunks().forEach(chunk -> {
            if (holders.merge(key(dimension, chunk), 1, Integer::sum) == 1) hold(level, chunk);
        });
        return null;
    }

    /** Whether an area of this size would still fit under the chunk ceiling. */
    @HostAccess.Export
    public boolean hasCapacity(int fromX, int fromZ, int toX, int toZ) {
        int wide = Math.abs(SectionPos.blockToSectionCoord(toX) - SectionPos.blockToSectionCoord(fromX)) + 1;
        int deep = Math.abs(SectionPos.blockToSectionCoord(toZ) - SectionPos.blockToSectionCoord(fromZ)) + 1;
        return chunkCount() + wide * deep <= MAX_CHUNKS;
    }

    @HostAccess.Export
    public boolean hasTickingArea(String identifier) {
        return areas.containsKey(identifier);
    }

    /** One area as JSON, or null when nothing is registered under that name. */
    @HostAccess.Export
    public String tickingArea(String identifier) {
        Area area = areas.get(identifier);
        return area == null ? null : json(area).toString();
    }

    @HostAccess.Export
    public String tickingAreas() {
        JsonArray all = new JsonArray();
        areas.values().forEach(area -> all.add(json(area)));
        return all.toString();
    }

    @HostAccess.Export
    public int chunkCount() {
        return holders.size();
    }

    @HostAccess.Export
    public int maxChunkCount() {
        return MAX_CHUNKS;
    }

    /** False when the name is not one this holds, which the script side raises as Bedrock does. */
    @HostAccess.Export
    public boolean removeTickingArea(String identifier) {
        Area area = areas.remove(identifier);
        if (area == null) return false;

        release(area);
        return true;
    }

    @HostAccess.Export
    public void removeAllTickingAreas() {
        areas.values().forEach(this::release);
        areas.clear();
    }

    private void release(Area area) {
        ServerLevel level = level(area.dimension());
        area.chunks().forEach(chunk -> {
            String key = key(area.dimension(), chunk);
            Integer left = holders.compute(key, (name, count) -> count == null || count <= 1 ? null : count - 1);
            if (left == null && level != null) letGo(level, chunk);
        });
    }

    private JsonObject json(Area area) {
        JsonObject fields = new JsonObject();
        fields.addProperty("identifier", area.identifier());
        fields.addProperty("dimension", area.dimension());
        fields.addProperty("chunkCount", area.chunks().size());
        fields.addProperty("isFullyLoaded", fullyLoaded(area));
        fields.add("min", position(area.fromX(), area.fromY(), area.fromZ()));
        fields.add("max", position(area.toX(), area.toY(), area.toZ()));
        return fields;
    }

    private boolean fullyLoaded(Area area) {
        ServerLevel level = level(area.dimension());
        return level != null && area.chunks().stream()
            .allMatch(chunk -> level.getChunkSource().hasChunk(chunk.x(), chunk.z()));
    }

    private static JsonObject position(int x, int y, int z) {
        JsonObject at = new JsonObject();
        at.addProperty("x", x);
        at.addProperty("y", y);
        at.addProperty("z", z);
        return at;
    }

    private static String key(String dimension, Chunk chunk) {
        return dimension + "/" + chunk.x() + "," + chunk.z();
    }

    private static void hold(ServerLevel level, Chunk chunk) {
        ChunkPos at = new ChunkPos(chunk.x(), chunk.z());
        //? if >=26 {
        level.getChunkSource().addTicketWithRadius(HELD, at, HOLD_RADIUS);
        //?} else {
        /*level.getChunkSource().addRegionTicket(HELD, at, HOLD_RADIUS, at);
        *///?}
    }

    private static void letGo(ServerLevel level, Chunk chunk) {
        ChunkPos at = new ChunkPos(chunk.x(), chunk.z());
        //? if >=26 {
        level.getChunkSource().removeTicketWithRadius(HELD, at, HOLD_RADIUS);
        //?} else {
        /*level.getChunkSource().removeRegionTicket(HELD, at, HOLD_RADIUS, at);
        *///?}
    }

    /** One area a script asked for, and the chunks its corners cover. */
    private record Area(String identifier, String dimension, int fromX, int fromY, int fromZ,
        int toX, int toY, int toZ) {

        List<Chunk> chunks() {
            List<Chunk> covered = new ArrayList<>();
            for (int x = SectionPos.blockToSectionCoord(fromX); x <= SectionPos.blockToSectionCoord(toX); x++) {
                for (int z = SectionPos.blockToSectionCoord(fromZ);
                    z <= SectionPos.blockToSectionCoord(toZ); z++) {
                    covered.add(new Chunk(x, z));
                }
            }
            return covered;
        }
    }
}
