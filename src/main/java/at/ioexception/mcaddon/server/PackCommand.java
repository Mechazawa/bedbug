package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.convert.Functions;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.resource.AddonPacks;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One Bedrock line, translated and read against the {@link BedrockTree}.
 *
 * Every Bedrock line reaches the game through here: the ones an operator or a function types after
 * `/bedrock`, and the ones a script, an entity event, an animation, a dialogue scene and a block run.
 * Each is translated by the same code and as the add-on that wrote it, so a bare function name
 * reaches the function that add-on ships; a line with no add-on behind it looks for the function in
 * every add-on alike. The same few lines are run every tick, so a translation is kept per add-on and
 * line.
 *
 * Brigadier raises the same kind of failure for a line it cannot read and one that read fine and
 * then declined, and only the first says this mod is missing something, so running a line reports
 * the two apart.
 */
public final class PackCommand {
    /** Lines kept translated, beyond which the least recently run is translated again when next run. */
    private static final int KEPT = 2048;

    private static final DynamicCommandExceptionType UNTRANSLATABLE = new DynamicCommandExceptionType(
        what -> Component.literal("Java has no form of " + what));

    private static final Map<String, PackCommand> TRANSLATED = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PackCommand> eldest) {
            return size() > KEPT;
        }
    };

    private final String java;
    private final List<String> untranslatable;

    private PackCommand(String java, List<String> untranslatable) {
        this.java = java;
        this.untranslatable = untranslatable;
    }

    /**
     * A Bedrock line as the add-on writing under this namespace wrote it, or null for a blank line
     * or a comment, which run nothing. A null namespace stands for an add-on not told apart from
     * the others, whose function calls are looked for in every add-on alike.
     */
    public static PackCommand tryFrom(String namespace, String line) {
        String stripped = line == null ? "" : line.strip();
        if (stripped.isEmpty() || stripped.startsWith("#")) return null;

        synchronized (TRANSLATED) {
            return TRANSLATED.computeIfAbsent(namespace + " " + stripped, key -> {
                Set<String> unsupported = new LinkedHashSet<>();
                String java = Functions.line(stripped, AddonPacks.functions().issuedBy(namespace), unsupported);
                return new PackCommand(java, List.copyOf(unsupported));
            });
        }
    }

    /** A line this mod built in the words the tree reads, which only needs running and reporting. */
    public static PackCommand java(String line) {
        return new PackCommand(line.startsWith("/") ? line.substring(1) : line, List.of());
    }

    /**
     * What the command returned, and zero for one that could not be run at all.
     *
     * It runs to its end before this returns, with the authority Java gives a function, and says
     * nothing to whoever runs it.
     */
    public int run(CommandSourceStack source, CommandReport report) {
        Heard heard = new Heard();
        Platform.ReadLine read;
        try {
            read = read(Platform.withFunctionPermission(source).withSource(heard), report);
        } catch (CommandSyntaxException reported) {
            return 0;
        }

        try {
            int returned = Platform.runToEnd(read.parsed(), read.line());
            if (heard.failure != null) report.declined(java, heard.failure);
            return returned;
        } catch (CommandSyntaxException declined) {
            report.declined(java, declined.getMessage());
            return 0;
        } finally {
            read.finish();
        }
    }

    /**
     * The line read for this source against the tree, its `hasitem` arguments already answered.
     *
     * What could not be translated or read is reported and thrown, so a source that typed the line
     * is told why nothing ran.
     */
    public Platform.ReadLine read(CommandSourceStack source, CommandReport report) throws CommandSyntaxException {
        untranslatable.forEach(report::untranslatable);
        if (java == null) throw UNTRANSLATABLE.create(String.join("; ", untranslatable));

        CarriedItems carried;
        try {
            carried = CarriedItems.tryFrom(java);
        } catch (CommandSyntaxException unreadable) {
            report.unreadable(java, unreadable.getMessage());
            throw unreadable;
        }

        String line = carried == null ? java : carried.line();
        ParseResults<CommandSourceStack> parsed = BedrockTree.of(source.getServer()).parse(line, source);
        if (!parsed.getExceptions().isEmpty() || parsed.getReader().canRead()) {
            CommandSyntaxException unreadable = unreadable(parsed);
            report.unreadable(java, unreadable.getMessage());
            throw unreadable;
        }

        if (carried != null) carried.tag(source.getServer());
        return new Read(parsed, line, carried);
    }

    private record Read(ParseResults<CommandSourceStack> parsed, String line, CarriedItems carried)
        implements Platform.ReadLine {
        @Override
        public void finish() {
            if (carried != null) carried.finish();
        }
    }

    /** Keeps the first failure a command reports and drops everything else it says. */
    private static final class Heard implements CommandSource {
        private String failure;

        @Override
        public void sendSystemMessage(Component message) {
            if (failure == null) failure = message.getString();
        }

        @Override
        public boolean acceptsSuccess() {
            return false;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    /** What Brigadier objected to, or where it stopped reading when it said nothing. */
    private static CommandSyntaxException unreadable(ParseResults<CommandSourceStack> parsed) {
        return parsed.getExceptions().values().stream().findFirst()
            .orElseGet(() -> CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
                .createWithContext(parsed.getReader()));
    }
}
