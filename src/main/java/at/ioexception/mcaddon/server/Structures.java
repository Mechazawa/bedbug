package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.JigsawSettings;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceKeyArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

/**
 * The words Bedrock's `place structure` adds after the position: `ignoreStartHeight`, `keepJigsaws`,
 * `includeEntities` and a liquid setting. Bedrock names only jigsaw structures there, and a jigsaw
 * structure is the one kind Java builds from a start pool the placement can be handed settings for,
 * so any other structure refuses these words while Java's own `place structure <structure> [pos]`
 * places it as it always did.
 *
 * Java's two shorter forms are not registered here. {@link BedrockTree} hangs them under this node.
 */
public final class Structures {
    private Structures() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("place")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("structure")
                .then(Commands.argument("structure", ResourceKeyArgument.key(Registries.STRUCTURE))
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("ignoreStartHeight", BoolArgumentType.bool())
                            .requires(source -> Platform.allowedAt(source, 2))
                            .executes(command -> place(command, JigsawSettings.BEDROCK))
                            .then(PlacementWords.keepJigsaws(Structures::place)))))));
    }

    private static int place(CommandContext<CommandSourceStack> command, JigsawSettings settings)
        throws CommandSyntaxException {
        var holder = ResourceKeyArgument.getStructure(command, "structure");
        BlockPos at = BlockPosArgument.getLoadedBlockPos(command, "pos");
        if (!(holder.value() instanceof JigsawStructure jigsaw)) {
            command.getSource().sendFailure(Component.literal(Platform.idOf(holder.key())
                + " is not a jigsaw structure, so it takes none of the placement words after the position."));
            return 0;
        }

        boolean ignoreStartHeight = BoolArgumentType.getBool(command, "ignoreStartHeight");
        if (!Platform.placeStructure(command.getSource().getLevel(), jigsaw, at, ignoreStartHeight, settings)) {
            command.getSource().sendFailure(
                Component.literal("The structure found nothing to place at " + at.toShortString() + "."));
            return 0;
        }
        command.getSource().sendSuccess(
            () -> Component.literal("Placed " + Platform.idOf(holder.key()) + " at " + at.toShortString() + "."), true);
        return 1;
    }
}
