package at.ioexception.mcaddon.sync;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.ReceivedArchives;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One transfer of a server's add-ons, as the joining client sees it.
 *
 * The client refuses a manifest from another build of this mod, or one asking for more than it is
 * willing to hold, before it requests anything. It then asks for every archive it does not hold,
 * resuming each from where an earlier transfer left it, writes the pieces as they come on a thread
 * of its own and acknowledges each one written, and hands the manifest on once it holds every
 * archive it lists.
 */
public final class Incoming {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Bedrock add-on receiver");
        thread.setDaemon(true);
        return thread;
    });

    /** How the transfer reaches the connection it belongs to. */
    public interface Link {
        void want(List<Transfer.Wanted> wanted);

        void acknowledge(Transfer.Wanted held);

        void ready(Transfer.Ready ready);
    }

    private final AddonManifest manifest;
    private final ReceivedArchives received;
    private final Link link;
    private final Consumer<AddonManifest> whole;
    private final Map<String, ReceivedArchives.Sink> sinks = new HashMap<>();
    private boolean failed;

    /** @param whole what to do once every archive the manifest lists is held */
    public Incoming(AddonManifest manifest, ReceivedArchives received, Link link, Consumer<AddonManifest> whole) {
        this.manifest = manifest;
        this.received = received;
        this.link = link;
        this.whole = whole;
    }

    /** @param installed the digests of the archives this client was started with */
    public void start(Set<String> installed, long free) {
        Set<String> held = new java.util.HashSet<>(installed);
        held.addAll(received.held());
        String refusal = manifest.refusal(McAddon.VERSION, held, free).orElse(null);
        if (refusal != null) {
            fail(refusal);
            return;
        }
        List<AddonManifest.Entry> missing = manifest.toFetch(held);
        if (missing.isEmpty()) {
            whole.accept(manifest);
            return;
        }
        McAddon.LOGGER.info("Downloading {} add-ons ({}) from the server.", missing.size(),
            AddonManifest.megabytes(missing.stream().mapToLong(AddonManifest.Entry::size).sum()));
        try {
            for (AddonManifest.Entry entry : missing) {
                sinks.put(entry.sha256(), received.receive(entry.sha256(), entry.size()));
            }
        } catch (IOException failure) {
            fail("Could not make room for the server's add-ons: " + failure.getMessage());
            return;
        }
        link.want(sinks.entrySet().stream()
            .map(sink -> new Transfer.Wanted(sink.getKey(), sink.getValue().held()))
            .toList());
    }

    /** Takes one piece; the writing happens off the network thread, in the order pieces arrived. */
    public void accept(Transfer.Piece piece) {
        CompletableFuture.runAsync(() -> write(piece), WRITER);
    }

    private synchronized void write(Transfer.Piece piece) {
        if (failed) return;
        ReceivedArchives.Sink sink = sinks.get(piece.sha256());
        if (sink == null) {
            fail("The server sent an add-on this client did not ask for.");
            return;
        }
        try {
            boolean done = sink.accept(piece.offset(), piece.data());
            link.acknowledge(new Transfer.Wanted(piece.sha256(), sink.held()));
            if (done) sinks.remove(piece.sha256());
        } catch (IOException failure) {
            fail("An add-on from the server arrived damaged: " + failure.getMessage());
            return;
        }
        if (sinks.isEmpty()) {
            McAddon.LOGGER.info("Received {} add-ons from the server.", manifest.entries().size());
            whole.accept(manifest);
        }
    }

    private void fail(String reason) {
        failed = true;
        McAddon.LOGGER.warn("Refused the server's add-ons: {}", reason);
        link.ready(new Transfer.Ready(false, reason));
    }
}
