package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.Mth;

/**
 * A seat's `rotate_rider_by` turns a player sitting in it on the player's own client.
 *
 * `tp:bench` turns a rider alone a quarter circle from the way it faces and locks it there with a
 * `lock_rider_rotation` of nought. A player's client decides which way the player faces, so a turn
 * the server alone works out leaves the player facing wherever it faced before it sat down.
 */
public final class SeatClientGameTest implements FabricClientGameTest {
    private static final float SEAT_TURN = 90f;
    private static final float TOLERANCE = 0.5f;
    private static final int PATIENCE = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("summon tp:bench ~ ~ ~3 {Rotation:[90f,0f]}");
            AddonEntity bench = Entities.waitForClient(context, "tp:bench");
            world.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~ 0 0");
            world.getServer().runCommand("ride @p mount @e[type=tp:bench,limit=1]");
            context.waitFor(client -> client.player != null && client.player.getVehicle() == bench);

            for (int tick = 0; tick < PATIENCE; tick++) {
                float[] facing = context.computeOnClient(client ->
                    new float[] {client.player.getYRot(), bench.getYRot()});
                if (Math.abs(Mth.wrapDegrees(facing[0] - facing[1] - SEAT_TURN)) < TOLERANCE) return;
                context.waitTick();
            }
            float[] facing = context.computeOnClient(client ->
                new float[] {client.player.getYRot(), bench.getYRot()});
            throw new AssertionError("The player on tp:bench faces " + facing[0] + " on its own client, where the"
                + " seat turns it a quarter from the bench's " + facing[1] + ".");
        }
    }
}
