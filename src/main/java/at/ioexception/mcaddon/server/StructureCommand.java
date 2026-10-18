package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.structure.McStructure;
import at.ioexception.mcaddon.structure.StructurePlacement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

/**
 * Bedrock's `/structure`, which saves a region under a name, places it again and forgets it.
 *
 * Java has the same three operations behind a structure block and `/place template` and no command
 * naming them, so the whole of Bedrock's form is read here. A structure saved to `memory`, which is
 * Bedrock's default, stays in the world's structure manager and is never written out, so it lasts as
 * long as the server does; `disk` writes it beside the world as Java's own structure block does.
 */
public final class StructureCommand {
    private static final Map<String, Rotation> ROTATIONS = Map.of(
        "0_degrees", Rotation.NONE,
        "90_degrees", Rotation.CLOCKWISE_90,
        "180_degrees", Rotation.CLOCKWISE_180,
        "270_degrees", Rotation.COUNTERCLOCKWISE_90);

    /**
     * Java names the pair of faces a mirror swaps where Bedrock names the axis it runs along, so
     * mirroring x swaps east for west and mirroring z swaps north for south. `xz` swaps both, which
     * is a half turn and is placed as one.
     */
    private static final Map<String, Mirror> MIRRORS = Map.of(
        "none", Mirror.NONE,
        "x", Mirror.FRONT_BACK,
        "z", Mirror.LEFT_RIGHT,
        "xz", Mirror.NONE);

    private static final Map<String, StructurePlacement.Animation> ANIMATIONS = Map.of(
        "block_by_block", StructurePlacement.Animation.BLOCK_BY_BLOCK,
        "layer_by_layer", StructurePlacement.Animation.LAYER_BY_LAYER);

    private StructureCommand() {
    }

    /**
     * `structure save <name> <from> <to> [includeEntities] [disk|memory] [includeBlocks]`, `structure
     * load <name> <to> [rotation] [mirror] [animation seconds] [includeEntities] [includeBlocks]
     * [waterlogged] [integrity] [seed]` and `structure delete <name>`, every part after the positions
     * optional from the end.
     */
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> seed = Commands.argument("seed", BasicString.basicString())
            .executes(StructureCommand::load).build();
        CommandNode<CommandSourceStack> integrity = Commands.argument("integrity", FloatArgumentType.floatArg(0, 100))
            .executes(StructureCommand::load).then(seed).build();
        CommandNode<CommandSourceStack> waterlogged = Commands.argument("waterlogged", BoolArgumentType.bool())
            .executes(StructureCommand::load).then(integrity).build();
        CommandNode<CommandSourceStack> includeBlocks = Commands.argument("includeBlocks", BoolArgumentType.bool())
            .executes(StructureCommand::load).then(waterlogged).build();
        CommandNode<CommandSourceStack> includeEntities = Commands.argument("includeEntities", BoolArgumentType.bool())
            .executes(StructureCommand::load).then(includeBlocks).build();

        CommandNode<CommandSourceStack>[] mirrorNodes = MIRRORS.keySet().stream().map(name -> {
            LiteralArgumentBuilder<CommandSourceStack> mirror = Commands.literal(name)
                .executes(StructureCommand::load).then(includeEntities);
            ANIMATIONS.keySet().forEach(animation -> mirror.then(Commands.literal(animation)
                .executes(StructureCommand::load)
                .then(Commands.argument("animationSeconds", FloatArgumentType.floatArg(0))
                    .executes(StructureCommand::load).then(includeEntities))));
            return mirror.build();
        }).toArray(CommandNode[]::new);

        var to = Commands.argument("to", BlockPosArgument.blockPos()).executes(StructureCommand::load);
        ROTATIONS.keySet().forEach(name -> {
            LiteralArgumentBuilder<CommandSourceStack> rotation =
                Commands.literal(name).executes(StructureCommand::load);
            for (CommandNode<CommandSourceStack> mirror : mirrorNodes) rotation.then(mirror);
            to.then(rotation);
        });

        CommandNode<CommandSourceStack> savedBlocks = Commands.argument("includeBlocks", BoolArgumentType.bool())
            .executes(StructureCommand::save).build();
        var corners = Commands.argument("to", BlockPosArgument.blockPos()).executes(StructureCommand::save)
            .then(Commands.literal("disk").executes(StructureCommand::save))
            .then(Commands.literal("memory").executes(StructureCommand::save))
            .then(Commands.argument("includeEntities", BoolArgumentType.bool())
                .executes(StructureCommand::save)
                .then(Commands.literal("disk").executes(StructureCommand::save).then(savedBlocks))
                .then(Commands.literal("memory").executes(StructureCommand::save).then(savedBlocks)));

