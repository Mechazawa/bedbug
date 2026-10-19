package at.ioexception.mcaddon.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One socket to Mojang's script debugger, framed the way that debugger frames it.
 *
 * Every message is eight hexadecimal digits giving the length and a newline, then one line of JSON
 * ending in a newline the length counts. Both directions are framed alike.
 */
public final class DebugConnection implements Closeable {
    private static final int HEADER_LENGTH = 9;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    public DebugConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    public synchronized void send(JsonObject message) {
        byte[] json = (message + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] header = String.format("%08x\n", json.length).getBytes(StandardCharsets.US_ASCII);
        try {
            out.write(header);
            out.write(json);
            out.flush();
        } catch (IOException closed) {
            close();
        }
    }

    /** The next message, or null once the other end has gone. */
    public JsonObject read() {
        try {
            byte[] header = new byte[HEADER_LENGTH];
            in.readFully(header);
            int length = Integer.parseInt(new String(header, StandardCharsets.US_ASCII).trim(), 16);
            byte[] body = new byte[length];
            in.readFully(body);
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException ended) {
            return null;
        }
    }

    public boolean isOpen() {
        return !socket.isClosed();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone, which is what closing asks for.
        }
    }
}
