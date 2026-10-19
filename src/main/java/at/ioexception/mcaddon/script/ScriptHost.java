package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.state.AddonState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs the behaviour packs' scripts for one server.
 *
 * Bedrock scripts are server side, so this exists only while a server is running and a client never
 * builds one. The guest sees the pack's own scripts and the `@minecraft/*` modules and nothing else.
 */
public final class ScriptHost {
    /** How long the queued callbacks of all packs together may take in one tick. */
    private static final long TICK_BUDGET_NANOS = 5_000_000L;

    /**
     * Every module a behaviour pack may import, by the specifier it imports it under.
     *
     * A module is linked before any of it runs, so an import of one that does not resolve ends the
     * pack's whole script rather than the line that asked for it. Every `@minecraft/*` module a
     * pack can name is here for that reason, whether or not this mod can act on what it offers.
     */
    private static final Map<String, String> MODULES = Map.of(
        "@minecraft/server", "minecraft_server.js",
        "@minecraft/server-ui", "minecraft_server_ui.js",
        "@minecraft/common", "minecraft_common.js",
        "@minecraft/math", "minecraft_math.js",
        "@minecraft/vanilla-data", "minecraft_vanilla_data.js",
        "@minecraft/server-gametest", "minecraft_server_gametest.js");

    public static boolean provides(String moduleName) {
        return MODULES.containsKey(moduleName);
    }

    private final MinecraftServer server;
    private final Context context;
    private final List<ScriptedPack> scripted = new ArrayList<>();
    private final ScriptBridge bridge;
    private final ScriptCoverage coverage;

    /** World events raised by a pack's own data, waiting for the next tick to be delivered. */
    private final List<Map.Entry<String, Map<String, Object>>> queued = new ArrayList<>();

    private Value runtime;
    private int loaded;
    private long lastTickNanos;

