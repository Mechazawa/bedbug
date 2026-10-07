package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Opening a test world, which the client game test API spelled differently before 26.2. */
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
}
