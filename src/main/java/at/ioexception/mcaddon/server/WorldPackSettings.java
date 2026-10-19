package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.pack.PackSettings;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The behaviour packs' settings as this world holds them, kept beside the world.
 *
 * Bedrock lets the world's owner change these and nobody else, and applies a change at once: the
 * script API raises `packSettingChange` while the world runs. Here the owner is an operator, or the
 * player whose single player world it is. A client asks for a change with a request; the server
 * decides, stores the value with the world, raises the event and tells every player the values
 * that now hold, so a refused change puts the asking player's screen back where it was.
 */
public final class WorldPackSettings {
    public static final String KIND = "pack_settings";

    private static final String SECTION = "packSettings";

    private WorldPackSettings() {
    }

    public static void load(AddonState state) {
        PackSettings.WORLD.replace(state == null ? null : state.section(SECTION));
    }

    public static void forget() {
        PackSettings.WORLD.replace(null);
    }

    /**
     * A client's request: a body naming a setting and a value changes it, and any other body only
     * asks for the values again.
     */
    public static void request(ServerPlayer player, String body) {
        JsonObject asked = JsonParser.parseString(body).getAsJsonObject();
        String name = asked.has("name") ? asked.get("name").getAsString() : null;
        if (name != null && mayChange(player) && PackSettings.belongsToWorld(name)) {
            change(player.level().getServer(), name, asked);
        } else {
            tell(player);
        }
    }

    private static boolean mayChange(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return Platform.isOperator(server, player) || Platform.isHost(server, player);
    }

    private static void change(MinecraftServer server, String name, JsonObject asked) {
        PackManifest.Setting moved = PackSettings.WORLD.set(name, asked.get("value"));
        AddonState state = ServerHooks.state();
        if (state != null) state.setSection(SECTION, PackSettings.WORLD.toJson());
        server.getPlayerList().getPlayers().forEach(WorldPackSettings::tell);

        ScriptHost scripts = ServerHooks.scripts();
        if (scripts != null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("settingName", name);
            fields.put("settingValue", moved.plain());
            scripts.raiseWorldEvent("packSettingChange", fields);
        }
    }

    /** Tells a player what the world's settings are and whether they may change them. */
    public static void tell(ServerPlayer player) {
        JsonObject body = new JsonObject();
        body.addProperty("mayChange", mayChange(player));
        body.add("values", PackSettings.WORLD.toJson());
        Channels.sendInstruction(player, KIND, body.toString());
    }
}
