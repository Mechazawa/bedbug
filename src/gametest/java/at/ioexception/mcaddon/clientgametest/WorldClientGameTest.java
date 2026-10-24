package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.server.MinecraftServer;

/**
 * A single player world opens with the test add-on loaded, and what its server summons reaches the
 * client as the pack's own entity type.
 *
 * The test pack's script counts each first spawn in the player's own dynamic properties, as it hears
 * `playerSpawn`. That is while the player is still joining, so the world is left and opened again to
 * read the count back as two: a pack keeps a player's choices there, and one read or written at that
 * moment has to reach the player the world saves.
 */
public final class WorldClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            save = world.getWorldSave();
            Worlds.waitForChunks(world);
            world.getServer().runCommand("summon tp:sprite ~ ~ ~3");
            Entities.waitForClient(context, "tp:sprite");
            waitForVisits(context, world, 1.0);
        }

        try (TestSingleplayerContext world = save.open()) {
            Worlds.waitForChunks(world);
            waitForVisits(context, world, 2.0);
        }
    }

    private static void waitForVisits(ClientGameTestContext context, TestSingleplayerContext world, double count) {
        Worlds.waitForServer(context, world.getServer(), server -> Double.valueOf(count).equals(visits(server)),
            PATIENCE, server -> "Expected the player's own store to count " + count + " visits, and found "
                + visits(server));
    }

    private static Object visits(MinecraftServer server) {
        return ServerHooks.state().dynamicProperty(server.getPlayerList().getPlayers().get(0).getUUID(), "tp:visits");
    }
}
