package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceKeyArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

/**
 * Bedrock's `place jigsaw`, which takes Java's form and three more words after the position: whether
 * the jigsaw blocks stay, whether the entities come along, and whether a block placed into water is
 * waterlogged.
 *
 * Java places a jigsaw's entities with it, so the tree reads only `true` there. The command of this
 * name stands in for Java's own under this tree's `place`, so Java's shorter form is read here too,
 * at the level Java asks for it, and the words Bedrock adds ask for Bedrock's level for `place`.
 */
public final class Jigsaws {
    private Jigsaws() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("place")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("jigsaw")
                .then(Commands.argument("pool", ResourceKeyArgument.key(Registries.TEMPLATE_POOL))
                    .then(Commands.argument("target", IdentifierArgument.id())
                        .then(Commands.argument("max_depth", IntegerArgumentType.integer(1, 20))
                            .executes(command -> place(command, here(command), false, true))
                            .then(Commands.argument("position", BlockPosArgument.blockPos())
                                .executes(command -> place(command, at(command), false, true))
                                .then(Commands.argument("keepJigsaws", BoolArgumentType.bool())
                                    .requires(source -> Platform.allowedAt(source, 2))
                                    .executes(command -> place(command, at(command), keeps(command), true))
                                    .then(Commands.literal("true")
                                        .executes(command -> place(command, at(command), keeps(command), true))
                                        .then(liquid("apply_waterlogging", true))
                                        .then(liquid("ignore_waterlogging", false))))))))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> liquid(String name, boolean waterlog) {
        return Commands.literal(name).executes(command -> place(command, at(command), keeps(command), waterlog));
    }

    private static BlockPos here(CommandContext<CommandSourceStack> command) {
        return BlockPos.containing(command.getSource().getPosition());
    }

    private static BlockPos at(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        return BlockPosArgument.getLoadedBlockPos(command, "position");
    }

    private static boolean keeps(CommandContext<CommandSourceStack> command) {
        return BoolArgumentType.getBool(command, "keepJigsaws");
    }

    private static int place(CommandContext<CommandSourceStack> command, BlockPos at, boolean keepJigsaws,
        boolean waterlog) throws CommandSyntaxException {
        boolean placed = Platform.placeJigsaw(command.getSource().getLevel(),
            ResourceKeyArgument.getStructureTemplatePool(command, "pool"), IdentifierArgument.getId(command, "target"),
            IntegerArgumentType.getInteger(command, "max_depth"), at, keepJigsaws, waterlog);
        if (!placed) {
            command.getSource().sendFailure(
                Component.literal("The jigsaw found nothing to place at " + at.toShortString() + "."));
            return 0;
        }
        command.getSource().sendSuccess(
            () -> Component.literal("Placed a jigsaw at " + at.toShortString() + "."), true);
        return 1;
    }
}
