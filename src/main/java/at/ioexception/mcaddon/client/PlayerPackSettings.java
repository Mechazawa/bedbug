package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.client.screen.PackSettingsScreen;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.pack.PackSettings;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.WorldPackSettings;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This client's side of the pack settings.
 *
 * A resource pack's settings are the player's own, kept in a file of this client's configuration
 * and read by the client's Molang. A behaviour pack's settings are the world's: the client shows
 * what the server last said and sends the server a change to decide on, which comes back to every
 * player once the server has taken it.
 */
public final class PlayerPackSettings {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mcaddon-pack-settings.json");

    private static volatile boolean connected;
    private static volatile boolean mayChangeWorld;

    private PlayerPackSettings() {
    }

    public static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try {
            PackSettings.CHOSEN.replace(Json.parseObject(FILE));
        } catch (IOException | RuntimeException unreadable) {
            McAddon.LOGGER.warn("Could not read {}; the resource pack settings start at their defaults.", FILE,
                unreadable);
        }
    }

    /** Sets one of a resource pack's settings for this player and keeps it for the next start. */
    public static void choose(String name, JsonElement value) {
        PackSettings.CHOSEN.set(name, value);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, PackSettings.CHOSEN.toJson().toString(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            McAddon.LOGGER.error("Could not write {}.", FILE, error);
        }
    }

    /** Whether a server running this mod is there to keep the world's settings. */
    public static boolean connected() {
        return connected && net.minecraft.client.Minecraft.getInstance().getConnection() != null
            && Channels.canRequest();
    }

    public static boolean mayChangeWorld() {
        return connected() && mayChangeWorld;
    }

    /** Asks the server for the world's settings again, and whether this player may change them now. */
    public static void ask() {
        if (connected()) Channels.sendRequest(WorldPackSettings.KIND, "{}");
    }

    /** Asks the server to change one of the world's settings. Nothing changes here until it answers. */
    public static void propose(String name, JsonElement value) {
        if (!mayChangeWorld()) return;

        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.add("value", value);
        Channels.sendRequest(WorldPackSettings.KIND, body.toString());
    }

    /** What the server says the world's settings are, which a screen showing them redraws from. */
    public static void accept(String body) {
        JsonObject told = Json.parse(body).getAsJsonObject();
        PackSettings.TOLD.replace(told.get("values"));
        mayChangeWorld = Json.bool(told, "mayChange", false);
        connected = true;
        if (Platform.currentScreen() instanceof PackSettingsScreen open) open.told();
    }

    public static void clear() {
        PackSettings.TOLD.replace(null);
        mayChangeWorld = false;
        connected = false;
    }
}
