package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.convert.Functions;
import at.ioexception.mcaddon.resource.AddonPacks;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A command line an add-on runs while the game is going, on its way to Java's dispatcher.
 *
 * A script, an entity event, an animation, a dialogue scene and a block all hand over lines in
 * Bedrock's dialect, which is the dialect a function file is written in, so each is translated by
 * the same code a function file is converted with and as the add-on that wrote it: a bare function
 * name reaches the function that add-on ships. The same few lines are run every tick, so a
 * translation is kept per add-on and line.
 *
 * Brigadier raises the same kind of failure for a line it cannot read and one that read fine and
 * then declined, and only the first says this mod is missing something, so running a line reports
 * the two apart.
 */
public final class PackCommand {
    /** Lines kept translated, beyond which the least recently run is translated again when next run. */
    private static final int KEPT = 2048;

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

    /** A line this mod built in Java's dialect, which only needs running and reporting. */
    public static PackCommand java(String line) {
        return new PackCommand(line.startsWith("/") ? line.substring(1) : line, List.of());
    }

    /** What the command returned, and zero for one that could not be run at all. */
    public int run(CommandSourceStack source, CommandReport report) {
        untranslatable.forEach(report::untranslatable);
        if (java == null) return 0;

        CommandSourceStack quiet = source.withSuppressedOutput();
        var dispatcher = source.getServer().getCommands().getDispatcher();
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(java, quiet);
        if (!parsed.getExceptions().isEmpty() || parsed.getReader().canRead()) {
            report.unreadable(java, unreadable(parsed));
            return 0;
        }

        try {
            return dispatcher.execute(parsed);
        } catch (CommandSyntaxException declined) {
            report.declined(java, declined.getMessage());
            return 0;
        }
    }

    /** What Brigadier objected to, or where it stopped reading when it said nothing. */
    private static String unreadable(ParseResults<CommandSourceStack> parsed) {
        return parsed.getExceptions().values().stream().findFirst()
            .map(CommandSyntaxException::getMessage)
            .orElseGet(() -> "unread from position " + parsed.getReader().getCursor());
    }
}
