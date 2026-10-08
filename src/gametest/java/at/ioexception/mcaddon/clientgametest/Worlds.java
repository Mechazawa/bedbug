package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** Opening a test world and waiting on its server, which the test API spelled differently before 26.2. */
final class Worlds {
    private Worlds() {
    }

    static void waitForChunks(TestSingleplayerContext world) {
        //? if >=26.2 {
        world.getConnection().waitForChunksRender();
        //?} else {
        /*world.getClientLevel().waitForChunksRender();
        *///?}
    }

    /** Waits a tick at a time until the server answers yes, and fails once the ticks run out. */
    static void waitForServer(ClientGameTestContext context, TestSingleplayerContext world,
        Predicate<MinecraftServer> holds, int ticks, String failure) {
        waitForServer(context, world, holds, ticks, () -> failure);
    }

    /** The same, with a failure worded once the ticks have run out, so it can name what was found. */
    static void waitForServer(ClientGameTestContext context, TestSingleplayerContext world,
        Predicate<MinecraftServer> holds, int ticks, Supplier<String> failure) {
        for (int tick = 0; tick < ticks; tick++) {
            if (world.getServer().computeOnServer(holds::test)) return;
            context.waitTick();
        }
        throw new AssertionError(failure.get());
    }
}
