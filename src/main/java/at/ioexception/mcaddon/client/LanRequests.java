package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Opens the open singleplayer world to the LAN and runs console commands in it when a file named
 * `lan.request` appears in the game directory, which is how `tools/verify/verify.py lan` hosts a
 * world for a second client. Only registered in a development environment.
 *
 * Each line is a command for the integrated server, except `publish`, which opens the world to the
 * LAN and writes the port to `lan.port`. The file is deleted once it has been read.
 */
public final class LanRequests {
    private LanRequests() {
    }

    public static void tick(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        Path request = client.gameDirectory.toPath().resolve("lan.request");
        if (server == null || client.level == null || !Files.exists(request)) return;

        List<String> lines;
        try {
            lines = Files.readAllLines(request, StandardCharsets.UTF_8);
            Files.deleteIfExists(request);
        } catch (IOException failure) {
            McAddon.LOGGER.warn("Could not read {}", request, failure);
            return;
        }

        for (String line : lines) {
            String command = line.strip();
            if (command.isEmpty()) continue;
            if (command.equals("publish")) {
                publish(client, server);
            } else {
                server.execute(() ->
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
            }
        }
    }

    private static void publish(Minecraft client, IntegratedServer server) {
        try {
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            boolean opened = Platform.publishServer(server, port);
            McAddon.LOGGER.info("LAN request answered: published={} port={}", opened, port);
            if (opened) {
                Files.writeString(client.gameDirectory.toPath().resolve("lan.port"),
                    Integer.toString(port), StandardCharsets.UTF_8);
            }
        } catch (IOException failure) {
            McAddon.LOGGER.warn("Could not publish the world to the LAN", failure);
        }
    }
}
