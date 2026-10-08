package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.server.MinecraftServer;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Opening a test world, waiting on its server and reading the client's frame, which 26.2 spells differently. */
final class Worlds {
    private Worlds() {
    }

    static void waitForChunks(TestSingleplayerContext world) {
        //? if >=26.2 {
        waitForChunks(world.getConnection());
        //?} else {
        /*world.getClientLevel().waitForChunksRender();
        *///?}
    }

    /** The camera the client draws its frames from. */
    static Camera camera(Minecraft client) {
        //? if >=26.2 {
        return client.gameRenderer.mainCamera();
        //?} else {
        /*return client.gameRenderer.getMainCamera();
        *///?}
    }

    /** The fog the client's last frame was drawn with. */
    static FogData fog(Minecraft client) {
        //? if >=26.2 {
        return client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData;
        //?} else {
        /*return client.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.fogData;
        *///?}
    }

    /** The same wait for a client connected to a dedicated server. */
    static void waitForChunks(TestServerConnection connection) {
        //? if >=26.2 {
        connection.waitForChunksRender();
        //?} else {
        /*connection.getClientLevel().waitForChunksRender();
        *///?}
    }

    /** Waits a tick at a time until the server answers yes, and fails once the ticks run out. */
    static void waitForServer(ClientGameTestContext context, TestServerContext server,
        Predicate<MinecraftServer> holds, int ticks, String failure) {
        waitForServer(context, server, holds, ticks, minecraft -> failure);
    }

    /** The failure is worded once the wait is over, so it can name what the server holds by then. */
    static void waitForServer(ClientGameTestContext context, TestServerContext server,
        Predicate<MinecraftServer> holds, int ticks, Function<MinecraftServer, String> failure) {
        for (int tick = 0; tick < ticks; tick++) {
            if (server.computeOnServer(holds::test)) return;
            context.waitTick();
        }
        throw new AssertionError(server.computeOnServer(failure::apply));
    }

    /** The same on the client, with the failure worded once the wait is over so it names what was found. */
    static void waitForClient(ClientGameTestContext context, Predicate<Minecraft> holds, int ticks,
        Supplier<String> failure) {
        for (int tick = 0; tick < ticks; tick++) {
            if (context.computeOnClient(holds::test)) return;
            context.waitTick();
        }
        throw new AssertionError(context.computeOnClient(client -> failure.get()));
    }

    /** Everything a command typed into the server console sends back, joined into one line. */
    static String said(TestServerContext server, String command) {
        return server.computeOnServer(minecraft -> {
            java.util.List<String> lines = new java.util.ArrayList<>();
            net.minecraft.commands.CommandSource listener = new net.minecraft.commands.CommandSource() {
                @Override
                public void sendSystemMessage(net.minecraft.network.chat.Component message) {
                    lines.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            minecraft.getCommands().performPrefixedCommand(minecraft.createCommandSourceStack().withSource(listener),
                command);
            return String.join(" ", lines);
        });
    }

    /**
     * A dedicated server on a port nothing else holds, because two checkouts running their client
     * game tests at once would otherwise both ask for the default one and the second could not bind.
     */
    static net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext server(
        ClientGameTestContext context) {
        java.util.Properties properties = new java.util.Properties();
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 0,
            java.net.InetAddress.getByName("127.0.0.1"))) {
            properties.setProperty("server-port", String.valueOf(probe.getLocalPort()));
        } catch (java.io.IOException unavailable) {
            throw new AssertionError("No free port for a dedicated server: " + unavailable.getMessage());
        }
        return context.worldBuilder().createServer(properties);
    }
}
