package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.server.WebSockets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WebSocket connection Bedrock's `/wsserver` opens, held by the client the way Bedrock's own
 * game holds it.
 *
 * It lasts until the WebSocket server closes it or sends `closewebsocket`, `/wsserver off` ends it,
 * or the game ends, so it outlives the world it was opened in. Every message is a JSON object of a
 * header and a body. The WebSocket server subscribes to events by name and sends command lines; a
 * line runs on whatever server the player is playing on, through this mod's request, and its answer
 * and every subscribed event come back from there. The server may ask for the connection to be
 * encrypted, which is an ECDH exchange on secp384r1 and AES-256 in CFB8 from then on, each message
 * sent as a binary frame.
 */
public final class WebSocketLink implements WebSocket.Listener {
    /** The subprotocol Bedrock offers, which says it can encrypt the connection. */
    private static final String ENCRYPTED = "com.microsoft.minecraft.wsencrypt";

    /** Protocol 1.1.0, from which an event carries its name in the header. */
    private static final int VERSION = 0x01010000;
    private static final String NO_REQUEST = "00000000-0000-0000-0000-000000000000";

    /** Commands Bedrock keeps waiting for their answer at once, past which it refuses another. */
    private static final int MOST_PENDING = 100;

    private static final int INVALID_COMMAND_CONTEXT = 0x80000005;
    private static final int MALFORMED_REQUEST = 0x80010001;
    private static final int TOO_MANY_PENDING_REQUESTS = 0x80010003;

    private static final int IV_LENGTH = 16;

    private static WebSocketLink current;
    private static boolean connecting;

    private final URI uri;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private volatile WebSocket socket;
    private CompletableFuture<?> sending = CompletableFuture.completedFuture(null);
    private volatile Cipher encrypting;
    private volatile Cipher decrypting;

    private WebSocketLink(URI uri) {
        this.uri = uri;
    }

    /** What the server sent this client about its connection, on the client's own thread. */
    public static void accept(String kind, String body) {
        switch (kind) {
            case WebSockets.CONNECT -> connect(body.strip());
            case WebSockets.ANSWER -> {
                if (current != null) current.answered(JsonParser.parseString(body).getAsJsonObject());
            }
            case WebSockets.EVENT -> {
                if (current != null) current.raised(JsonParser.parseString(body).getAsJsonObject());
            }
            default -> {
            }
        }
    }

    /** Tells the server just joined which events the WebSocket server listens for. */
    public static void joined() {
        if (current != null) current.sendSubscriptions();
    }

    private static void connect(String written) {
        if (written.equals("off")) {
            if (current != null) current.close();
            return;
        }
        if (connecting) {
            say("Another connection request is currently running");
            return;
        }

        URI uri = uriOf(written);
        if (uri == null) {
            say("The provided server URL is invalid");
            return;
        }

        connecting = true;
        WebSocketLink link = new WebSocketLink(uri);
        HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(ENCRYPTED).buildAsync(uri, link)
            .whenComplete((socket, failure) -> {
                if (failure != null) {
                    Minecraft.getInstance().execute(() -> {
                        connecting = false;
                        say("Could not connect to server: " + uri);
                    });
                }
            });
    }

    /** The server's address, which Bedrock takes without its scheme as readily as with one. */
    private static URI uriOf(String written) {
        try {
            URI uri = new URI(written.contains("://") ? written : "ws://" + written);
            boolean webSocket = "ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme());
            return webSocket && uri.getHost() != null ? uri : null;
        } catch (URISyntaxException unreadable) {
            return null;
        }
    }

    private static void say(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.sendSystemMessage(Component.literal(message));
    }