        dispatcher.register(Commands.literal("structure")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("from", BlockPosArgument.blockPos()).then(corners))))
            .then(Commands.literal("load")
                .then(Commands.argument("name", StringArgumentType.string()).then(to)))
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(StructureCommand::delete))));
    }

    /**
     * The region between the two corners, kept under the name the pack gave it. Bedrock keeps the
     * entities and the blocks unless told otherwise; without its blocks the structure keeps its size
     * and places only what stood in it.
     */
    private static int save(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        Identifier id = id(command);
        if (id == null) return failed(command, "That is not a usable structure name.");

        BlockPos from = BlockPosArgument.getLoadedBlockPos(command, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(command, "to");
        BlockPos corner = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
            Math.min(from.getZ(), to.getZ()));
        Vec3i size = new Vec3i(Math.abs(from.getX() - to.getX()) + 1,
            Math.abs(from.getY() - to.getY()) + 1, Math.abs(from.getZ() - to.getZ()) + 1);

        StructureTemplateManager manager = command.getSource().getServer().getStructureManager();
        StructureTemplate template = manager.getOrCreate(id);
        Platform.fillFromWorld(template, command.getSource().getLevel(), corner, size,
            flag(command, "includeEntities", true));
        if (!flag(command, "includeBlocks", true)) {
            CompoundTag saved = template.save(new CompoundTag());
            saved.put("blocks", new ListTag());
            Platform.loadTemplate(template, saved);
        }
        if (typed(command, "disk") && !manager.save(id)) return failed(command, "The structure could not be written.");

        command.getSource().sendSuccess(() -> Component.literal("Saved the structure " + id + "."), true);
        return 1;
    }

    private static int load(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        Identifier id = id(command);
        Optional<StructureTemplate> template = id == null ? Optional.empty()
            : command.getSource().getServer().getStructureManager().get(id);
        if (template.isEmpty()) return failed(command, "There is no structure called " + id + ".");

        Rotation rotation = picked(command, ROTATIONS, Rotation.NONE);
        if (typed(command, "xz")) rotation = rotation.getRotated(Rotation.CLOCKWISE_180);
        StructurePlacement placement = new StructurePlacement(rotation, picked(command, MIRRORS, Mirror.NONE),
            flag(command, "includeEntities", true), flag(command, "includeBlocks", true),
            flag(command, "waterlogged", false),
            BedrockCommands.given(command, "integrity") ? FloatArgumentType.getFloat(command, "integrity") / 100f : 1f,
            seed(command), picked(command, ANIMATIONS, StructurePlacement.Animation.NONE),
            BedrockCommands.given(command, "animationSeconds")
                ? FloatArgumentType.getFloat(command, "animationSeconds") : 0f);

        placement.place(command.getSource().getLevel(), template.get(),
            BlockPosArgument.getLoadedBlockPos(command, "to"));
        command.getSource().sendSuccess(() -> Component.literal("Loaded the structure " + id + "."), true);
        return 1;
    }

    /**
     * Forgets a structure the world saved under that name and deletes its file, which is what
     * Bedrock's own delete does. One a data pack ships is the pack's and comes back with it.
     */
    private static int delete(CommandContext<CommandSourceStack> command) {
        Identifier id = id(command);
        if (id == null) return failed(command, "That is not a usable structure name.");

        StructureTemplateManager manager = command.getSource().getServer().getStructureManager();
        if (manager.get(id).isEmpty()) return failed(command, "There is no structure called " + id + ".");
        manager.remove(id);
        try {
            Files.deleteIfExists(Platform.savedStructureFile(manager, id));
        } catch (IOException | RuntimeException error) {
            return failed(command, "The structure's file could not be deleted: " + error.getMessage());
        }
        command.getSource().sendSuccess(() -> Component.literal("Deleted the structure " + id + "."), true);
        return 1;
    }

    private static Identifier id(CommandContext<CommandSourceStack> command) {
        return Identifier.tryParse(McStructure.javaName(StringArgumentType.getString(command, "name")));
    }

    /**
     * Bedrock's seed is a string. A number is read as the number; any other text stands for the
     * number its characters hash to, and no seed at all picks one at random.
     */
    private static long seed(CommandContext<CommandSourceStack> command) {
        if (!BedrockCommands.given(command, "seed")) return command.getSource().getLevel().getRandom().nextLong();
        String written = BasicString.get(command, "seed");
        try {
            return Long.parseLong(written);
        } catch (NumberFormatException words) {
            return written.hashCode();
        }
    }

    private static boolean flag(CommandContext<CommandSourceStack> command, String name, boolean otherwise) {
        return BedrockCommands.given(command, name) ? BoolArgumentType.getBool(command, name) : otherwise;
    }

    /** The value of whichever of the literals in the table the line typed. */
    private static <T> T picked(CommandContext<CommandSourceStack> command, Map<String, T> table, T otherwise) {
        for (ParsedCommandNode<CommandSourceStack> node : command.getNodes()) {
            T value = table.get(node.getNode().getName());
            if (value != null) return value;
        }
        return otherwise;
    }

    private static boolean typed(CommandContext<CommandSourceStack> command, String literal) {
        return BedrockCommands.given(command, literal);
    }

    private static int failed(CommandContext<CommandSourceStack> command, String why) {
        command.getSource().sendFailure(Component.literal(why));
        return 0;
    }
}
