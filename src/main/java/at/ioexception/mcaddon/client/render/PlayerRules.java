package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.BedrockRules;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * The game rules of Bedrock's this client draws by: the coordinates and the day count it writes at
 * the top left of the screen the way Bedrock does, and whether it shows a toast for a recipe unlocked
 * and the lines naming what an item may break or be placed on. The server says, and a client told
 * nothing draws as Java does.
 */
public final class PlayerRules {
    private static final int LEFT = 2;
    private static final int TOP = 2;
    private static final int LINE = 10;
    private static final int WHITE = 0xFFFFFFFF;
    private static final long DAY = 24000L;

    private static JsonObject rules = new JsonObject();

    private PlayerRules() {
    }

    public static void accept(String body) {
        rules = Json.parse(body).getAsJsonObject();
    }

    public static boolean on(String rule, boolean otherwise) {
        return rules.has(rule) ? rules.get(rule).getAsBoolean() : otherwise;
    }

    public static void draw(Platform.Painter painter) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;

        int top = PaperDoll.shown() ? TOP + PaperDoll.PADDING : TOP;
        if (on(BedrockRules.SHOW_COORDINATES, false)) {
            BlockPos at = player.blockPosition();
            painter.text("Position: " + at.getX() + ", " + at.getY() + ", " + at.getZ(), LEFT, top, WHITE);
            top += LINE;
        }
        if (on(BedrockRules.SHOW_DAYS_PLAYED, false)) {
            painter.text("Days played: " + Platform.dayTime(client.level) / DAY, LEFT, top, WHITE);
        }
    }

    public static void clear() {
        rules = new JsonObject();
    }
}
