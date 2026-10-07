package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Takes a picture without the heads-up display when a file named `screenshot.request` appears in
 * the game directory, which is how `tools/scene/scene.py` photographs a scene.
 *
 * A key sent from outside reaches whichever window has the focus, and on a desktop that offers no
 * way to send one at all it reaches nothing. Only registered in a development environment.
 */
public final class ScreenshotRequests {
    // Frames drawn with the display hidden before the picture is taken, so none of it is still in it.
    private static final int SETTLE_TICKS = 3;

    private static int countdown = -1;

    private ScreenshotRequests() {
    }

    public static void tick(Minecraft client) {
        Path request = client.gameDirectory.toPath().resolve("screenshot.request");
        if (countdown < 0) {
            if (client.level == null || !Files.exists(request)) return;
            Platform.hideHud(client, true);
            countdown = SETTLE_TICKS;
            return;
        }

        if (countdown-- > 0) return;
        Screenshot.grab(client.gameDirectory, Platform.mainRenderTarget(client),
            message -> McAddon.LOGGER.info("Screenshot request answered: {}", message.getString()));
        Platform.hideHud(client, false);
        try {
            Files.deleteIfExists(request);
        } catch (IOException failure) {
            McAddon.LOGGER.warn("Could not remove {}", request, failure);
        }
    }
}
