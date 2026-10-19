package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The identity key each server last presented, kept by the address the player joined it at.
 *
 * A server never joined before is trusted as it presents itself. One presenting another key than the
 * one kept for its address is asked about before the player plays: accepting keeps the new key and
 * refusing leaves the server. A world the client hosts itself is not asked about.
 */
public final class TrustedServers {
    public static final String TITLE = "mcaddon.server_trust.title";
    public static final String ACCEPT = "mcaddon.server_trust.accept";
    public static final String DISCONNECT = "mcaddon.server_trust.disconnect";

    private static String waitingAddress;
    private static String waitingKey;

    private TrustedServers() {
    }

    public static Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("mcaddon_trusted_servers.json");
    }

    /** The identity the server just joined presents, as `ServerIdentity.publicText` writes it. */
    public static void presented(String key) {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null) return;
        String trusted = Json.text(known(), server.ip, null);
        if (trusted == null || trusted.equals(key)) {
            trust(server.ip, key);
        } else {
            waitingAddress = server.ip;
            waitingKey = key;
        }
    }

    /** Asks about a changed identity as soon as the world is up with nothing else on the screen. */
    public static void tick(Minecraft client) {
        if (waitingKey == null || client.level == null || Platform.currentScreen() != null) return;
        ServerData server = client.getCurrentServer();
        if (server == null || !server.ip.equals(waitingAddress)) {
            clear();
            return;
        }
        String address = waitingAddress;
        String key = waitingKey;
        waitingAddress = null;
        waitingKey = null;
        Platform.setScreen(new ConfirmScreen(accepted -> {
            if (accepted) {
                trust(address, key);
                Platform.setScreen(null);
            } else if (client.getConnection() != null) {
                client.getConnection().getConnection().disconnect(
                    Component.translatable("mcaddon.server_trust.refused"));
            }
        }, Component.translatable(TITLE), Component.translatable("mcaddon.server_trust.message", address),
            Component.translatable(ACCEPT), Component.translatable(DISCONNECT)));
    }

    public static void clear() {
        waitingAddress = null;
        waitingKey = null;
    }

    private static JsonObject known() {
        try {
            return Files.isRegularFile(file()) ? Json.parseObject(file()) : new JsonObject();
        } catch (IOException | RuntimeException unreadable) {
            McAddon.LOGGER.warn("Could not read the trusted server identities in {}: {}", file(),
                unreadable.getMessage());
            return new JsonObject();
        }
    }

    private static void trust(String address, String key) {
        JsonObject known = known();
        if (key.equals(Json.text(known, address, null))) return;
        known.addProperty(address, key);
        try {
            Files.writeString(file(), known.toString());
        } catch (IOException unwritable) {
            McAddon.LOGGER.warn("Could not keep the identity of {} in {}: {}", address, file(),
                unwritable.getMessage());
        }
    }
}
