package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.ExecuteCommand;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * The commands a Bedrock line is read against once it is translated.
 *
 * Java's own tree stays as Java builds it, with `/bedrock` beside its commands: that takes a whole
 * Bedrock line as text and reads it here. This tree holds the commands Bedrock has and Java does
 * not, the commands a pack's script declares, and every command of Java's own, so a translated line
 * may name any of them, while a Java data pack's line can name none of the first two. Its `execute`
 * is built against this tree, so the command behind a `run` is found here as well, and where both
 * editions have a command of one name, such as `particle`, this tree holds Bedrock's.
 */
public final class BedrockTree {
    /** One per Java tree, which the game builds again on every data pack reload. */
    private static final Map<CommandDispatcher<CommandSourceStack>, BedrockTree> BY_JAVA_TREE =
        Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Commands Bedrock and Java share a name and some forms of, where Bedrock adds forms of its own:
     * `place featurerule` beside Java's `place`, `schedule on_area_loaded` beside Java's `schedule
     * function`, and the rules Java keeps none of beside Java's own in `gamerule`. This tree's command
     * of each name carries Java's forms as well.
     */
    private static final List<String> SHARED = List.of("place", "schedule", "gamerule");

    private final CommandDispatcher<CommandSourceStack> bedrock = new CommandDispatcher<>();
    private boolean holdsJava;

    private BedrockTree() {
    }

    /** Builds the tree for this Java tree and puts `/bedrock` into the Java one. */
    public static void register(CommandDispatcher<CommandSourceStack> java, CommandBuildContext context) {
        BedrockTree tree = new BedrockTree();
        BedrockCommands.register(tree.bedrock);
        SHARED.forEach(name -> tree.alongsideJava(name, java));
        ExecuteCommand.register(tree.bedrock, context);
        BY_JAVA_TREE.put(java, tree);

        // Two is the level Java runs a function at, so a data pack's function may run a Bedrock
        // line, and each command the line names asks for its own level on top of that.
        java.register(Commands.literal("bedrock")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("line", StringArgumentType.greedyString())
                .suggests(BedrockTree::suggest)
                .executes(Platform.runningLine((command, source) -> {
                    PackCommand line = PackCommand.tryFrom(null, StringArgumentType.getString(command, "line"));
                    return line == null ? null : line.read(source, CommandReport.DATA);
                }))));
    }

    /** Hangs the forms Java's command of this name has under this tree's command of the same name. */
    private void alongsideJava(String name, CommandDispatcher<CommandSourceStack> java) {
        CommandNode<CommandSourceStack> ours = bedrock.getRoot().getChild(name);
        CommandNode<CommandSourceStack> javas = java.getRoot().getChild(name);
        if (ours != null && javas != null) javas.getChildren().forEach(ours::addChild);
    }

    /** The tree a translated line is read against on this server. */
    public static CommandDispatcher<CommandSourceStack> of(MinecraftServer server) {
        CommandDispatcher<CommandSourceStack> java = server.getCommands().getDispatcher();
        BedrockTree tree = BY_JAVA_TREE.get(java);
        if (tree == null) throw new IllegalStateException("No Bedrock command tree was built beside Java's.");
        return tree.holding(java);
    }

    /**
     * This tree with every command of Java's beside Bedrock's own.
     *
     * Java's are added the first time a line is read rather than as the trees are built, because
     * other mods register theirs into Java's tree after this mod has.
     */
    private synchronized CommandDispatcher<CommandSourceStack> holding(CommandDispatcher<CommandSourceStack> java) {
        if (holdsJava) return bedrock;
        holdsJava = true;

        for (CommandNode<CommandSourceStack> command : java.getRoot().getChildren()) {
            if (command.getName().equals("bedrock") || bedrock.getRoot().getChild(command.getName()) != null) continue;
            bedrock.getRoot().addChild(command);
        }
        return bedrock;
    }

    /** What comes next in a Bedrock line, in the words this tree knows. */
    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> command,
        SuggestionsBuilder builder) {
        CommandDispatcher<CommandSourceStack> tree = of(command.getSource().getServer());
        return tree.getCompletionSuggestions(tree.parse(builder.getRemaining(), command.getSource()))
            .thenApply(found -> {
                SuggestionsBuilder shifted = builder.createOffset(builder.getStart() + found.getRange().getStart());
                found.getList().forEach(suggestion -> shifted.suggest(suggestion.getText(), suggestion.getTooltip()));
                return shifted.build();
            });
    }
}
