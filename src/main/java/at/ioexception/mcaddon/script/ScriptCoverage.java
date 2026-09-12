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

    void unsupported(String member) {
        count(unsupported, member);
    }

    void missingSound(String sound) {
        count(missingSounds, sound);
    }

    void failedCommand(String command, String reason) {
        failedCommands.putIfAbsent(command.split(" ")[0], reason);
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
        return lines;
    }

    void logReport() {
        report().forEach(McAddon.LOGGER::info);
    }

    private static void count(Map<String, AtomicInteger> into, String key) {
        into.computeIfAbsent(key, name -> new AtomicInteger()).incrementAndGet();
    }
}