    @Override
    public void onOpen(WebSocket opened) {
        socket = opened;
        Minecraft.getInstance().execute(() -> {
            connecting = false;
            if (current != null) current.close();
            current = this;
            say("Connection established to server: " + uri);
        });
        opened.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket from, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            String message = text.toString();
            text.setLength(0);
            handle(message);
        }
        from.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket from, ByteBuffer data, boolean last) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        binary.writeBytes(bytes);
        if (last) {
            byte[] message = binary.toByteArray();
            binary.reset();
            Cipher cipher = decrypting;
            handle(new String(cipher == null ? message : cipher.update(message), StandardCharsets.UTF_8));
        }
        from.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket from, int statusCode, String reason) {
        Minecraft.getInstance().execute(this::forget);
        return null;
    }

    @Override
    public void onError(WebSocket from, Throwable error) {
        Minecraft.getInstance().execute(() -> {
            connecting = false;
            forget();
        });
    }

    private void forget() {
        if (current == this) current = null;
    }

    private void close() {
        forget();
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "");
    }

    /** One whole message from the WebSocket server, on the connection's own thread. */
    private void handle(String message) {
        JsonObject header;
        JsonObject body;
        try {
            JsonObject read = JsonParser.parseString(message).getAsJsonObject();
            header = read.getAsJsonObject("header");
            body = read.getAsJsonObject("body");
        } catch (JsonParseException | IllegalStateException | ClassCastException unreadable) {
            send("error", NO_REQUEST, status(MALFORMED_REQUEST, "The message is not a JSON object"), null);
            return;
        }

        String requestId = text(header, "requestId", NO_REQUEST);
        switch (text(header, "messagePurpose", "")) {
            case "subscribe" -> {
                subscribed.add(text(body, "eventName", ""));
                Minecraft.getInstance().execute(this::sendSubscriptions);
            }
            case "unsubscribe" -> {
                subscribed.remove(text(body, "eventName", ""));
                Minecraft.getInstance().execute(this::sendSubscriptions);
            }
            case "commandRequest" -> command(requestId, text(body, "commandLine", ""));
            case "ws:encrypt" -> encrypt(requestId, body);
            default -> send("error", requestId, status(MALFORMED_REQUEST,
                "Unknown message purpose " + text(header, "messagePurpose", "")), null);
        }
    }

    private static String text(JsonObject object, String field, String absent) {
        JsonElement value = object == null ? null : object.get(field);
        return value == null || !value.isJsonPrimitive() ? absent : value.getAsString();
    }

    private void command(String requestId, String commandLine) {
        String stripped = commandLine.strip();
        if (stripped.equals("closewebsocket") || stripped.equals("/closewebsocket")) {
            Minecraft.getInstance().execute(this::close);
            return;
        }
        if (pending.size() >= MOST_PENDING) {
            send("error", requestId, status(TOO_MANY_PENDING_REQUESTS,
                "Too many commands have been requested, wait for one to be done"), null);
            return;
        }

        pending.add(requestId);
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player == null || !Channels.canRequest()) {
                pending.remove(requestId);
                send("commandResponse", requestId,
                    status(INVALID_COMMAND_CONTEXT, "There is no world to run the command in"), null);
                return;
            }
            JsonObject request = new JsonObject();
            request.addProperty("requestId", requestId);
            request.addProperty("commandLine", commandLine);
            Channels.sendRequest(WebSockets.COMMAND, request.toString());
        });
    }

    private void sendSubscriptions() {
        if (Minecraft.getInstance().player == null || !Channels.canRequest()) return;
        JsonArray names = new JsonArray();
        subscribed.forEach(names::add);
        Channels.sendRequest(WebSockets.SUBSCRIPTIONS, names.toString());
    }

    private void answered(JsonObject answer) {
        String requestId = answer.get("requestId").getAsString();
        if (!pending.remove(requestId)) return;
        answer.remove("requestId");
        send("commandResponse", requestId, answer, null);
    }

    private void raised(JsonObject event) {
        String name = event.get("eventName").getAsString();
        if (subscribed.contains(name)) send("event", NO_REQUEST, event.getAsJsonObject("body"), name);
    }

    private static JsonObject status(int statusCode, String statusMessage) {
        JsonObject status = new JsonObject();
        status.addProperty("statusCode", statusCode);
        status.addProperty("statusMessage", statusMessage);
        return status;
    }

    /**
     * Answers the WebSocket server's key with this client's own and encrypts everything after the
     * answer, which goes out as plain text.
     *
     * The key is SHA-256 over the server's salt followed by the shared secret, and its first sixteen
     * bytes are the initial vector of both directions.
     */
    private void encrypt(String requestId, JsonObject body) {
        if (!"cfb8".equals(text(body, "mode", "cfb8"))) {
            send("error", requestId, status(MALFORMED_REQUEST,
                "Unsupported encryption mode " + text(body, "mode", "")), null);
            return;
        }

        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            KeyPair own = generator.generateKeyPair();
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(own.getPrivate());
            agreement.doPhase(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(text(body, "publicKey", "")))), true);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(text(body, "salt", "")));
            byte[] key = digest.digest(agreement.generateSecret());
            SecretKeySpec secret = new SecretKeySpec(key, "AES");
            IvParameterSpec vector = new IvParameterSpec(Arrays.copyOf(key, IV_LENGTH));
            Cipher out = Cipher.getInstance("AES/CFB8/NoPadding");
            out.init(Cipher.ENCRYPT_MODE, secret, vector);
            Cipher in = Cipher.getInstance("AES/CFB8/NoPadding");
            in.init(Cipher.DECRYPT_MODE, secret, vector);

            JsonObject answer = new JsonObject();
            answer.addProperty("publicKey", Base64.getEncoder().encodeToString(own.getPublic().getEncoded()));
            send("ws:encrypt", requestId, answer, null);
            decrypting = in;
            encrypting = out;
        } catch (GeneralSecurityException | IllegalArgumentException refused) {
            send("error", requestId, status(MALFORMED_REQUEST, "The key exchange failed"), null);
        }
    }

    /**
     * Sends one message after every one before it, encrypted when the connection was encrypted as
     * it was handed in, because the cipher is a stream both sides advance in the same order.
     */
    private synchronized void send(String purpose, String requestId, JsonObject body, String eventName) {
        JsonObject header = new JsonObject();
        header.addProperty("version", VERSION);
        header.addProperty("requestId", requestId);
        header.addProperty("messagePurpose", purpose);
        if (eventName != null) header.addProperty("eventName", eventName);
        JsonObject message = new JsonObject();
        message.add("header", header);
        message.add("body", body);

        String written = message.toString();
        Cipher cipher = encrypting;
        sending = sending.handle((sent, failed) -> null).thenCompose(ignored -> cipher == null
            ? socket.sendText(written, true)
            : socket.sendBinary(ByteBuffer.wrap(cipher.update(written.getBytes(StandardCharsets.UTF_8))), true));
    }
}
