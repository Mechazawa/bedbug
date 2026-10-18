package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Bedrock's `place featurerule`, which places a feature the way the world places it.
 *
 * A pack's feature rule is converted into a placed feature carrying the rule's distribution and its
 * biome filter, under the rule's own name, so placing that is placing the rule: the feature lands as
 * often and where the distribution says, starting from the position the line names. The biome filter
 * is what decides which biomes the world generates the rule in, and is left out here, so the rule is
 * placed where the line points the way Java's own `place feature` places a feature. Java's own
 * `place` forms sit beside this one in the Bedrock tree.
 */
public final class FeatureRules {
    private FeatureRules() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Java's own `place` forms hang off this node too, so it asks for what Java's `place` asks for,
        // and Bedrock's own form asks for its higher level on top.
        dispatcher.register(Commands.literal("place")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("featurerule")
                .requires(source -> Platform.allowedAt(source, 2))
                .then(Commands.argument("featurerule", IdentifierArgument.id())
                    .executes(command -> place(command, BlockPos.containing(command.getSource().getPosition())))
                    .then(Commands.argument("position", BlockPosArgument.blockPos())
                        .executes(command ->
                            place(command, BlockPosArgument.getLoadedBlockPos(command, "position")))))));
    }

    private static int place(CommandContext<CommandSourceStack> command, BlockPos at) throws CommandSyntaxException {
        Identifier rule = IdentifierArgument.getId(command, "featurerule");
        if (!place(command.getSource().getLevel(), rule, at)) {
            command.getSource().sendFailure(Component.literal("The feature rule " + rule + " placed nothing."));
            return 0;
        }
        command.getSource().sendSuccess(() -> Component.literal("Placed the feature rule " + rule + "."), true);
        return 1;
    }

    /** False when no pack has a rule of that name, or when the rule placed nothing there. */
    public static boolean place(ServerLevel level, Identifier rule, BlockPos at) {
        PlacedFeature feature = Platform.placedFeature(level, rule);
        if (feature == null) return false;

        PlacedFeature anywhere = new PlacedFeature(feature.feature(), feature.placement().stream()
            .filter(modifier -> !(modifier instanceof BiomeFilter))
            .toList());
        return anywhere.place(level, level.getChunkSource().getGenerator(), level.getRandom(), at);
    }
}
