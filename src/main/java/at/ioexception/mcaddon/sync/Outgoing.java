package at.ioexception.mcaddon.sync;

import at.ioexception.mcaddon.McAddon;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One client's transfer of the server's add-ons, as the server sees it.
 *
 * The server sends the archives a client asks for in pieces, at most `Transfer.WINDOW` of them ahead
 * of the client's acknowledgements, and serves each archive at most once per connection and only
 * when its manifest lists it. The pieces are read on a thread of their own, never on the network
 * thread that heard the acknowledgement. A client that goes quiet for a minute, or takes more than
 * ten minutes in all, is disconnected.
 */
public final class Outgoing {
    private static final long QUIET_MILLIS = 60_000;
    private static final long WHOLE_MILLIS = 600_000;
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Bedrock add-on sender");
        thread.setDaemon(true);
        return thread;
    });

    /** How the transfer reaches the connection it belongs to. */
    public interface Link {
        void send(Transfer.Piece piece);

        /** Ends the connection with a message the player reads. */
        void refuse(String reason);

        /** Lets the join go on. */
        void done();
    }

    private final AddonManifest manifest;
    private final Link link;
    private final Deque<Transfer.Wanted> queue = new ArrayDeque<>();
    private final Set<String> served = new HashSet<>();
    private final long started = System.currentTimeMillis();
    private long heard = started;
    private Transfer.Wanted current;
    private long size;
    private int inFlight;
    private boolean finished;

    public Outgoing(AddonManifest manifest, Link link) {
        this.manifest = manifest;
        this.link = link;
    }

    public synchronized void request(List<Transfer.Wanted> wanted) {
        heard = System.currentTimeMillis();
        for (Transfer.Wanted one : wanted) {
            if (manifest.entries().stream().noneMatch(entry -> entry.sha256().equals(one.sha256()))
                || ServedArchives.file(one.sha256()) == null || !served.add(one.sha256())) {
                finish(false, "The client asked for an add-on this server does not hand out.");
                return;
            }
            queue.add(one);
        }
        pump();
    }

    public synchronized void acknowledged() {
        heard = System.currentTimeMillis();
        inFlight = Math.max(0, inFlight - 1);
        pump();
    }

    public synchronized void ready(Transfer.Ready ready) {
        finish(ready.ok(), ready.reason());
    }

    /** Disconnects a client that stopped making progress, and answers whether this transfer is over. */
    public synchronized boolean expire(long now) {
        if (!finished && (now - heard > QUIET_MILLIS || now - started > WHOLE_MILLIS)) {
            finish(false, "The add-on transfer from this server stopped making progress.");
        }
        return finished;
    }

    private void finish(boolean ok, String reason) {
        if (finished) return;
        finished = true;
        if (ok) {
            link.done();
        } else {
            link.refuse(reason == null || reason.isBlank() ? "The server's add-ons could not be loaded." : reason);
        }
    }

    private void pump() {
        while (!finished && inFlight < Transfer.WINDOW) {
            if (current == null || current.offset() >= size) {
                current = queue.poll();
                if (current == null) return;
                size = manifest.entries().stream().filter(entry -> entry.sha256().equals(current.sha256()))
                    .findFirst().orElseThrow().size();
                if (current.offset() >= size) continue;
            }
            Transfer.Wanted piece = current;
            int length = (int) Math.min(Transfer.PIECE, size - piece.offset());
            current = new Transfer.Wanted(piece.sha256(), piece.offset() + length);
            inFlight++;
            Path file = ServedArchives.file(piece.sha256());
            READER.execute(() -> send(file, piece, length));
        }
    }

    private void send(Path file, Transfer.Wanted piece, int length) {
        byte[] data = new byte[length];
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            in.seek(piece.offset());
            in.readFully(data);
        } catch (IOException failure) {
            McAddon.LOGGER.error("Could not read {} to send it.", file, failure);
            synchronized (this) {
                finish(false, "The server could not read one of its own add-ons.");
            }
            return;
        }
        link.send(new Transfer.Piece(piece.sha256(), piece.offset(), data));
    }
}
