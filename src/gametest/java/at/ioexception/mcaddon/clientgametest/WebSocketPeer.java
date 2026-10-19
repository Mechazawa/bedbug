package at.ioexception.mcaddon.clientgametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The WebSocket server end of Bedrock's protocol, as a tool such as Code Builder speaks it, bound to
 * a free port on 127.0.0.1.
 *
 * It speaks RFC 6455 itself over a plain socket: one handshake, then frames, the client's masked and
 * its own not. Everything the client sends is kept, in order, for the test to look through.
 */
final class WebSocketPeer implements AutoCloseable {
    private static final String ACCEPT_SUFFIX = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int TEXT = 1;
    private static final int BINARY = 2;
    private static final int CLOSE = 8;
    private static final int PING = 9;
    private static final int PONG = 10;
    private static final int SHORT_LENGTH = 126;
    private static final int LONG_LENGTH = 127;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 16;

    private final ServerSocket listening;
    private final List<JsonObject> received = new CopyOnWriteArrayList<>();
    private volatile Socket socket;
    private volatile String subprotocols = "";
    private volatile boolean closed;
    private volatile Cipher encrypting;
    private volatile Cipher decrypting;
    private volatile Exchange exchange;

    private record Exchange(String requestId, KeyPair own, byte[] salt) {
    }

    WebSocketPeer() throws IOException {
        listening = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread accepting = new Thread(this::serve, "websocket-peer");
        accepting.setDaemon(true);
        accepting.start();
    }

    int port() {
        return listening.getLocalPort();
    }

    boolean connected() {
        return socket != null;
    }

    boolean closed() {
        return closed;
    }

    /** The subprotocols the client offered in its handshake. */
    String subprotocols() {
        return subprotocols;
    }

    /** The first message the client sent that answers to this, or null. */
    JsonObject find(Predicate<JsonObject> wanted) {
        return received.stream().filter(wanted).findFirst().orElse(null);
    }

    static Predicate<JsonObject> answering(String requestId) {
        return message -> requestId.equals(message.getAsJsonObject("header").get("requestId").getAsString());
    }

    static Predicate<JsonObject> event(String name) {
        return message -> message.getAsJsonObject("header").has("eventName")
            && name.equals(message.getAsJsonObject("header").get("eventName").getAsString());
    }

    /** Sends a request with a header Bedrock's own tools write, and answers its id. */
    String request(String purpose, JsonObject body) {
        String requestId = UUID.randomUUID().toString();
        JsonObject header = new JsonObject();
        header.addProperty("version", 1);
        header.addProperty("requestId", requestId);
        header.addProperty("messagePurpose", purpose);
        header.addProperty("messageType", "commandRequest");
        JsonObject message = new JsonObject();
        message.add("header", header);
        message.add("body", body);
        send(message.toString());
        return requestId;
    }

    String subscribe(String event) {
        JsonObject body = new JsonObject();
        body.addProperty("eventName", event);
        return request("subscribe", body);
    }

    String command(String commandLine) {
        JsonObject body = new JsonObject();
        body.addProperty("version", 1);
        body.addProperty("commandLine", commandLine);
        return request("commandRequest", body);
    }

    /**
     * Asks the client to encrypt the connection, the way a WebSocket server does: its own key and a
     * salt out, the client's key back, and AES-256 in CFB8 from the next message on.
     */
    String encrypt() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            KeyPair own = generator.generateKeyPair();
            byte[] salt = new byte[SALT_LENGTH];
            new SecureRandom().nextBytes(salt);

            JsonObject body = new JsonObject();
            body.addProperty("mode", "cfb8");
            body.addProperty("publicKey", Base64.getEncoder().encodeToString(own.getPublic().getEncoded()));
            body.addProperty("salt", Base64.getEncoder().encodeToString(salt));
            String requestId = UUID.randomUUID().toString();
            exchange = new Exchange(requestId, own, salt);

