package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
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
 * Bedrock's `schedule on_area_loaded`, which runs a function once every chunk of an area is loaded
 * with its entities.
 *
 * Java's `schedule` waits for a time and has no form waiting for chunks, but whether a chunk and its
 * entities are loaded is a question Java answers, so each waiting function is asked about every
 * server tick and run as the server in the area's dimension once its area answers yes. Bedrock's
 * delay forms are Java's own `schedule function` and `schedule clear`, which this tree's `schedule`
 * holds beside the waiting forms. What is waiting is kept beside the world, so a restart keeps it.
 */
public final class AreaSchedules {
    private static final String SECTION = "areaSchedules";

    private static final List<Waiting> WAITING = new ArrayList<>();

    /** A function waiting on the chunks of one area, and the ticking area it was named by, if any. */
    record Waiting(String dimension, Set<Long> chunks, String function, String area) {
        boolean loaded(ServerLevel level) {
            return chunks.stream().allMatch(chunk -> level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))
                && level.areEntitiesLoaded(chunk));
        }

        JsonObject encode() {
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension);
            json.addProperty("function", function);
            if (area != null) json.addProperty("area", area);
            JsonArray held = new JsonArray();
            chunks.forEach(held::add);
            json.add("chunks", held);
            return json;
        }

        static Waiting decode(JsonObject json) {
            Set<Long> chunks = new LinkedHashSet<>();
            json.getAsJsonArray("chunks").forEach(chunk -> chunks.add(chunk.getAsLong()));
            return new Waiting(json.get("dimension").getAsString(), chunks, json.get("function").getAsString(),
                json.has("area") ? json.get("area").getAsString() : null);
        }
    }

    private AreaSchedules() {
    }

    public static void load(AddonState state) {
        WAITING.clear();
        JsonElement saved = state == null ? null : state.section(SECTION);
        if (saved != null && saved.isJsonArray()) {
            saved.getAsJsonArray().forEach(waiting -> WAITING.add(Waiting.decode(waiting.getAsJsonObject())));
        }
    }

    /** Runs every waiting function whose area has loaded. */
    public static void tick(MinecraftServer server) {
        if (WAITING.isEmpty()) return;

        List<Waiting> ready = WAITING.stream().filter(waiting -> {
            ServerLevel level = TickingAreas.levelOf(server, waiting.dimension());
            return level != null && waiting.loaded(level);
        }).toList();
        if (ready.isEmpty()) return;

        WAITING.removeAll(ready);
        save();
        ready.forEach(waiting -> PackCommand.java("function " + waiting.function()).run(
            server.createCommandSourceStack().withLevel(TickingAreas.levelOf(server, waiting.dimension())),
            CommandReport.DATA));
    }

    /**
     * `schedule on_area_loaded add <from> <to>|circle <center> <radius>|tickingarea <name> <function>`
     * and `schedule on_area_loaded clear tickingarea <name> [function]|function <function>`, with
     * Java's own `schedule function` and `schedule clear` held beside them.
     */
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher,
        CommandDispatcher<CommandSourceStack> java) {
        CommandNode<CommandSourceStack> schedule = dispatcher.register(Commands.literal("schedule")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("on_area_loaded")
                .then(Commands.literal("add")
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                            .then(Commands.argument("function", IdentifierArgument.id())
                                .executes(AreaSchedules::add))))
                    .then(Commands.literal("circle")
                        .then(Commands.argument("center", BlockPosArgument.blockPos())
                            .then(Commands.argument("radius", IntegerArgumentType.integer(0))
                                .then(Commands.argument("function", IdentifierArgument.id())
                                    .executes(AreaSchedules::add)))))
                    .then(Commands.literal("tickingarea")
                        .then(Commands.argument("name", BasicString.basicString())
                            .then(Commands.argument("function", IdentifierArgument.id())
                                .executes(AreaSchedules::add)))))
                .then(Commands.literal("clear")
                    .then(Commands.literal("tickingarea")
                        .then(Commands.argument("name", BasicString.basicString())
                            .executes(AreaSchedules::clear)
                            .then(Commands.argument("function", IdentifierArgument.id())
                                .executes(AreaSchedules::clear))))
                    .then(Commands.literal("function")
                        .then(Commands.argument("function", IdentifierArgument.id())
                            .executes(AreaSchedules::clear))))));

        CommandNode<CommandSourceStack> javas = java.getRoot().getChild("schedule");
        if (javas != null) javas.getChildren().forEach(schedule::addChild);
    }

    private static int add(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        String function = IdentifierArgument.getId(command, "function").toString();
        if (command.getSource().getServer().getFunctions()
            .get(IdentifierArgument.getId(command, "function")).isEmpty()) {
            return failed(command, "There is no function called " + function + ".");
        }

        ServerLevel level = command.getSource().getLevel();
        Set<Long> chunks = new LinkedHashSet<>();
        String area = null;
        String dimension = TickingAreas.dimension(level);
        if (BedrockCommands.given(command, "name")) {
            area = BasicString.get(command, "name");
            TickingAreas.Area named = TickingAreas.named(area);
            if (named == null) return failed(command, "There is no ticking area called " + area + ".");
            chunks.addAll(named.chunks());
            dimension = named.dimension();
        } else if (BedrockCommands.given(command, "center")) {
            BlockPos center = BlockPosArgument.getBlockPos(command, "center");
            int radius = IntegerArgumentType.getInteger(command, "radius");
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radius * radius) {
                        chunks.add(Platform.chunkKey((center.getX() >> 4) + x, (center.getZ() >> 4) + z));
                    }
                }
            }
        } else {
            BlockPos from = BlockPosArgument.getBlockPos(command, "from");
            BlockPos to = BlockPosArgument.getBlockPos(command, "to");
            for (int x = Math.min(from.getX(), to.getX()) >> 4; x <= Math.max(from.getX(), to.getX()) >> 4; x++) {
                for (int z = Math.min(from.getZ(), to.getZ()) >> 4; z <= Math.max(from.getZ(), to.getZ()) >> 4; z++) {
                    chunks.add(Platform.chunkKey(x, z));
                }
            }
        }

        WAITING.add(new Waiting(dimension, chunks, function, area));
        save();
        command.getSource().sendSuccess(() -> Component.literal(function + " waits for "
            + chunks.size() + " chunk(s) to load."), true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        String area = BedrockCommands.given(command, "name") ? BasicString.get(command, "name") : null;
        String function = BedrockCommands.given(command, "function")
            ? IdentifierArgument.getId(command, "function").toString() : null;
        Predicate<Waiting> picked = waiting -> (area == null || area.equals(waiting.area()))
            && (function == null || function.equals(waiting.function()));

        int before = WAITING.size();
        WAITING.removeIf(picked);
        int cleared = before - WAITING.size();
        if (cleared == 0) return failed(command, "Nothing waiting matches.");
        save();
        return cleared;
    }

    private static void save() {
        AddonState state = ServerHooks.state();
        if (state == null) return;
        JsonArray waiting = new JsonArray();
        WAITING.forEach(entry -> waiting.add(entry.encode()));
        state.setSection(SECTION, waiting);
    }

    private static int failed(CommandContext<CommandSourceStack> command, String why) {
        command.getSource().sendFailure(Component.literal(why));
        return 0;
    }
}
