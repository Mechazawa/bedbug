package at.ioexception.mcaddon.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.tools.profiler.CPUSampler;
import com.oracle.truffle.tools.profiler.CPUSamplerData;
import com.oracle.truffle.tools.profiler.ProfilerNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The script profiler `/script profiler` and the debugger's capture buttons run, writing what it
 * saw as the Chrome `.cpuprofile` Bedrock writes, with the `$vscode` locations Mojang's debugger
 * reads back out of one.
 *
 * Truffle's sampler stops the server thread every millisecond and records the guest stack it was
 * in. It keeps a call tree with the moments each node was on top of the stack, which is what a
 * profile's nodes, samples and time deltas are.
 */
public final class ScriptProfiler {
    private static final long PERIOD_MILLIS = 1;

    private static ScriptProfiler running;

    private final ScriptHost host;
    private final CPUSampler sampler;
    private final long startedMillis = System.currentTimeMillis();

    private ScriptProfiler(ScriptHost host) {
        this.host = host;
        this.sampler = CPUSampler.find(host.engine());
    }

    /** Starts recording, and answers false when there is no script to record or a profile is running. */
    public static boolean start(ScriptHost host) {
        if (host == null || running != null) return false;
        ScriptProfiler profiler = new ScriptProfiler(host);
        profiler.sampler.clearData();
        profiler.sampler.setPeriod(PERIOD_MILLIS);
        profiler.sampler.setGatherSelfHitTimes(true);
        profiler.sampler.setCollecting(true);
        running = profiler;
        return true;
    }

    public static boolean isRunning() {
        return running != null;
    }

    /** Stops recording and hands back the profile, or null when none was running. */
    public static JsonObject stop() {
        ScriptProfiler profiler = running;
        running = null;
        if (profiler == null) return null;
        profiler.sampler.setCollecting(false);
        JsonObject profile = profiler.profile();
        profiler.sampler.clearData();
        return profile;
    }

    private JsonObject profile() {
        long endedMillis = System.currentTimeMillis();
        Profile built = new Profile();
        Node root = built.add("(root)", "", -1, -1);
        for (CPUSamplerData data : sampler.getDataList()) {
            data.getThreadData().values().forEach(nodes -> built.addChildren(root, nodes));
        }

        built.hits.sort(Comparator.comparingLong(Hit::millis));
        JsonArray samples = new JsonArray();
        JsonArray deltas = new JsonArray();
        long previous = startedMillis;
        for (Hit hit : built.hits) {
            long at = Math.max(previous, Math.min(hit.millis(), endedMillis));
            samples.add(hit.node());
            deltas.add((at - previous) * 1000);
            previous = at;
        }

        JsonArray nodes = new JsonArray();
        JsonArray locations = new JsonArray();
        for (Node node : built.nodes) {
            nodes.add(node.json());
            JsonObject location = new JsonObject();
            location.add("callFrame", node.callFrame());
            location.add("locations", node.locations());
            locations.add(location);
        }

        JsonObject vscode = new JsonObject();
        vscode.addProperty("rootPath", "");
        vscode.add("locations", locations);

        JsonObject profile = new JsonObject();
        profile.add("nodes", nodes);
        profile.addProperty("startTime", startedMillis * 1000);
        profile.addProperty("endTime", endedMillis * 1000);
        profile.add("samples", samples);
        profile.add("timeDeltas", deltas);
        profile.add("$vscode", vscode);
        return profile;
    }

    /** The profile's nodes as they are numbered, and every sample naming one. */
    private final class Profile {
        private final List<Node> nodes = new ArrayList<>();
        private final List<Hit> hits = new ArrayList<>();

        private Node add(String function, String url, int line, int column) {
            Node node = new Node(nodes.size() + 1, function, url, line, column);
            nodes.add(node);
            return node;
        }

        private void addChildren(Node parent, Collection<ProfilerNode<CPUSampler.Payload>> children) {
            for (ProfilerNode<CPUSampler.Payload> child : children) {
                SourceSection section = child.getSourceSection();
                String name = child.getRootName();
                Node node = add(name == null || name.isEmpty() ? "(anonymous)" : name,
                    section == null ? "" : urlOf(section),
                    section == null ? -1 : section.getStartLine() - 1,
                    section == null ? -1 : section.getStartColumn() - 1);
                parent.children.add(node.id);

                CPUSampler.Payload payload = child.getPayload();
                node.hitCount = payload.getSelfHitCount();
                payload.getSelfHitTimes().forEach(millis -> hits.add(new Hit(node.id, millis)));
                addChildren(node, child.getChildren());
            }
        }

        /** A pack's script by its path inside the pack's `scripts` folder, anything else by its own name. */
        private String urlOf(SourceSection section) {
            var source = section.getSource();
            String path = source.getURI() == null ? source.getPath() : source.getURI().getPath();
            return host.scripted().stream()
                .map(pack -> pack.nameOf(path))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(source.getName());
        }
    }

    /** One sample on top of a node, at the wall clock millisecond the sampler took it. */
    private record Hit(int node, long millis) {
    }

    private static final class Node {
        private final int id;
        private final String function;
        private final String url;
        private final int line;
        private final int column;
        private final List<Integer> children = new ArrayList<>();
        private int hitCount;

        private Node(int id, String function, String url, int line, int column) {
            this.id = id;
            this.function = function;
            this.url = url;
            this.line = line;
            this.column = column;
        }

        private JsonObject callFrame() {
            JsonObject frame = new JsonObject();
            frame.addProperty("functionName", function);
            frame.addProperty("scriptId", url.isEmpty() ? "0" : Integer.toString(url.hashCode() & Integer.MAX_VALUE));
            frame.addProperty("url", url);
            frame.addProperty("lineNumber", line);
            frame.addProperty("columnNumber", column);
            return frame;
        }

        /** Where the node's code sits, in the one-based lines and columns the debugger's sources use. */
        private JsonArray locations() {
            JsonArray locations = new JsonArray();
            if (url.isEmpty()) return locations;
            JsonObject source = new JsonObject();
            source.addProperty("name", url.substring(url.lastIndexOf('/') + 1));
            source.addProperty("path", url);
            source.addProperty("sourceReference", 0);
            JsonObject location = new JsonObject();
            location.addProperty("lineNumber", line + 1);
            location.addProperty("columnNumber", column + 1);
            location.add("source", source);
            locations.add(location);
            return locations;
        }

        private JsonObject json() {
            JsonObject node = new JsonObject();
            node.addProperty("id", id);
            node.add("callFrame", callFrame());
            node.addProperty("hitCount", hitCount);
            JsonArray childIds = new JsonArray();
            children.forEach(childIds::add);
            node.add("children", childIds);
            node.addProperty("locationId", id - 1);
            return node;
        }
    }
}
