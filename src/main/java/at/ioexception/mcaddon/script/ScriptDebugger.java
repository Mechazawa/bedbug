package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugException;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.debug.SuspensionFilter;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The script debugger `/script debugger` opens, speaking the protocol of Mojang's Minecraft Bedrock
 * Edition Debugger for Visual Studio Code.
 *
 * The protocol is the one that extension's source defines: framed JSON both ways, Minecraft first
 * announcing its protocol version and its script modules, the extension answering with the module
 * it debugs, and from then on the Debug Adapter Protocol's own requests forwarded one by one. The
 * answers come from Truffle's debugger over the engine every pack's script runs in.
 *
 * A pack's script runs on the server thread, so a breakpoint holds the server thread, as a Bedrock
 * breakpoint holds the game. While it is held, the server thread answers the requests about that
 * moment itself, because a suspended Truffle frame may only be read on the thread it stopped.
 */
public final class ScriptDebugger {
    /** The version of Mojang's protocol this speaks: the newest its extension knows. */
    private static final int PROTOCOL_VERSION = 11;

    public static final int DEFAULT_PORT = 19144;

    /** Bedrock runs each pack's script in one context, which the protocol calls a thread. */
    private static final int THREAD = 1;

    private static final long POLL_MILLIS = 100;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    private static volatile ScriptDebugger current;

    private final MinecraftServer server;
    private final ScriptHost host;
    private final DebuggerSession session;
    private final ServerSocket listener;
    private final ScriptDiagnostics.Collectors collectors = new ScriptDiagnostics.Collectors();
    private final Map<String, List<Breakpoint>> breakpoints = new HashMap<>();
    private final CountDownLatch configured = new CountDownLatch(1);

    private volatile DebugConnection connection;
    private volatile String target;
    private volatile Suspension suspension;
    private Breakpoint exceptions;

    private ScriptDebugger(MinecraftServer server, ScriptHost host, ServerSocket listener) {
        this.server = server;
        this.host = host;
        this.listener = listener;
        this.session = host == null ? null : Debugger.find(host.engine()).startSession(this::suspended);
        if (session != null) {
            session.setSteppingFilter(SuspensionFilter.newBuilder()
                .sourceIs(source -> nameOf(source) != null)
                .build());
        }
    }

    public static ScriptDebugger current() {
        return current;
    }

    /** `/script debugger listen <port>`: waits on the port for the extension to connect. */
    public static ScriptDebugger listen(MinecraftServer server, int port) throws IOException {
        closeCurrent();
        ServerSocket listener = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
        ScriptDebugger debugger = new ScriptDebugger(server, ServerHooks.scripts(), listener);
        current = debugger;
        Thread accepting = new Thread(() -> {
            try {
                Socket socket = listener.accept();
                debugger.closeListener();
                debugger.attach(socket);
            } catch (IOException closed) {
                debugger.closeListener();
            }
        }, "mcaddon-script-debugger-listen");
        accepting.setDaemon(true);
        accepting.start();
        return debugger;
    }