            JsonObject header = new JsonObject();
            header.addProperty("version", 1);
            header.addProperty("requestId", requestId);
            header.addProperty("messagePurpose", "ws:encrypt");
            header.addProperty("messageType", "commandRequest");
            JsonObject message = new JsonObject();
            message.add("header", header);
            message.add("body", body);
            send(message.toString());
            return requestId;
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
    }

    boolean encrypted() {
        return encrypting != null;
    }

    private void serve() {
        try {
            Socket accepted = listening.accept();
            InputStream in = accepted.getInputStream();
            String key = handshake(in, accepted.getOutputStream());
            if (key == null) return;
            socket = accepted;
            read(in);
        } catch (IOException | GeneralSecurityException ended) {
            closed = true;
        }
    }

    private String handshake(InputStream in, OutputStream out) throws IOException, GeneralSecurityException {
        String key = null;
        for (String line = line(in); !line.isEmpty(); line = line(in)) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Sec-WebSocket-Key")) key = value;
            if (name.equalsIgnoreCase("Sec-WebSocket-Protocol")) subprotocols = value;
        }
        if (key == null) return null;

        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + ACCEPT_SUFFIX).getBytes(StandardCharsets.US_ASCII)));
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n"
            + (subprotocols.isEmpty() ? "" : "Sec-WebSocket-Protocol: " + subprotocols.split(",")[0].trim() + "\r\n")
            + "\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return key;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int read = in.read(); read != '\n'; read = in.read()) {
            if (read < 0) throw new IOException("The handshake ended early.");
            if (read != '\r') line.write(read);
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private void read(InputStream in) throws IOException, GeneralSecurityException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = 0;
        while (true) {
            int first = in.read();
            if (first < 0) break;
            int opcode = first & 0x0F;
            byte[] payload = payload(in);

            if (opcode == CLOSE) break;
            if (opcode == PING) {
                frame(PONG, payload);
                continue;
            }
            if (opcode == PONG) continue;
            if (opcode != 0) messageOpcode = opcode;
            message.writeBytes(payload);
            if ((first & 0x80) == 0) continue;

            byte[] whole = message.toByteArray();
            message.reset();
            Cipher cipher = decrypting;
            byte[] plain = messageOpcode == BINARY && cipher != null ? cipher.update(whole) : whole;
            JsonObject parsed = JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
            completeExchange(parsed);
            received.add(parsed);
        }
        closed = true;
    }

    /** Finishes a key exchange the moment the client answers it, before its next message is read. */
    private void completeExchange(JsonObject message) throws GeneralSecurityException {
        Exchange pending = exchange;
        if (pending == null || !answering(pending.requestId()).test(message)) return;

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(pending.own().getPrivate());
        agreement.doPhase(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
            .decode(message.getAsJsonObject("body").get("publicKey").getAsString()))), true);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(pending.salt());
        byte[] key = digest.digest(agreement.generateSecret());
        SecretKeySpec secret = new SecretKeySpec(key, "AES");
        IvParameterSpec vector = new IvParameterSpec(Arrays.copyOf(key, IV_LENGTH));
        Cipher in = Cipher.getInstance("AES/CFB8/NoPadding");
        in.init(Cipher.DECRYPT_MODE, secret, vector);
        Cipher out = Cipher.getInstance("AES/CFB8/NoPadding");
        out.init(Cipher.ENCRYPT_MODE, secret, vector);
        decrypting = in;
        encrypting = out;
        exchange = null;
    }

    private static byte[] payload(InputStream in) throws IOException {
        int second = in.read();
        long length = second & 0x7F;
        if (length == SHORT_LENGTH) {
            length = (in.read() << 8) | in.read();
        } else if (length == LONG_LENGTH) {
            length = 0;
            for (int i = 0; i < Long.BYTES; i++) length = (length << 8) | in.read();
        }
        byte[] mask = (second & 0x80) != 0 ? in.readNBytes(4) : null;
        byte[] payload = in.readNBytes((int) length);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        return payload;
    }

    private synchronized void send(String message) {
        Cipher cipher = encrypting;
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        try {
            if (cipher == null) {
                frame(TEXT, bytes);
            } else {
                frame(BINARY, cipher.update(bytes));
            }
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private synchronized void frame(int opcode, byte[] payload) throws IOException {
        List<Byte> header = new ArrayList<>();
        header.add((byte) (0x80 | opcode));
        if (payload.length < SHORT_LENGTH) {
            header.add((byte) payload.length);
        } else {
            header.add((byte) SHORT_LENGTH);
            header.add((byte) (payload.length >> 8));
            header.add((byte) payload.length);
        }
        OutputStream out = socket.getOutputStream();
        for (byte written : header) out.write(written);
        out.write(payload);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        listening.close();
        if (socket != null) socket.close();
    }
}
