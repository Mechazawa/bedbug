package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bedrock's ticking areas, which are Java's force-loaded chunks under a name.
 *
 * Java keeps a chunk loaded and ticking with nobody near it once it is forced, which is what a
 * ticking area is for, and it already loads every forced chunk before the world starts, which is
 * what Bedrock's `preload` asks for; the flag is kept and reported, since a pack reads it back. What
 * Java has no word for is the area itself: its name, its shape and the chunks it holds. Those are
 * kept here, beside the world, and a chunk is released only once no area covers it any more.
 *
 * Bedrock's limits hold: ten areas in a world, a hundred chunks to an area and a circle of at most
 * four chunks across its radius.
 */
public final class TickingAreas {
    private static final String SECTION = "tickingAreas";
    private static final int MOST_AREAS = 10;
    private static final int MOST_CHUNKS = 100;
    private static final int WIDEST_CIRCLE = 4;

    private static final List<Area> AREAS = new ArrayList<>();

    /**
     * One area: the chunks it holds in one dimension, the corners or the circle it was added as, which
     * is how the list describes it, and whether it is marked to load before the world.
     */
    record Area(String name, String dimension, Set<Long> chunks, String shape, boolean preload) {
        boolean holds(String dimension, BlockPos position) {
            return this.dimension.equals(dimension) && chunks.contains(Platform.chunkKey(position.getX() >> 4,
                position.getZ() >> 4));
        }

        Area preloading(boolean preload) {
            return new Area(name, dimension, chunks, shape, preload);
        }

        String describe() {
            return name + " in " + dimension + ": " + shape + ", " + chunks.size() + " chunk(s)"
                + (preload ? ", preloaded" : "");
        }

        JsonObject encode() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("dimension", dimension);
            json.addProperty("shape", shape);
            json.addProperty("preload", preload);
            JsonArray held = new JsonArray();
            chunks.forEach(held::add);
            json.add("chunks", held);
            return json;
        }