    private ScriptHost(MinecraftServer server, Context context, ScriptBridge bridge,
        ScriptCoverage coverage) {
        this.server = server;
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
        List<String> namespaces = scripted.stream().map(ActiveAddons.installed()::namespaceOf).distinct().toList();
        ScriptBridge bridge = new ScriptBridge(server, state, coverage,
            namespaces.size() == 1 ? namespaces.get(0) : null);

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
        warmUpErrorReporting(context);

        ScriptHost host = new ScriptHost(server, context, bridge, coverage);

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
     * Makes the engine describe one thrown error while the stack is still shallow.
     *
     * Truffle loads the classes that build a guest stack trace the first time one is asked for. A
     * script error deep in a call chain asks for one at the worst possible moment, and a stack
     * overflow during that class load leaves the class permanently unusable, so every later script
     * error becomes a crash instead of a logged message. Asking once here settles it.
     */
    private static void warmUpErrorReporting(Context context) {
        try {
            context.eval("js", "(function () { throw new TypeError('warm up'); })()");
        } catch (PolyglotException error) {
            error.getStackTrace();
        } catch (RuntimeException error) {
            McAddon.LOGGER.warn("The script engine could not prepare its error reporting.");
        }
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
        scripted.add(new ScriptedPack(pack, root + "/scripts/"));

        for (Path script : pack.list("scripts", ".js")) {
            try {
                String relative = pack.relative(script).toString();
                files.put(root + "/" + relative, Files.readAllBytes(script));
            } catch (IOException error) {
                McAddon.LOGGER.error("Could not read {} from {}.", script, pack.displayName(), error);
            }
        }

        String entryPath = root + "/" + entry;
        try {
            String code = Files.readString(pack.file(entry), StandardCharsets.UTF_8);
            // Named by its file rather than by a string, so a debugger finds it under the same kind of
            // name as the modules it imports, which the file system hands the engine.
            context.eval(Source.newBuilder("js", new java.io.File(entryPath))
                .content(code)
                .mimeType("application/javascript+module")
                .build());
            loaded++;
        } catch (IOException error) {
            McAddon.LOGGER.error("{} declares {} as its script entry and it is not readable.",
                pack.displayName(), entry, error);
        } catch (RuntimeException | Error error) {
            McAddon.LOGGER.error("{} failed while loading {}: {}", pack.displayName(), entry, error.toString());
        }
    }

    /**
     * The one way into the guest, so nothing enters it unguarded or from the wrong thread.
     *
     * A script belongs to the server thread: the engine allows one thread per context and refuses
     * the rest, which leaves the game stuck part way through a tick rather than merely failing. What
     * comes back out is anything at all, including an `Error`, because a guest error deep in a call
     * chain can exhaust the host stack or leave a lazily loaded engine class unusable. Neither is
     * something a pack should be able to do to the game.
     */
    private <T> T enter(String what, Supplier<T> call, T fallback) {
        if (!server.isSameThread()) {
            McAddon.LOGGER.error("Nothing reached the scripts {}: that came from off the server thread.", what);
            return fallback;
        }
        ScriptDebugger.awaitConfiguration();

        try {
            return call.get();
        } catch (RuntimeException | Error error) {
            McAddon.LOGGER.error("A script failed {}: {}", what, error.toString());
            return fallback;
        }
    }

    public void tick(int tick) {
        long started = System.nanoTime();
        bridge.beginTick(TICK_BUDGET_NANOS);
        deliverQueued();
        enter("during tick " + tick, () -> runtime.invokeMember("tick", tick), null);
        lastTickNanos = System.nanoTime() - started;
    }

    /** How long the last tick's queued events and scheduled callbacks took to run. */
    public long lastTickNanos() {
        return lastTickNanos;
    }

    /** Raises `system.beforeEvents.startup` and returns what the pack registered on it, as JSON. */
    public String startup() {
        return enter("during startup", () -> runtime.invokeMember("startup").asString(), null);
    }

    /**
     * Calls one callback of a custom component a pack registered, and says whether it cancelled.
     *
     * `minecraft:custom_components` is how a block or an item hands a moment of its life to a
     * script. The component's name is the pack's own, the callback's name is Bedrock's, and which
     * callback belongs to which moment is decided by the caller rather than here.
     */
    public boolean runBlockComponent(String name, String callback, String argumentsJson) {
        return enter("running a block component", () -> runtime
            .invokeMember("runBlockComponent", name, callback, argumentsJson).asBoolean(), false);
    }

    public boolean runItemComponent(String name, String callback, String argumentsJson) {
        return enter("running an item component", () -> runtime
            .invokeMember("runItemComponent", name, callback, argumentsJson).asBoolean(), false);
    }

    /** Runs a command the pack registered. The outcome carries a status and a message to show. */
    public com.google.gson.JsonObject runCommand(int index, String origin, String arguments) {
        return enter("running a command", () -> {
            String outcome = runtime.invokeMember("runCommand", index, origin, arguments).asString();
            return com.google.gson.JsonParser.parseString(outcome).getAsJsonObject();
        }, new com.google.gson.JsonObject());
    }

    /**
     * The tests the packs registered, as JSON, and an empty list while no pack imports the module.
     *
     * The module is only evaluated when a pack imports it, so the hook it installs stands in for
     * whether any pack ships tests at all.
     */
    public String gameTests() {
        return enter("listing the tests", () -> {
            Value tests = context.getBindings("js").getMember("__mcaddon_gametests__");
            return tests == null ? "[]" : tests.invokeMember("list").asString();
        }, "[]");
    }

    /** Starts one registered test in the area the caller already built for it. */
    public void startGameTest(int index, String placementJson) {
        enter("starting a test", () -> {
            Value tests = context.getBindings("js").getMember("__mcaddon_gametests__");
            if (tests != null) tests.invokeMember("start", index, placementJson);
            return null;
        }, null);
    }

    /** Abandons every test still running, which is what clearing their areas means for the script. */
    public void stopGameTests() {
        enter("stopping the tests", () -> {
            Value tests = context.getBindings("js").getMember("__mcaddon_gametests__");
            if (tests != null) tests.invokeMember("stop");
            return null;
        }, null);
    }

    /**
     * Hands a form's answer back to the promise the pack is waiting on, when it comes from the
     * player the form was shown to. The id is all an answer names, and any client can send any id.
     */
    public void formResponse(ServerPlayer player, int formId, String answer) {
        if (!bridge.shownTo(formId, player)) return;
        enter("handling a form answer", () -> {
            Value forms = context.getBindings("js").getMember("__mcaddon_forms__");
            if (forms != null) forms.invokeMember("respond", formId, answer);
            return null;
        }, null);
    }

    /** Raises a `world.afterEvents.<name>` with the fields the caller provides. */
    public void raiseWorldEvent(String name, Map<String, Object> fields) {
        raise("worldAfter", name, fields);
    }

    public void raiseSystemEvent(String name, Map<String, Object> fields) {
        raise("systemAfter", name, fields);
    }

    /** `system.beforeEvents.<name>`, which is where a pack learns the server is going down. */
    public void raiseSystemBeforeEvent(String name, Map<String, Object> fields) {
        raise("systemBefore", name, fields);
    }

    /**
     * Holds a world event back until the next tick.
     *
     * An after event a pack's own data raised is delivered once the tick's work is done, because a
     * listener that triggers more of the same is ordinary and raising it in place would let that
     * recurse without end.
     */
    public void queueWorldEvent(String name, Map<String, Object> fields) {
        queued.add(Map.entry(name, fields));
    }

    private void deliverQueued() {
        if (queued.isEmpty()) return;

        List<Map.Entry<String, Map<String, Object>>> raising = List.copyOf(queued);
        queued.clear();
        for (Map.Entry<String, Map<String, Object>> event : raising) {
            raise("worldAfter", event.getKey(), event.getValue());
        }
    }

    /**
     * Raises a `world.beforeEvents.<name>` and answers whether a listener called it off.
     *
     * A before event is the one kind a pack can refuse: it sets `cancel` on the event and the game
     * is expected to honour it. The event object has to be read back after the listeners have run,
     * which is what separates this from an after event.
     */
    public boolean raiseCancellable(String name, Map<String, Object> fields) {
        return Boolean.TRUE.equals(enter("handling " + name, () -> {
            Value signal = runtime.invokeMember("worldBefore", name);
            if (signal.getMember("size").asInt() == 0) return false;

            Value event = context.eval("js", "({ cancel: false })");
            fields.forEach((key, value) -> event.putMember(key, wrap(value)));
            signal.invokeMember("emit", event);

            Value cancelled = event.getMember("cancel");
            return cancelled != null && cancelled.isBoolean() && cancelled.asBoolean();
        }, false));
    }

    /**
     * Whether anything is listening on one event, so a caller can decide before building it.
     *
     * Raising already costs nothing when nobody listens, and this is for the few events whose
     * fields are expensive to gather: an explosion names every block it will take.
     */
    public boolean listens(String group, String name) {
        return Boolean.TRUE.equals(enter("counting the listeners on " + name,
            () -> runtime.invokeMember(group, name).getMember("size").asInt() > 0, false));
    }

    private void raise(String group, String name, Map<String, Object> fields) {
        enter("handling " + name, () -> {
            Value signal = runtime.invokeMember(group, name);
            if (signal.getMember("size").asInt() == 0) return null;

            Value event = context.eval("js", "({})");
            fields.forEach((key, value) -> event.putMember(key, wrap(value)));
            signal.invokeMember("emit", event);
            return null;
        }, null);
    }

    /**
     * One field of an event, in the shape the guest expects to find it.
     *
     * An entity arrives as the wrapper the pack may already have stashed state on, and a map arrives
     * as a guest object rather than as a host one: only annotated members of a host object are
     * reachable, so a pack reading a plain field of it would find nothing there. A list is a guest
     * array for the same reason, and because a pack maps and filters what it is handed.
     */
    private Object wrap(Object value) {
        if (value instanceof EntityHandle handle) {
            return runtime.invokeMember("entityFor", handle.uuid(), handle.player());
        }
        if (value instanceof List<?> items) {
            Value array = context.eval("js", "([])");
            for (int i = 0; i < items.size(); i++) array.setArrayElement(i, wrap(items.get(i)));
            return array;
        }
        if (!(value instanceof Map<?, ?> nested)) return value;

        Value object = context.eval("js", "({})");
        nested.forEach((key, inner) -> object.putMember(String.valueOf(key), wrap(inner)));
        return object;
    }

    /**
     * Marks a value as an entity id so it arrives in the script as an Entity and not as a string.
     *
     * An event that can only ever carry somebody playing says so, because the game will not always
     * answer that question in time: a player joining is not in the player list yet while the events
     * about them are being raised, and a script handed a plain entity for a player has none of the
     * player members to call.
     */
    public record EntityHandle(String uuid, boolean player) {
        public EntityHandle(String uuid) {
            this(uuid, false);
        }

        public static EntityHandle of(net.minecraft.world.entity.Entity entity) {
            return new EntityHandle(entity.getStringUUID(),
                entity instanceof net.minecraft.server.level.ServerPlayer);
        }
    }

    /** The bridge a pack's script reaches the game through, for anything that has to ask it too. */
    public ScriptBridge bridge() {
        return bridge;
    }

    public ScriptCoverage coverage() {
        return coverage;
    }

    /** The engine every pack's script runs in, which a debugger or a profiler attaches to. */
    public Engine engine() {
        return context.getEngine();
    }

    /** The packs whose script loaded, in the order they loaded. */
    public List<ScriptedPack> scripted() {
        return List.copyOf(scripted);
    }

    /**
     * A behaviour pack whose script runs here, and the folder its scripts sit in inside the guest
     * file system. Bedrock's script tools name a script by its path inside that folder.
     */
    public record ScriptedPack(BedrockPack pack, String scriptsRoot) {
        /** The name Bedrock's tools give the script at this guest path, or null when it is another pack's. */
        public String nameOf(String guestPath) {
            return guestPath != null && guestPath.startsWith(scriptsRoot)
                ? guestPath.substring(scriptsRoot.length()) : null;
        }

        public UUID moduleUuid() {
            PackManifest.Module module = pack.manifest().scriptModule();
            return module == null ? null : module.uuid();
        }
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
