package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * The per-tick statistics Mojang's debugger draws as its live diagnostics, and the `.mcstats` file
 * `/script diagnostics startcapture` writes them into.
 *
 * Each tick is one `StatEvent2`: the tick number and a tree of named values. The collectors are
 * the ones the debugger's own server tabs read: how long the level and the scripts took, how many
 * entities there are, how many chunks each dimension holds loaded and how much memory the game
 * uses. A capture is the same events one to a line under a header naming their encoding, which is
 * the file the debugger's replay command reads.
 */
public final class ScriptDiagnostics {
    /** Bedrock's own log file names: the weekday, then the date and time, each joined by underscores. */
    public static final DateTimeFormatter LOG_NAME =
        DateTimeFormatter.ofPattern("EEEE'__'yyyy'_'MMMM'_'dd'__'HH'_'mm'_'ss", Locale.ENGLISH);

    private static long tickStartedNanos;
    private static BufferedWriter capture;
    private static Path capturePath;

    private ScriptDiagnostics() {
    }

    public static void beginTick() {
        tickStartedNanos = System.nanoTime();
    }

    /** Hands this tick's statistics to whatever asked for them: a capture, an attached debugger or both. */
    public static void endTick(MinecraftServer server, ScriptHost host) {
        ScriptDebugger debugger = ScriptDebugger.current();
        boolean live = debugger != null && debugger.connected();
        if (capture == null && !live) return;

        JsonObject stats = stats(server, host, live ? debugger.collectors() : null);
        if (live) debugger.sendStats(stats);
        if (capture != null) {
            try {
                capture.write(stats(server, host, null).toString());
                capture.newLine();
            } catch (IOException failed) {
                stopCapture();
            }
        }
    }

    /** `/script diagnostics startcapture`. Answers false when a capture is already running. */
    public static boolean startCapture() throws IOException {
        if (capture != null) return false;
        Path folder = Path.of("logs", "diagnostics");
        Files.createDirectories(folder);
        capturePath = folder.resolve("ScriptDiagnostics__" + LocalDateTime.now().format(LOG_NAME) + ".mcstats");
        capture = Files.newBufferedWriter(capturePath, StandardCharsets.UTF_8);
        JsonObject header = new JsonObject();
        header.addProperty("encoding", "utf8");
        capture.write(header.toString());
        capture.newLine();
        return true;
    }

    /** `/script diagnostics stopcapture`. The file written, or null when no capture was running. */
    public static Path stopCapture() {
        if (capture == null) return null;
        try {
            capture.close();
        } catch (IOException ignored) {
            // What was written before the failure is still the capture.
        }
        capture = null;
        return capturePath;
    }

    private static JsonObject stats(MinecraftServer server, ScriptHost host, Collectors collectors) {
        JsonArray stats = new JsonArray();

        JsonArray timings = new JsonArray();
        timings.add(value("level_tick", (System.nanoTime() - tickStartedNanos) / 1000, "microseconds"));
        timings.add(value("script_tick", host == null ? 0 : host.lastTickNanos() / 1000, "microseconds"));
        add(stats, collectors, group("server_tick_timings", timings));

        long entities = 0;
        JsonArray chunks = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            entities += StreamSupport.stream(level.getAllEntities().spliterator(), false).count();
            JsonArray dimension = new JsonArray();
            dimension.add(value("loaded", level.getChunkSource().getLoadedChunksCount(), null));
            chunks.add(group(Platform.idOf(level.dimension()).toString(), dimension));
        }
        add(stats, collectors, value("entities", entities, null));
        add(stats, collectors, group("chunks", chunks));

        Runtime runtime = Runtime.getRuntime();
        JsonArray memory = new JsonArray();
        memory.add(value("used", runtime.totalMemory() - runtime.freeMemory(), "bytes"));
        memory.add(value("free", runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()), "bytes"));
        add(stats, collectors, group("app_memory", memory));

        JsonObject event = new JsonObject();
        event.addProperty("tick", server.getTickCount());
        event.addProperty("type", "StatEvent2");
        event.add("stats", stats);
        return event;
    }

    private static void add(JsonArray stats, Collectors collectors, JsonObject stat) {
        if (collectors == null || collectors.isActive(stat.get("name").getAsString())) stats.add(stat);
    }

    private static JsonObject group(String name, JsonArray children) {
        JsonObject group = new JsonObject();
        group.addProperty("name", name);
        group.add("children", children);
        return group;
    }

    private static JsonObject value(String name, long value, String type) {
        JsonObject stat = new JsonObject();
        stat.addProperty("name", name);
        JsonArray values = new JsonArray();
        values.add(value);
        stat.add("values", values);
        if (type != null) stat.addProperty("type", type);
        return stat;
    }

    /** The collectors the debugger has switched off, which are left out of what it is sent. */
    static final class Collectors {
        private final Set<String> inactive = new HashSet<>();

        synchronized void set(JsonArray names, boolean active) {
            for (JsonElement name : names) {
                if (active) {
                    inactive.remove(name.getAsString());
                } else {
                    inactive.add(name.getAsString());
                }
            }
        }

        synchronized boolean isActive(String name) {
            return !inactive.contains(name);
        }
    }
}