    /** `/script debugger connect [host] [port]`: reaches the extension waiting in its listen mode. */
    public static ScriptDebugger connect(MinecraftServer server, String host, int port) throws IOException {
        closeCurrent();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
        } catch (IOException refused) {
            socket.close();
            throw refused;
        }
        ScriptDebugger debugger = new ScriptDebugger(server, ServerHooks.scripts(), null);
        current = debugger;
        debugger.attach(socket);
        return debugger;
    }

    /** `/script debugger close`, and what a server going away does. Answers whether one was open. */
    public static boolean closeCurrent() {
        ScriptDebugger closing = current;
        current = null;
        if (closing == null) return false;
        closing.close();
        return true;
    }

    /**
     * Holds the scripts after the extension attached until it has set its breakpoints.
     *
     * Bedrock pauses its scripts once a debugger attaches and runs them again on the extension's
     * `resume`, which it sends once its configuration is done, so a breakpoint set before then is
     * in place before the next line of the pack runs.
     */
    static void awaitConfiguration() {
        ScriptDebugger debugger = current;
        if (debugger == null || debugger.connection == null) return;
        try {
            while (debugger.connection != null && debugger.connection.isOpen()
                && !debugger.configured.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                // Waiting is the whole of it.
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean listening() {
        return listener != null && !listener.isClosed();
    }

    public boolean connected() {
        DebugConnection open = connection;
        return open != null && open.isOpen();
    }

    /** Which diagnostics collectors the extension asked for. */
    ScriptDiagnostics.Collectors collectors() {
        return collectors;
    }

    /** Sends one tick's diagnostics to the extension, which draws them as its live diagnostics. */
    void sendStats(JsonObject stats) {
        DebugConnection open = connection;
        if (open != null && configured.getCount() == 0) open.send(event(stats));
    }

    private void attach(Socket socket) throws IOException {
        DebugConnection opened = new DebugConnection(socket);
        connection = opened;

        JsonObject protocol = new JsonObject();
        protocol.addProperty("type", "ProtocolEvent");
        protocol.addProperty("version", PROTOCOL_VERSION);
        JsonArray plugins = new JsonArray();
        if (host != null) {
            host.scripted().stream()
                .filter(pack -> pack.moduleUuid() != null)
                .map(pack -> {
                    JsonObject plugin = new JsonObject();
                    plugin.addProperty("name", pack.pack().displayName());
                    plugin.addProperty("module_uuid", pack.moduleUuid().toString());
                    return plugin;
                })
                .forEach(plugins::add);
        }
        protocol.add("plugins", plugins);
        protocol.addProperty("require_passcode", false);
        opened.send(event(protocol));

        Thread reading = new Thread(() -> read(opened), "mcaddon-script-debugger");
        reading.setDaemon(true);
        reading.start();
    }

    private void read(DebugConnection opened) {
        for (JsonObject message = opened.read(); message != null; message = opened.read()) {
            try {
                receive(message);
            } catch (RuntimeException error) {
                McAddon.LOGGER.warn("The script debugger could not handle a message: {}", error.toString());
            }
        }
        if (current == this) closeCurrent();
    }

    private void receive(JsonObject message) {
        String type = text(message, "type");
        switch (type) {
            case "protocol" -> {
                target = text(message, "target_module_uuid");
                if (target.isEmpty()) target = null;
            }
            case "resume" -> configured.countDown();
            case "stopOnException" -> stopOnException(message.get("stopOnException").getAsBoolean());
            case "minecraftCommand" -> runCommand(text(message, "command"), text(message, "dimension_type"));
            case "startProfiler" -> server.execute(() -> ScriptProfiler.start(host));
            case "stopProfiler" -> server.execute(() -> sendCapture(text(message, "captures_path")));
            case "diagnostics-set-active" -> collectors.set(message.getAsJsonArray("collector_names"),
                message.get("active").getAsBoolean());
            case "request" -> request(message.getAsJsonObject("request"));
            case "debugger-request" -> refuse(message);
            default -> McAddon.LOGGER.debug("The script debugger ignored a message of type {}.", type);
        }
    }

    private void request(JsonObject request) {
        int sequence = request.get("request_seq").getAsInt();
        String command = text(request, "command");
        JsonObject arguments = request.has("args") && request.get("args").isJsonObject()
            ? request.getAsJsonObject("args") : new JsonObject();

        switch (command) {
            case "setBreakpoints" -> respond(sequence, command, setBreakpoints(arguments), null);
            case "pause" -> {
                if (session != null) session.suspendNextExecution();
                respond(sequence, command, new JsonObject(), null);
            }
            case "stackTrace", "scopes", "variables", "evaluate", "continue", "next", "stepIn", "stepOut" -> {
                Suspension stopped = suspension;
                if (stopped == null) {
                    respond(sequence, command, null, "The scripts are not paused.");
                } else {
                    stopped.requests.add(new Request(sequence, command, arguments));
                }
            }
            default -> respond(sequence, command, null, "Minecraft does not answer " + command + ".");
        }
    }

    /**
     * Replaces the breakpoints of one script, by its path inside its pack's `scripts` folder.
     *
     * A breakpoint is verified when a pack running here ships that script, which is the question
     * Bedrock answers too.
     */
    private JsonObject setBreakpoints(JsonObject arguments) {
        String path = normalise(text(arguments, "path"));
        breakpoints.getOrDefault(path, List.of()).forEach(Breakpoint::dispose);
        breakpoints.remove(path);

        List<ScriptHost.ScriptedPack> packs = host == null ? List.of() : host.scripted().stream()
            .filter(pack -> target == null || Objects.equals(String.valueOf(pack.moduleUuid()), target))
            .filter(pack -> Files.isRegularFile(pack.pack().file("scripts/" + path)))
            .toList();

        JsonArray answered = new JsonArray();
        List<Breakpoint> installed = new ArrayList<>();
        for (JsonElement element : arguments.getAsJsonArray("breakpoints")) {
            int line = element.getAsInt();
            for (ScriptHost.ScriptedPack pack : packs) {
                URI uri = Path.of(pack.scriptsRoot() + path).toUri();
                installed.add(session.install(Breakpoint.newBuilder(uri).lineIs(line).build()));
            }
            JsonObject status = new JsonObject();
            status.addProperty("verified", !packs.isEmpty());
            answered.add(status);
        }
        breakpoints.put(path, installed);

        JsonObject body = new JsonObject();
        body.add("breakpoints", answered);
        return body;
    }

    private void stopOnException(boolean stop) {
        if (session == null) return;
        if (exceptions != null) exceptions.dispose();
        exceptions = stop ? session.install(Breakpoint.newExceptionBuilder(false, true).build()) : null;
    }

    private void runCommand(String command, String dimension) {
        server.execute(() -> {
            CommandSourceStack source = server.createCommandSourceStack();
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                Platform.id("minecraft", dimension.isEmpty() ? "overworld" : dimension)));
            if (level != null) source = source.withLevel(level);
            server.getCommands().performPrefixedCommand(source, command);
        });
    }

    /** The profile the extension's own start and stop buttons asked for, handed back to it to save. */
    private void sendCapture(String capturesPath) {
        JsonObject profile = ScriptProfiler.stop();
        if (profile == null) return;
        JsonObject capture = new JsonObject();
        capture.addProperty("type", "ProfilerCapture");
        capture.addProperty("capture_base_path", capturesPath);
        capture.addProperty("capture_data", Base64.getEncoder()
            .encodeToString(profile.toString().getBytes(StandardCharsets.UTF_8)));
        DebugConnection open = connection;
        if (open != null) open.send(event(capture));
    }

    private void refuse(JsonObject message) {
        JsonObject answer = new JsonObject();
        answer.addProperty("type", "debuggee-response");
        answer.addProperty("request_seq", message.get("request_seq").getAsInt());
        answer.addProperty("success", false);
        answer.addProperty("response_message", "Minecraft does not answer " + text(message, "request") + ".");
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", "debuggee-response");
        envelope.add("event", answer);
        DebugConnection open = connection;
        if (open != null) open.send(envelope);
    }

    /**
     * Truffle stops here, on the thread that ran the script, and the thread stays until the
     * extension says how to go on.
     */
    private void suspended(SuspendedEvent event) {
        DebugConnection open = connection;
        if (open == null || !open.isOpen()) return;

        Suspension stopped = new Suspension(event);
        suspension = stopped;
        JsonObject message = new JsonObject();
        message.addProperty("type", "StoppedEvent");
        message.addProperty("reason", reason(event));
        message.addProperty("thread", THREAD);
        open.send(event(message));

        try {
            while (open.isOpen() && !stopped.resumed) {
                Request request = stopped.requests.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (request != null) stopped.answer(request);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            suspension = null;
        }
        if (!stopped.resumed) event.prepareContinue();
    }

    private static String reason(SuspendedEvent event) {
        if (event.getException() != null) return "exception";
        if (!event.getBreakpoints().isEmpty()) return "breakpoint";
        if (event.isStep()) return "step";
        return "pause";
    }

    private void respond(int sequence, String command, JsonElement body, String error) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");
        response.addProperty("request_seq", sequence);
        response.addProperty("command", command);
        if (error != null) {
            response.addProperty("error", error);
        } else {
            response.add("body", body);
        }
        DebugConnection open = connection;
        if (open != null) open.send(response);
    }

    /** The name Bedrock's tools give a script, or null for anything that is not a pack's own. */
    private String nameOf(Source source) {
        if (host == null || source == null) return null;
        URI uri = source.getURI();
        String path = uri == null ? source.getPath() : uri.getPath();
        return host.scripted().stream()
            .map(pack -> pack.nameOf(path))
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    private static String normalise(String path) {
        String forward = path.replace('\\', '/').replaceFirst("^(\\./|/)+", "");
        return forward.startsWith("scripts/") ? forward.substring("scripts/".length()) : forward;
    }

    private static JsonObject event(JsonObject body) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", "event");
        envelope.add("event", body);
        return envelope;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private void closeListener() {
        if (listener == null) return;
        try {
            listener.close();
        } catch (IOException ignored) {
            // Closed already, which is the point.
        }
    }

    private void close() {
        closeListener();
        DebugConnection open = connection;
        connection = null;
        if (open != null) open.close();
        configured.countDown();
        if (session != null) session.close();
    }

    private record Request(int sequence, String command, JsonObject arguments) {
    }

    /**
     * One stop of the scripts, alive until the extension resumes them.
     *
     * Frames, scopes and values are numbered as the extension first asks for them and the numbers
     * mean nothing once the scripts run on, which is how the protocol treats them too.
     */
    private final class Suspension {
        private final SuspendedEvent event;
        private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        private final List<DebugStackFrame> frames = new ArrayList<>();
        private final List<Object> references = new ArrayList<>();
        private volatile boolean resumed;

        private Suspension(SuspendedEvent event) {
            this.event = event;
            for (DebugStackFrame frame : event.getStackFrames()) {
                if (!frame.isHost() && frame.getSourceSection() != null
                    && nameOf(frame.getSourceSection().getSource()) != null) {
                    frames.add(frame);
                }
            }
        }

        private void answer(Request request) {
            try {
                JsonElement body = switch (request.command()) {
                    case "stackTrace" -> stackTrace();
                    case "scopes" -> scopes(frame(request.arguments(), "frameId"));
                    case "variables" -> variables(request.arguments().get("variablesReference").getAsInt());
                    case "evaluate" -> evaluate(request.arguments());
                    default -> resume(request.command());
                };
                respond(request.sequence(), request.command(), body, null);
            } catch (DebugException | IllegalArgumentException | IndexOutOfBoundsException error) {
                respond(request.sequence(), request.command(), null, String.valueOf(error.getMessage()));
            }
        }

        private JsonArray stackTrace() {
            JsonArray body = new JsonArray();
            for (int id = 0; id < frames.size(); id++) {
                DebugStackFrame frame = frames.get(id);
                SourceSection section = frame.getSourceSection();
                JsonObject described = new JsonObject();
                described.addProperty("id", id);
                String name = frame.getName();
                described.addProperty("name", name == null || name.isEmpty() ? "(anonymous)" : name);
                described.addProperty("filename", nameOf(section.getSource()));
                described.addProperty("line", section.getStartLine());
                described.addProperty("column", section.getStartColumn());
                body.add(described);
            }
            return body;
        }

        private DebugStackFrame frame(JsonObject arguments, String key) {
            return arguments.has(key) ? frames.get(arguments.get(key).getAsInt()) : frames.get(0);
        }

        private JsonArray scopes(DebugStackFrame frame) {
            JsonArray body = new JsonArray();
            for (DebugScope scope = frame.getScope(); scope != null; scope = scope.getParent()) {
                JsonObject described = new JsonObject();
                described.addProperty("name", scope.getName());
                described.addProperty("reference", reference(scope));
                described.addProperty("expensive", scope.getParent() == null);
                body.add(described);
            }
            return body;
        }

        private JsonArray variables(int reference) {
            Object held = references.get(reference - 1);
            Iterable<DebugValue> values;
            if (held instanceof DebugScope scope) {
                values = scope.getDeclaredValues();
            } else {
                DebugValue value = (DebugValue) held;
                values = value.isArray() ? value.getArray() : value.getProperties();
            }
            JsonArray body = new JsonArray();
            if (values == null) return body;
            for (DebugValue value : values) {
                if (value.isInternal() || !value.isReadable()) continue;
                JsonObject described = describe(value);
                described.addProperty("name", value.getName());
                body.add(described);
            }
            return body;
        }

        private JsonObject evaluate(JsonObject arguments) {
            DebugValue result = frame(arguments, "frameId").eval(text(arguments, "expression"));
            JsonObject described = describe(result);
            JsonObject body = new JsonObject();
            body.addProperty("result", described.get("value").getAsString());
            body.addProperty("type", described.get("type").getAsString());
            body.addProperty("variablesReference", described.get("variablesReference").getAsInt());
            return body;
        }

        private JsonObject describe(DebugValue value) {
            JsonObject described = new JsonObject();
            described.addProperty("value", value.toDisplayString(false));
            DebugValue meta = value.getMetaObject();
            described.addProperty("type", meta == null ? "" : meta.getMetaSimpleName());
            boolean array = value.isArray();
            boolean expandable = array || value.getProperties() != null && !value.getProperties().isEmpty();
            described.addProperty("variablesReference", expandable ? reference(value) : 0);
            if (array) described.addProperty("indexedVariables", value.getArray().size());
            return described;
        }

        private int reference(Object held) {
            references.add(held);
            return references.size();
        }

        private JsonObject resume(String command) {
            switch (command) {
                case "next" -> event.prepareStepOver(1);
                case "stepIn" -> event.prepareStepInto(1);
                case "stepOut" -> event.prepareStepOut(1);
                default -> event.prepareContinue();
            }
            resumed = true;
            JsonObject body = new JsonObject();
            if (command.equals("continue")) body.addProperty("allThreadsContinued", true);
            return body;
        }
    }
}
