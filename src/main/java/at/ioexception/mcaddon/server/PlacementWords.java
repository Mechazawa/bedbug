package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.JigsawSettings;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * The trailing words `place jigsaw` and `place structure` share: `keepJigsaws`, `includeEntities` and
 * a liquid setting. A word the line leaves out takes Bedrock's value, which is false, false and
 * `apply_waterlogging`.
 */
final class PlacementWords {
    @FunctionalInterface
    interface Run {
        int place(CommandContext<CommandSourceStack> command, JigsawSettings settings) throws CommandSyntaxException;
    }

    private PlacementWords() {
    }

    static RequiredArgumentBuilder<CommandSourceStack, Boolean> keepJigsaws(Run run) {
        return Commands.argument("keepJigsaws", BoolArgumentType.bool())
            .requires(source -> Platform.allowedAt(source, 2))
            .executes(command -> run.place(command, new JigsawSettings(keeps(command), false, true)))
            .then(Commands.argument("includeEntities", BoolArgumentType.bool())
                .executes(command -> run.place(command, new JigsawSettings(keeps(command), includes(command), true)))
                .then(liquid("apply_waterlogging", true, run))
                .then(liquid("ignore_waterlogging", false, run)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> liquid(String name, boolean waterlog, Run run) {
        return Commands.literal(name).executes(
            command -> run.place(command, new JigsawSettings(keeps(command), includes(command), waterlog)));
    }

    private static boolean keeps(CommandContext<CommandSourceStack> command) {
        return BoolArgumentType.getBool(command, "keepJigsaws");
    }

    private static boolean includes(CommandContext<CommandSourceStack> command) {
        return BoolArgumentType.getBool(command, "includeEntities");
    }
}
