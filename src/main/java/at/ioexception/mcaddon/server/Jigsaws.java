package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.JigsawSettings;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 * The command of this name stands in for Java's own under this tree's `place`, so Java's shorter form
 * is read here too, at the level Java asks for it, with Java's own behaviour. The words Bedrock adds
 * ask for Bedrock's level for `place` and take Bedrock's value for a word the line leaves out.
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
                            .executes(command -> place(command, here(command), JigsawSettings.JAVA))
                            .then(Commands.argument("position", BlockPosArgument.blockPos())
                                .executes(command -> place(command, at(command), JigsawSettings.JAVA))
                                .then(PlacementWords.keepJigsaws(Jigsaws::place))))))));
    }

    private static BlockPos here(CommandContext<CommandSourceStack> command) {
        return BlockPos.containing(command.getSource().getPosition());
    }

    private static int place(CommandContext<CommandSourceStack> command, JigsawSettings settings)
        throws CommandSyntaxException {
        return place(command, at(command), settings);
    }

    private static BlockPos at(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        return BlockPosArgument.getLoadedBlockPos(command, "position");
    }

    private static int place(CommandContext<CommandSourceStack> command, BlockPos at, JigsawSettings settings)
        throws CommandSyntaxException {
        boolean placed = Platform.placeJigsaw(command.getSource().getLevel(),
            ResourceKeyArgument.getStructureTemplatePool(command, "pool"), IdentifierArgument.getId(command, "target"),
            IntegerArgumentType.getInteger(command, "max_depth"), at, settings);
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
