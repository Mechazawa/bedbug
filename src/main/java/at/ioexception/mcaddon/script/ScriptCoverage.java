package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.McAddon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a pack asked for that this mod does not do.
 *
 * A loader that silently does nothing for half a pack is worse than one that says which half, so
 * every unimplemented script member, every sound a pack names that does not exist and every command
 * that would not parse is counted here and reported rather than thrown.
 */
public final class ScriptCoverage {
    private final Map<String, AtomicInteger> unsupported = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> missingSounds = new LinkedHashMap<>();
    private final Map<String, String> failedCommands = new LinkedHashMap<>();
    private final Map<String, String> refusedCommands = new LinkedHashMap<>();

    public void unsupported(String member) {
        count(unsupported, member);
    }

    void missingSound(String sound) {
        count(missingSounds, sound);
    }

    void failedCommand(String command, String reason) {
        failedCommands.putIfAbsent(command.split(" ")[0], reason);
    }

    /** A command the game read and then declined, which says nothing about what this mod supports. */
    void refusedCommand(String command, String reason) {
        refusedCommands.putIfAbsent(command.split(" ")[0], reason);
    }

    public List<String> report() {
        List<String> lines = new ArrayList<>();
        if (!unsupported.isEmpty()) {
            lines.add("Script members the packs used and this mod does not implement:");
            unsupported.forEach((member, calls) -> lines.add("    " + member + " x" + calls.get()));
        }
        if (!missingSounds.isEmpty()) {
            lines.add("Sounds the packs played that are not registered:");
            missingSounds.forEach((sound, plays) -> lines.add("    " + sound + " x" + plays.get()));
        }
        if (!failedCommands.isEmpty()) {
            lines.add("Commands the packs ran that did not parse:");
            failedCommands.forEach((command, reason) -> lines.add("    " + command + ": " + reason));
        }
        if (lines.isEmpty()) lines.add("Everything the packs asked for was implemented.");

        // Listed after the verdict rather than among the gaps: a command the game read and then
        // declined is the game answering, and counting it would make a pack that asks about an
        // empty chunk read as a pack this mod cannot serve.
        if (!refusedCommands.isEmpty()) {
            lines.add("Commands the packs ran that the game declined:");
            refusedCommands.forEach((command, reason) -> lines.add("    " + command + ": " + reason));
        }
        return lines;
    }

    void logReport() {
        report().forEach(McAddon.LOGGER::info);
    }

    private static void count(Map<String, AtomicInteger> into, String key) {
        into.computeIfAbsent(key, name -> new AtomicInteger()).incrementAndGet();
    }
}