        static Area decode(JsonObject json) {
            Set<Long> chunks = new LinkedHashSet<>();
            json.getAsJsonArray("chunks").forEach(chunk -> chunks.add(chunk.getAsLong()));
            return new Area(json.get("name").getAsString(), json.get("dimension").getAsString(), chunks,
                json.get("shape").getAsString(), json.get("preload").getAsBoolean());
        }
    }

    private TickingAreas() {
    }

    /** The areas this world was left with, which are read once as the server starts. */
    public static void load(AddonState state) {
        AREAS.clear();
        JsonElement saved = state == null ? null : state.section(SECTION);
        if (saved != null && saved.isJsonArray()) {
            saved.getAsJsonArray().forEach(area -> AREAS.add(Area.decode(area.getAsJsonObject())));
        }
    }

    /** The area of that name, or null, which is what a schedule waiting on a ticking area asks for. */
    static Area named(String name) {
        return AREAS.stream().filter(area -> area.name().equals(name)).findFirst().orElse(null);
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tickingarea")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("add")
                .then(named(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(named(Commands.argument("to", BlockPosArgument.blockPos())))))
                .then(Commands.literal("circle")
                    .then(Commands.argument("center", BlockPosArgument.blockPos())
                        .then(named(Commands.argument("radius", IntegerArgumentType.integer(0, WIDEST_CIRCLE)))))))
            .then(Commands.literal("remove")
                .then(Commands.argument("position", BlockPosArgument.blockPos())
                    .executes(command -> remove(command, at(command))))
                .then(Commands.argument("name", BasicString.basicString())
                    .executes(command -> remove(command, called(command)))))
            .then(Commands.literal("remove_all").executes(command -> remove(command, area -> true)))
            .then(Commands.literal("list")
                .executes(command -> list(command, false))
                .then(Commands.literal("all-dimensions").executes(command -> list(command, true))))
            .then(Commands.literal("preload")
                .then(preload(Commands.argument("position", BlockPosArgument.blockPos()), TickingAreas::at))
                .then(preload(Commands.argument("name", BasicString.basicString()), TickingAreas::called))));
    }

    /** What may follow the shape of a new area: its name, then whether it preloads. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T named(T node) {
        return node.executes(TickingAreas::add)
            .then(Commands.argument("name", BasicString.basicString())
                .executes(TickingAreas::add)
                .then(Commands.argument("preload", BoolArgumentType.bool()).executes(TickingAreas::add)));
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T preload(T node, Picker picker) {
        return node.executes(command -> preload(command, picker.pick(command), null))
            .then(Commands.argument("preload", BoolArgumentType.bool())
                .executes(command -> preload(command, picker.pick(command),
                    BoolArgumentType.getBool(command, "preload"))));
    }

    @FunctionalInterface
    private interface Picker {
        Predicate<Area> pick(CommandContext<CommandSourceStack> command) throws CommandSyntaxException;
    }

    private static int add(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        if (AREAS.size() >= MOST_AREAS) {
            return failed(command, "A world holds at most " + MOST_AREAS + " ticking areas.");
        }

        String name = BedrockCommands.given(command, "name") ? BasicString.get(command, "name") : freeName();
        if (named(name) != null) return failed(command, "There is already a ticking area called " + name + ".");

        Set<Long> chunks = new LinkedHashSet<>();
        String shape;
        if (BedrockCommands.given(command, "center")) {
            BlockPos center = BlockPosArgument.getBlockPos(command, "center");
            int radius = IntegerArgumentType.getInteger(command, "radius");
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radius * radius) {
                        chunks.add(Platform.chunkKey((center.getX() >> 4) + x, (center.getZ() >> 4) + z));
                    }
                }
            }
            shape = "a circle of " + radius + " chunk(s) round " + center.getX() + " " + center.getZ();
        } else {
            BlockPos from = BlockPosArgument.getBlockPos(command, "from");
            BlockPos to = BlockPosArgument.getBlockPos(command, "to");
            int across = Math.abs((from.getX() >> 4) - (to.getX() >> 4)) + 1;
            int deep = Math.abs((from.getZ() >> 4) - (to.getZ() >> 4)) + 1;
            if ((long) across * deep > MOST_CHUNKS) {
                return failed(command, "A ticking area holds at most " + MOST_CHUNKS + " chunks.");
            }
            for (int x = Math.min(from.getX(), to.getX()) >> 4; x <= Math.max(from.getX(), to.getX()) >> 4; x++) {
                for (int z = Math.min(from.getZ(), to.getZ()) >> 4; z <= Math.max(from.getZ(), to.getZ()) >> 4; z++) {
                    chunks.add(Platform.chunkKey(x, z));
                }
            }
            shape = "from " + from.getX() + " " + from.getZ() + " to " + to.getX() + " " + to.getZ();
        }

        ServerLevel level = command.getSource().getLevel();
        Area area = new Area(name, dimension(level), chunks, shape,
            BedrockCommands.given(command, "preload") && BoolArgumentType.getBool(command, "preload"));
        AREAS.add(area);
        chunks.forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true));
        save();

        command.getSource().sendSuccess(() -> Component.literal("Added the ticking area " + area.describe()), true);
        return 1;
    }

    /** Removes every area the test picks, and lets go of each chunk no area left covers. */
    private static int remove(CommandContext<CommandSourceStack> command, Predicate<Area> picked) {
        List<Area> removed = AREAS.stream().filter(picked).toList();
        if (removed.isEmpty()) return failed(command, "No ticking area matches.");

        AREAS.removeAll(removed);
        MinecraftServer server = command.getSource().getServer();
        for (Area area : removed) {
            ServerLevel level = levelOf(server, area.dimension());
            if (level == null) continue;
            area.chunks().stream()
                .filter(chunk -> AREAS.stream().noneMatch(other -> other.dimension().equals(area.dimension())
                    && other.chunks().contains(chunk)))
                .forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        }
        save();

        command.getSource().sendSuccess(
            () -> Component.literal("Removed " + removed.size() + " ticking area(s)."), true);
        return removed.size();
    }

    private static int list(CommandContext<CommandSourceStack> command, boolean everywhere) {
        String here = dimension(command.getSource().getLevel());
        List<Area> shown = AREAS.stream().filter(area -> everywhere || area.dimension().equals(here)).toList();
        if (shown.isEmpty()) return failed(command, "There are no ticking areas.");

        shown.forEach(area -> command.getSource().sendSuccess(() -> Component.literal("- " + area.describe()), false));
        return shown.size();
    }

    /** Marks the areas, or with no value says which of them are marked and returns how many are. */
    private static int preload(CommandContext<CommandSourceStack> command, Predicate<Area> picked, Boolean preload) {
        List<Area> found = AREAS.stream().filter(picked).toList();
        if (found.isEmpty()) return failed(command, "No ticking area matches.");

        if (preload != null) {
            AREAS.replaceAll(area -> found.contains(area) ? area.preloading(preload) : area);
            save();
        }
        List<Area> now = AREAS.stream().filter(picked).toList();
        now.forEach(area -> command.getSource().sendSuccess(() -> Component.literal("- " + area.describe()), false));
        return (int) now.stream().filter(Area::preload).count();
    }

    private static Predicate<Area> at(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        BlockPos position = BlockPosArgument.getBlockPos(command, "position");
        String here = dimension(command.getSource().getLevel());
        return area -> area.holds(here, position);
    }

    private static Predicate<Area> called(CommandContext<CommandSourceStack> command) {
        String name = BasicString.get(command, "name");
        return area -> area.name().equals(name);
    }

    /** Bedrock names an area the line left unnamed after the first number no area uses. */
    private static String freeName() {
        int number = 0;
        while (named("Area" + number) != null) number++;
        return "Area" + number;
    }

    private static void save() {
        AddonState state = ServerHooks.state();
        if (state == null) return;
        JsonArray areas = new JsonArray();
        AREAS.forEach(area -> areas.add(area.encode()));
        state.setSection(SECTION, areas);
    }

    static String dimension(ServerLevel level) {
        return Platform.idOf(level.dimension()).toString();
    }

    static ServerLevel levelOf(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (dimension(level).equals(dimension)) return level;
        }
        return null;
    }

    private static int failed(CommandContext<CommandSourceStack> command, String why) {
        command.getSource().sendFailure(Component.literal(why));
        return 0;
    }
}
