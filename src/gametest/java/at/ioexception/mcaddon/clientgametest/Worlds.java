package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Opening a test world and waiting on its server, which the test API spelled differently before 26.2. */
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
}
