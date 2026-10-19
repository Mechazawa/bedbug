package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * `/script`, driven the way Bedrock's own tools drive it: a profile written and read back, and a
 * debugger client that speaks the protocol of Mojang's Visual Studio Code extension.
 *
 * Both run the test pack's `tp:weigh` probe, which sums square roots in `scripts/weigh.js` and is
 * called from `scripts/main.js`. With no pack script loaded each test succeeds without asserting.
 */
public final class ScriptToolGameTests {
    /** The marker on the line of `weigh.js` the debugger test stops on. */
    private static final String MARKER = "tp:weigh breakpoint";

    /**
     * A profile names the function the scripts spent their time in, by the script it is written in.
     *
     * The probe runs long enough for the sampler to land in it many times, so a profile with no
     * sample in `weighTheProbe` of `weigh.js` is one that did not record the pack.
     */
    @GameTest(maxTicks = 200)
    public void aProfileNamesTheFunctionAScriptSpentItsTimeIn(GameTestHelper helper) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }
        MinecraftServer server = helper.getLevel().getServer();
        long started = System.currentTimeMillis();
        console(server, "bedrock script profiler start");
        console(server, "bedrock scriptevent tp:weigh 400000");
        helper.runAfterDelay(5, () -> {
            console(server, "bedrock script profiler stop");
            JsonObject profile = JsonParser.parseString(String.join("\n",
                newestLines(helper, Path.of("logs", "profiles"), ".cpuprofile", started))).getAsJsonObject();

            JsonArray nodes = profile.getAsJsonArray("nodes");
            JsonObject weighed = null;
            for (JsonElement element : nodes) {
                JsonObject frame = element.getAsJsonObject().getAsJsonObject("callFrame");
                if (frame.get("functionName").getAsString().equals("weighTheProbe")
                    && frame.get("url").getAsString().equals("weigh.js")) {
                    weighed = element.getAsJsonObject();
                }
            }
            helper.assertTrue(weighed != null, "No node of the profile is weighTheProbe in weigh.js: " + nodes);
            int id = weighed.get("id").getAsInt();
            long sampled = profile.getAsJsonArray("samples").asList().stream()
                .filter(sample -> sample.getAsInt() == id).count();
            helper.assertTrue(sampled > 0 && sampled == weighed.get("hitCount").getAsInt(),
                "weighTheProbe was sampled " + sampled + " times against a hit count of " + weighed.get("hitCount"));
            helper.assertTrue(profile.getAsJsonArray("samples").size() == profile.getAsJsonArray("timeDeltas").size(),
                "A profile's samples and time deltas differ in number");
            long elapsed = profile.getAsJsonArray("timeDeltas").asList().stream()
                .mapToLong(JsonElement::getAsLong).sum();
            long span = profile.get("endTime").getAsLong() - profile.get("startTime").getAsLong();
            helper.assertTrue(elapsed > 0 && elapsed <= span,
                "The samples cover " + elapsed + " microseconds of a profile " + span + " long");
            helper.assertTrue(profile.getAsJsonObject("$vscode").getAsJsonArray("locations").size() == nodes.size(),
                "The $vscode locations do not name every node");
            helper.succeed();
        });
    }

    /**
     * A debugger client set on the protocol of Mojang's extension stops on a breakpoint and reads
     * the stack it stopped in.
     *
     * The client attaches to `/script debugger listen`, chooses the test pack's script module,
     * sets a breakpoint on the marked line of `weigh.js` by that script's path inside the pack's
     * `scripts` folder, and resumes the scripts the attach held. The probe then stops there: the
     * stopped event names a breakpoint, and the stack trace has the probe on top at that line and
     * the listener in `main.js` that called it underneath.
     */
    @GameTest(maxTicks = 400)
    public void aDebuggerStopsAtABreakpointAndReadsTheStack(GameTestHelper helper) {
        ScriptHost scripts = ServerHooks.scripts();
        ScriptHost.ScriptedPack pack = scripts == null ? null : scripts.scripted().stream()
            .filter(scripted -> Files.isRegularFile(scripted.pack().file("scripts/weigh.js")))
            .findFirst().orElse(null);
        if (pack == null) {
            helper.succeed();
            return;
        }

        int line = markedLine(helper, pack.pack().file("scripts/weigh.js"));
        int port = freePort(helper);
        MinecraftServer server = helper.getLevel().getServer();
        console(server, "bedrock script debugger listen " + port);

        Client client = new Client(port, pack.moduleUuid().toString(), line);
        Thread thread = new Thread(client::run, "mcaddon-test-debugger-client");
        thread.setDaemon(true);
        thread.start();

        boolean[] fired = {false};
        helper.succeedWhen(() -> {
            if (!fired[0] && client.configured) {
                fired[0] = true;
                console(server, "bedrock scriptevent tp:weigh 3");
            }
            helper.assertTrue(client.done, "The debugger client has not finished");
            helper.assertTrue(client.failure.get() == null, "The debugger client failed: " + client.failure.get());
            helper.assertTrue(client.plugins.contains(pack.moduleUuid().toString()),
                "The protocol event named no module " + pack.moduleUuid() + ": " + client.plugins);
            helper.assertTrue(client.verified, "The breakpoint on weigh.js was not verified");
            helper.assertTrue("breakpoint".equals(client.reason), "Stopped for " + client.reason);

            JsonArray frames = client.frames;
            helper.assertTrue(frames != null && frames.size() >= 2, "The stack is " + frames);
            JsonObject top = frames.get(0).getAsJsonObject();
            helper.assertTrue(top.get("name").getAsString().equals("weighTheProbe")
                    && top.get("filename").getAsString().equals("weigh.js")
                    && top.get("line").getAsInt() == line,
                "The top frame is " + top + " where weighTheProbe in weigh.js at line " + line + " was expected");
            helper.assertTrue(frames.get(1).getAsJsonObject().get("filename").getAsString().equals("main.js"),
                "The probe's caller is " + frames.get(1));
            console(server, "bedrock script debugger close");
        });
    }

    /**
     * A diagnostics capture is the replay file Mojang's debugger reads: a header naming the
     * encoding, then one `StatEvent2` a tick, ticks in order, each carrying the tick timings and the
     * entity count the debugger's server tabs draw.
     */
    @GameTest(maxTicks = 100)
    public void aDiagnosticsCaptureWritesOneStatEventATick(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        long started = System.currentTimeMillis();
        console(server, "bedrock script diagnostics startcapture");
        helper.runAfterDelay(5, () -> {
            console(server, "bedrock script diagnostics stopcapture");
            List<String> lines = newestLines(helper, Path.of("logs", "diagnostics"), ".mcstats", started);
            helper.assertTrue(lines.size() >= 5, "The capture holds " + lines.size() + " lines");
            helper.assertTrue(JsonParser.parseString(lines.get(0)).getAsJsonObject().get("encoding").getAsString()
                .equals("utf8"), "The capture's header is " + lines.get(0));
            long previous = -1;
            for (String line : lines.subList(1, lines.size())) {
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                long tick = event.get("tick").getAsLong();
                helper.assertTrue("StatEvent2".equals(event.get("type").getAsString()) && tick > previous,
                    "A capture line is " + line);
                previous = tick;
                List<String> names = event.getAsJsonArray("stats").asList().stream()
                    .map(stat -> stat.getAsJsonObject().get("name").getAsString())
                    .toList();
                helper.assertTrue(names.contains("server_tick_timings") && names.contains("entities"),
                    "A tick's statistics are " + names);
            }
            helper.succeed();
        });
    }

    private static void console(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** The lines of the newest file of a kind written into a folder since a moment. */
    private static List<String> newestLines(GameTestHelper helper, Path folder, String extension, long since) {
        try (Stream<Path> files = Files.list(folder)) {
            Path newest = files
                .filter(file -> file.getFileName().toString().endsWith(extension))
                .filter(file -> file.toFile().lastModified() >= since - 1000)
                .max((first, second) -> Long.compare(first.toFile().lastModified(), second.toFile().lastModified()))
                .orElse(null);
            helper.assertTrue(newest != null, "No " + extension + " was written under " + folder);
            return Files.readAllLines(newest);
        } catch (IOException unreadable) {
            helper.fail("Nothing could be read under " + folder + ": " + unreadable);
            return List.of();
        }
    }

    private static int markedLine(GameTestHelper helper, Path script) {
        try {
            List<String> lines = Files.readAllLines(script);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(MARKER)) return i + 1;
            }
        } catch (IOException unreadable) {
            helper.fail("weigh.js could not be read: " + unreadable);
        }
        helper.fail("weigh.js carries no line marked " + MARKER);
        return 0;
    }

    private static int freePort(GameTestHelper helper) {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (IOException none) {
            helper.fail("No free port: " + none);
            return 0;
        }
    }

    /**
     * The debugger client, on a thread of its own because a breakpoint holds the server thread.
     *
     * It frames each message the way the extension does, eight hexadecimal digits of length and a
     * newline before a line of JSON, and writes nothing through the mod's own framing.
     */
    private static final class Client {
        private static final int READ_TIMEOUT_MILLIS = 20_000;

        private final int port;
        private final String module;
        private final int line;
        private final AtomicReference<String> failure = new AtomicReference<>();
        private volatile boolean configured;
        private volatile boolean done;
        private volatile List<String> plugins = List.of();
        private volatile boolean verified;
        private volatile String reason;
        private volatile JsonArray frames;
        private DataInputStream in;
        private OutputStream out;

        private Client(int port, String module, int line) {
            this.port = port;
            this.module = module;
            this.line = line;
        }

        private void run() {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                in = new DataInputStream(socket.getInputStream());
                out = socket.getOutputStream();

                JsonObject protocol = read().getAsJsonObject("event");
                plugins = protocol.getAsJsonArray("plugins").asList().stream()
                    .map(plugin -> plugin.getAsJsonObject().get("module_uuid").getAsString())
                    .toList();
                JsonObject answer = new JsonObject();
                answer.addProperty("type", "protocol");
                answer.addProperty("version", protocol.get("version").getAsInt());
                answer.addProperty("target_module_uuid", module);
                send(answer);

                JsonObject breakpoints = new JsonObject();
                breakpoints.addProperty("path", "weigh.js");
                JsonArray lines = new JsonArray();
                lines.add(line);
                breakpoints.add("breakpoints", lines);
                JsonObject set = request(1, "setBreakpoints", breakpoints);
                verified = set.getAsJsonObject("body").getAsJsonArray("breakpoints")
                    .get(0).getAsJsonObject().get("verified").getAsBoolean();

                JsonObject resume = new JsonObject();
                resume.addProperty("type", "resume");
                send(resume);
                configured = true;

                JsonObject stopped = awaitEvent("StoppedEvent");
                reason = stopped.get("reason").getAsString();
                JsonObject trace = new JsonObject();
                trace.addProperty("threadId", stopped.get("thread").getAsInt());
                frames = request(2, "stackTrace", trace).getAsJsonArray("body");
                request(3, "continue", new JsonObject());
            } catch (IOException | RuntimeException error) {
                failure.set(error.toString());
            } finally {
                configured = true;
                done = true;
            }
        }

        private JsonObject request(int sequence, String command, JsonObject arguments) throws IOException {
            JsonObject inner = new JsonObject();
            inner.addProperty("request_seq", sequence);
            inner.addProperty("command", command);
            inner.add("args", arguments);
            JsonObject envelope = new JsonObject();
            envelope.addProperty("type", "request");
            envelope.add("request", inner);
            send(envelope);
            while (true) {
                JsonObject message = read();
                if ("response".equals(message.get("type").getAsString())
                    && message.get("request_seq").getAsInt() == sequence) {
                    if (message.has("error")) throw new IOException(command + ": " + message.get("error"));
                    return message;
                }
            }
        }

        private JsonObject awaitEvent(String type) throws IOException {
            while (true) {
                JsonObject message = read();
                if ("event".equals(message.get("type").getAsString())
                    && Objects.equals(type, message.getAsJsonObject("event").get("type").getAsString())) {
                    return message.getAsJsonObject("event");
                }
            }
        }

        private void send(JsonObject message) throws IOException {
            byte[] json = (message + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(String.format("%08x\n", json.length).getBytes(StandardCharsets.US_ASCII));
            out.write(json);
            out.flush();
        }

        private JsonObject read() throws IOException {
            byte[] header = new byte[9];
            in.readFully(header);
            byte[] body = new byte[Integer.parseInt(new String(header, StandardCharsets.US_ASCII).trim(), 16)];
            in.readFully(body);
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
