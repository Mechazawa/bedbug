package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Bedrock's `/gametest`, which runs the tests a behaviour pack registered through
 * `@minecraft/server-gametest`.
 *
 * A test is named by `class:name` and runs in an area built from its structure. `run`, `runset` and
 * `runall` take a fresh area beside whoever asked. `runthis` and `runthese` run the test a structure
 * block names, in the area that structure block covers, as Bedrock's test editor does: the nearest
 * one within fifteen blocks, or every one within two hundred. `pos` says where the source stands
 * against the nearest such area, and `create` lays one out: a structure block naming the test, a
 * floor of polished andesite on a dirt base, and a command block with a button that runs it.
 *
 * `runsetuntilfail` runs a set again in the same areas each time every test of it passed, and stops
 * at the first failure; `run` with a repeat count does the same for one test, stopping early only
 * when told to. A test is always built unturned: the runner counts a test's coordinates from the
 * corner of an unturned structure, so a line asking for rotation steps is refused.
 */
public final class GameTestCommand {
    /** How far `runthis` looks for a structure block, and how far `runthese` and `pos` look. */
    private static final int NEAREST = 15;
    private static final int NEARBY = 200;

    /** The largest side `create` lays out, which is the largest a structure block holds in Bedrock. */
    private static final int WIDEST = 48;
    private static final int DEFAULT_SIDE = 5;

