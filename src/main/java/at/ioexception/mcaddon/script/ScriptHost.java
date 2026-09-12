package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.state.AddonState;
import net.minecraft.server.MinecraftServer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs the behaviour packs' scripts for one server.
 *
 * Bedrock scripts are server side, so this exists only while a server is running and a client never
 * builds one. The guest sees the pack's own scripts and the `@minecraft/*` modules and nothing else.
 */
public final class ScriptHost {
    /** How long the queued callbacks of all packs together may take in one tick. */
    private static final long TICK_BUDGET_NANOS = 5_000_000L;

    private static final Map<String, String> MODULES = Map.of(
        "@minecraft/server", "minecraft_server.js",
        "@minecraft/server-ui", "minecraft_server_ui.js");

    private final Context context;
    private final ScriptBridge bridge;
    private final ScriptCoverage coverage;
    private Value runtime;
    private int loaded;

    private ScriptHost(Context context, ScriptBridge bridge, ScriptCoverage coverage) {
        this.context = context;
        this.bridge = bridge;
        this.coverage = coverage;
    }

    /** Null when no loaded pack ships a script, which is the common case. */
    public static ScriptHost start(MinecraftServer server, List<BedrockPack> packs, AddonState state) {
        List<BedrockPack> scripted = packs.stream()
            .filter(pack -> pack.role() == PackRole.BEHAVIOUR && pack.manifest().scriptEntry() != null)
            .toList();
        if (scripted.isEmpty()) return null;

        ScriptFileSystem files = new ScriptFileSystem();
        for (Map.Entry<String, String> module : MODULES.entrySet()) {
            String source = readModule(module.getValue());
            if (source == null) return null;
            files.put(module.getKey(), source);
        }

        ScriptCoverage coverage = new ScriptCoverage();
        ScriptBridge bridge = new ScriptBridge(server, state, coverage);

        Context context = Context.newBuilder("js")
            // Explicit, so only annotated members are reachable, but with array and list access on:
            // without those a Java array arrives in the guest with neither a length nor an index.
            .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .build())
            .allowIO(IOAccess.newBuilder().fileSystem(files).build())
            .option("engine.WarnInterpreterOnly", "false")
            .option("js.esm-eval-returns-exports", "true")
            .build();

        Value globals = context.getBindings("js");
        globals.putMember("__mcaddon_bridge__", bridge);

        ScriptHost host = new ScriptHost(context, bridge, coverage);

        // Importing the module here rather than waiting for a pack to do it: the runtime hook has to
        // exist even for a pack whose script never imports anything.
        try {
            context.eval(Source.newBuilder("js", "import \"@minecraft/server\";\n", "/bootstrap.js")
                .mimeType("application/javascript+module")
                .build());
            host.runtime = globals.getMember("__mcaddon_runtime__");
        } catch (IOException | PolyglotException error) {
            McAddon.LOGGER.error("The script runtime would not load; scripts are off.", error);
        }

        if (host.runtime == null) {
            context.close(true);
            return null;
        }

        for (BedrockPack pack : scripted) host.load(files, pack);
        McAddon.LOGGER.info("Running scripts from {} of {} behaviour pack(s) that ship one.",
            host.loaded, scripted.size());
        return host;
    }

    /**
     * Copies a pack's scripts into the guest file system and evaluates its entry.
     *
     * Everything under `scripts/` comes along rather than only the entry, because the entry imports
     * its siblings by relative path.
     */
    private void load(ScriptFileSystem files, BedrockPack pack) {
        String entry = pack.manifest().scriptEntry();
        String root = "/" + pack.manifest().uuid();

        for (Path script : pack.list("scripts", ".js")) {
            try {
                String relative = pack.root().relativize(script).toString();
                files.put(root + "/" + relative, Files.readAllBytes(script));
            } catch (IOException error) {
                McAddon.LOGGER.error("Could not read {} from {}.", script, pack.displayName(), error);
            }
        }

        String entryPath = root + "/" + entry;
        try {
            String code = Files.readString(pack.file(entry), StandardCharsets.UTF_8);
            context.eval(Source.newBuilder("js", code, entryPath)
                .mimeType("application/javascript+module")
                .build());
            loaded++;
        } catch (IOException error) {
            McAddon.LOGGER.error("{} declares {} as its script entry and it is not readable.",
                pack.displayName(), entry, error);
        } catch (PolyglotException error) {
            McAddon.LOGGER.error("{} failed while loading {}: {}", pack.displayName(), entry, error.getMessage());
        }
    }

    public void tick(int tick) {
        bridge.beginTick(TICK_BUDGET_NANOS);
        try {
            runtime.invokeMember("tick", tick);
        } catch (PolyglotException error) {
            McAddon.LOGGER.error("A script failed during tick {}: {}", tick, error.getMessage());
        }
    }

    /** Raises a `world.afterEvents.<name>` with the fields the caller provides. */
    public void raiseWorldEvent(String name, Map<String, Object> fields) {
        raise("worldAfter", name, fields);
    }

    public void raiseSystemEvent(String name, Map<String, Object> fields) {
        raise("systemAfter", name, fields);
    }

    private void raise(String group, String name, Map<String, Object> fields) {
        try {
            Value signal = runtime.invokeMember(group, name);
            if (signal.getMember("size").asInt() == 0) return;

            Value event = context.eval("js", "({})");
            fields.forEach((key, value) -> event.putMember(key, wrap(value)));
            signal.invokeMember("emit", event);
        } catch (PolyglotException error) {
            McAddon.LOGGER.error("A script failed handling {}: {}", name, error.getMessage());
        }
    }

    /** An entity reaches a script as the wrapper the pack may already have stashed state on. */
    private Object wrap(Object value) {
        return value instanceof EntityHandle handle
            ? runtime.invokeMember("entityFor", handle.uuid())
            : value;
    }

    /** Marks a value as an entity id so it arrives in the script as an Entity and not as a string. */
    public record EntityHandle(String uuid) {
    }

    public ScriptCoverage coverage() {
        return coverage;
    }

    public void stop() {
        coverage.logReport();
        context.close(true);
    }

    private static String readModule(String name) {
        String path = "/assets/" + McAddon.MOD_ID + "/script/" + name;
        try (InputStream stream = ScriptHost.class.getResourceAsStream(path)) {
            if (stream == null) {
                McAddon.LOGGER.error("{} is missing from the mod jar.", path);
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            McAddon.LOGGER.error("Could not read {}.", path, error);
            return null;
        }
    }
}