    private GameTestCommand() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gametest")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("run")
                .then(Commands.argument("testName", BasicString.basicString())
                    .suggests(GameTestCommand::testNames)
                    .executes(command -> runNamed(command, true, 1))
                    .then(Commands.argument("rotationSteps", IntegerArgumentType.integer())
                        .executes(command -> runNamed(command, true, 1)))
                    .then(Commands.argument("stopOnFailure", BoolArgumentType.bool())
                        .then(Commands.argument("repeatCount", IntegerArgumentType.integer(1))
                            .executes(command -> runNamed(command, BoolArgumentType.getBool(command, "stopOnFailure"),
                                IntegerArgumentType.getInteger(command, "repeatCount")))
                            .then(Commands.argument("rotationSteps", IntegerArgumentType.integer())
                                .executes(command -> runNamed(command,
                                    BoolArgumentType.getBool(command, "stopOnFailure"),
                                    IntegerArgumentType.getInteger(command, "repeatCount"))))))))
            .then(runSet("runset", false))
            .then(runSet("runsetuntilfail", true))
            .then(Commands.literal("runall").executes(command -> start(command, test -> true, Repeat.ONCE)))
            .then(Commands.literal("runthis").executes(command -> runStructureBlocks(command, NEAREST, true)))
            .then(Commands.literal("runthese").executes(command -> runStructureBlocks(command, NEARBY, false)))
            .then(Commands.literal("stopall").executes(command -> stopTests(command.getSource())))
            .then(Commands.literal("clearall").executes(command -> clearTests(command.getSource())))
            .then(Commands.literal("pos").executes(GameTestCommand::position))
            .then(Commands.literal("create")
                .then(Commands.argument("testName", BasicString.basicString())
                    .executes(GameTestCommand::create)
                    .then(Commands.argument("width", IntegerArgumentType.integer(1, WIDEST))
                        .executes(GameTestCommand::create)
                        .then(Commands.argument("height", IntegerArgumentType.integer(1, WIDEST))
                            .executes(GameTestCommand::create)
                            .then(Commands.argument("depth", IntegerArgumentType.integer(1, WIDEST))
                                .executes(GameTestCommand::create)))))));
    }

    /** Bedrock runs the set a test carries by default when the line names no tag. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> runSet(String word,
        boolean untilFail) {
        Repeat repeat = untilFail ? Repeat.UNTIL_FAIL : Repeat.ONCE;
        return Commands.literal(word)
            .executes(command -> start(command, tagged("suite:default"), repeat))
            .then(Commands.argument("tag", BasicString.basicString())
                .executes(command -> start(command, tagged(BasicString.get(command, "tag")), repeat))
                .then(Commands.argument("rotationSteps", IntegerArgumentType.integer())
                    .executes(command -> start(command, tagged(BasicString.get(command, "tag")), repeat))));
    }

    /** How a batch of tests is run again once every test of it has answered. */
    private record Repeat(int times, boolean stopOnFailure, boolean untilFail) {
        static final Repeat ONCE = new Repeat(1, true, false);
        static final Repeat UNTIL_FAIL = new Repeat(1, true, true);

        /** Whether a batch that has answered `failed` and has run `runs` times goes round again. */
        boolean again(boolean failed, int runs) {
            if (failed && stopOnFailure) return false;
            return untilFail || runs < times;
        }
    }

    /** One test in one area, which a repeat builds again where it stood. */
    private record Placed(int index, String structure, BlockPos origin) {
    }

    private static int runNamed(CommandContext<CommandSourceStack> command, boolean stopOnFailure, int times) {
        return start(command, named(BasicString.get(command, "testName")), new Repeat(times, stopOnFailure, false));
    }

    /** Builds an area beside the source for every matching test and starts them all. */
    private static int start(CommandContext<CommandSourceStack> command, Predicate<JsonObject> wanted,
        Repeat repeat) {
        if (refusedTurn(command)) return 0;
        ScriptHost scripts = scripts(command.getSource());
        if (scripts == null) return 0;

        JsonArray tests = JsonParser.parseString(scripts.gameTests()).getAsJsonArray();
        List<Placed> batch = new ArrayList<>();
        List<String> placements = new ArrayList<>();
        for (int i = 0; i < tests.size(); i++) {
            JsonObject test = tests.get(i).getAsJsonObject();
            if (!wanted.test(test)) continue;

            String structure = Json.text(test, "structureName", "");
            String placement = scripts.bridge().gametests.begin(command.getSource(), structure,
                test.get("padding").getAsInt());
            JsonObject at = JsonParser.parseString(placement).getAsJsonObject();
            batch.add(new Placed(i, structure, new BlockPos(at.get("x").getAsInt(), at.get("y").getAsInt(),
                at.get("z").getAsInt())));
            placements.add(placement);
        }
        return launch(command.getSource(), batch, repeat, placements);
    }

    /**
     * Runs the test each structure block in reach names, in the area it covers: the nearest one for
     * `runthis` and every one for `runthese`. A structure block naming no registered test is passed
     * over, as Bedrock passes over one holding an ordinary structure.
     */
    private static int runStructureBlocks(CommandContext<CommandSourceStack> command, int reach, boolean nearestOnly) {
        ScriptHost scripts = scripts(command.getSource());
        if (scripts == null) return 0;

        JsonArray tests = JsonParser.parseString(scripts.gameTests()).getAsJsonArray();
        List<Placed> batch = new ArrayList<>();
        for (StructureBlockEntity block : structureBlocks(command.getSource(), reach)) {
            for (int i = 0; i < tests.size(); i++) {
                JsonObject test = tests.get(i).getAsJsonObject();
                if (!named(block.getStructureName()).test(test)) continue;
                batch.add(new Placed(i, Json.text(test, "structureName", ""),
                    block.getBlockPos().offset(block.getStructurePos())));
                break;
            }
            if (nearestOnly && !batch.isEmpty()) break;
        }
        if (batch.isEmpty()) return failed(command.getSource(), "No structure block within " + reach
            + " blocks names a registered test.");
        return launch(command.getSource(), batch, Repeat.ONCE, null);
    }

    /**
     * Starts every test of the batch, in the areas `built` holds for it or, when that is null, in
     * areas built where each test stands.
     */
    private static int launch(CommandSourceStack source, List<Placed> batch, Repeat repeat, List<String> built) {
        if (batch.isEmpty()) return failed(source, "No loaded pack has registered such a test.");

        new Round(source, batch, repeat).go(built);
        source.sendSuccess(() -> Component.literal("Started " + batch.size() + " test(s)."), false);
        return batch.size();
    }

    /** One batch of tests going round, counting its verdicts until each of them is in. */
    private static final class Round {
        private final CommandSourceStack source;
        private final List<Placed> batch;
        private final Repeat repeat;
        private int runs;
        private int waiting;
        private boolean failed;

        private Round(CommandSourceStack source, List<Placed> batch, Repeat repeat) {
            this.source = source;
            this.batch = batch;
            this.repeat = repeat;
        }

        /** Starts every test of the batch, building each area again where it stood unless given one. */
        private void go(List<String> built) {
            ScriptHost scripts = ServerHooks.scripts();
            if (scripts == null) return;
            runs++;
            waiting = batch.size();
            failed = false;
            for (int i = 0; i < batch.size(); i++) {
                Placed placed = batch.get(i);
                String placement = built != null ? built.get(i)
                    : scripts.bridge().gametests.beginAt(source, placed.structure(), placed.origin());
                int runId = JsonParser.parseString(placement).getAsJsonObject().get("runId").getAsInt();
                scripts.bridge().gametests.listen(runId, this::answered);
                scripts.startGameTest(placed.index(), placement);
            }
        }

        /** The next round starts outside the verdict that ended this one, which the script is still giving. */
        private void answered(boolean passed) {
            failed |= !passed;
            if (--waiting > 0) return;
            if (repeat.again(failed, runs)) source.getServer().execute(() -> go(null));
        }
    }

    /** Where the source stands against the nearest test area a structure block covers. */
    private static int position(CommandContext<CommandSourceStack> command) {
        List<StructureBlockEntity> found = structureBlocks(command.getSource(), NEARBY);
        if (found.isEmpty()) return failed(command.getSource(), "No structure block within " + NEARBY + " blocks.");

        StructureBlockEntity nearest = found.get(0);
        BlockPos origin = nearest.getBlockPos().offset(nearest.getStructurePos());
        BlockPos here = BlockPos.containing(command.getSource().getPosition());
        command.getSource().sendSuccess(() -> Component.literal("Position relative to "
            + nearest.getStructureName() + ": " + (here.getX() - origin.getX()) + ", "
            + (here.getY() - origin.getY()) + ", " + (here.getZ() - origin.getZ())), false);
        return 1;
    }

    /**
     * Lays out a new test three blocks south of the source: a structure block naming the test over
     * an area of the size asked for, the area's floor of polished andesite on three blocks of dirt,
     * and before it a command block running the test from a button on top.
     */
    private static int create(CommandContext<CommandSourceStack> command) {
        Vec3i size = new Vec3i(side(command, "width"), side(command, "height"), side(command, "depth"));
        ServerLevel level = command.getSource().getLevel();
        BlockPos block = BlockPos.containing(command.getSource().getPosition()).offset(0, 0, 3);
        BlockPos origin = block.above();

        for (BlockPos ground : BlockPos.betweenClosed(origin.offset(0, -3, 0),
            origin.offset(size.getX() - 1, -1, size.getZ() - 1))) {
            level.setBlock(ground, Blocks.DIRT.defaultBlockState(), 2);
        }
        for (BlockPos floor : BlockPos.betweenClosed(origin, origin.offset(size.getX() - 1, 0, size.getZ() - 1))) {
            level.setBlock(floor, Blocks.POLISHED_ANDESITE.defaultBlockState(), 2);
        }

        String name = BasicString.get(command, "testName");
        level.setBlock(block, Blocks.STRUCTURE_BLOCK.defaultBlockState(), 2);
        if (level.getBlockEntity(block) instanceof StructureBlockEntity structure) {
            structure.setMode(StructureMode.SAVE);
            structure.setStructureName(name);
            structure.setStructurePos(new BlockPos(0, 1, 0));
            structure.setStructureSize(size);
            structure.setChanged();
        }

        BlockPos trigger = block.offset(1, 0, -1);
        level.setBlock(trigger, Blocks.COMMAND_BLOCK.defaultBlockState(), 2);
        if (level.getBlockEntity(trigger) instanceof CommandBlockEntity commands) {
            commands.getCommandBlock().setCommand("bedrock gametest runthis");
            commands.setChanged();
        }
        level.setBlock(trigger.above(), Blocks.STONE_BUTTON.defaultBlockState()
            .setValue(ButtonBlock.FACE, AttachFace.FLOOR).setValue(ButtonBlock.FACING, Direction.NORTH), 2);

        command.getSource().sendSuccess(() -> Component.literal("Created the test " + name + "."), true);
        return 1;
    }

    private static int side(CommandContext<CommandSourceStack> command, String name) {
        return BedrockCommands.given(command, name) ? IntegerArgumentType.getInteger(command, name) : DEFAULT_SIDE;
    }

    /** The structure blocks in loaded chunks within reach of the source, nearest first. */
    private static List<StructureBlockEntity> structureBlocks(CommandSourceStack source, int reach) {
        ServerLevel level = source.getLevel();
        BlockPos here = BlockPos.containing(source.getPosition());
        int fromX = SectionPos.blockToSectionCoord(here.getX() - reach);
        int toX = SectionPos.blockToSectionCoord(here.getX() + reach);
        int fromZ = SectionPos.blockToSectionCoord(here.getZ() - reach);
        int toZ = SectionPos.blockToSectionCoord(here.getZ() + reach);

        List<StructureBlockEntity> found = new ArrayList<>();
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (entity instanceof StructureBlockEntity structure
                        && structure.getBlockPos().distSqr(here) <= (double) reach * reach) {
                        found.add(structure);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(structure -> structure.getBlockPos().distSqr(here)));
        return found;
    }

    /** A test the name picks out, by its full `class:name` or by its name alone. */
    private static Predicate<JsonObject> named(String wanted) {
        String asked = wanted.trim();
        return test -> asked.equalsIgnoreCase(Json.text(test, "name", ""))
            || asked.equalsIgnoreCase(Json.text(test, "testName", ""));
    }

    private static Predicate<JsonObject> tagged(String wanted) {
        String asked = wanted.trim();
        return test -> test.getAsJsonArray("tags").asList().stream()
            .anyMatch(tag -> asked.equalsIgnoreCase(tag.getAsString()));
    }

    private static boolean refusedTurn(CommandContext<CommandSourceStack> command) {
        if (!BedrockCommands.given(command, "rotationSteps")
            || IntegerArgumentType.getInteger(command, "rotationSteps") % 4 == 0) {
            return false;
        }
        failed(command.getSource(), "A test is built unturned here, so it cannot be run turned.");
        return true;
    }

    /** Ends every running test and leaves the areas standing, which is what a clear takes back. */
    private static int stopTests(CommandSourceStack source) {
        ScriptHost scripts = scripts(source);
        if (scripts == null) return 0;
        scripts.stopGameTests();
        source.sendSuccess(() -> Component.literal("Stopped every running test."), false);
        return 1;
    }

    private static int clearTests(CommandSourceStack source) {
        ScriptHost scripts = scripts(source);
        if (scripts == null) return 0;
        scripts.stopGameTests();
        int cleared = scripts.bridge().gametests.clearAll();
        source.sendSuccess(() -> Component.literal("Cleared " + cleared + " test area(s)."), false);
        return cleared;
    }

    private static ScriptHost scripts(CommandSourceStack source) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) source.sendFailure(Component.literal("No pack scripts are running."));
        return scripts;
    }

    private static CompletableFuture<Suggestions> testNames(CommandContext<CommandSourceStack> command,
        SuggestionsBuilder builder) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return builder.buildFuture();
        JsonParser.parseString(scripts.gameTests()).getAsJsonArray()
            .forEach(test -> builder.suggest(Json.text(test.getAsJsonObject(), "name", "")));
        return builder.buildFuture();
    }

    private static int failed(CommandSourceStack source, String why) {
        source.sendFailure(Component.literal(why));
        return 0;
    }
}
