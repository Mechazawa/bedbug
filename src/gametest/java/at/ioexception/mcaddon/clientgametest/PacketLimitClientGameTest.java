package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * A dedicated server with `enable-packet-rate-limiter=true` drops the commands a client sends past
 * the bucket its `packetlimitconfig.json` gives Bedrock's command request packet, id 77, and
 * `/bedrock reloadpacketlimitconfig` puts a looser file in force for the connection already open.
 *
 * Each command the server runs adds one to a score, so the score counts what got through. The
 * bucket never drains, so the count does not depend on how fast the client sends.
 */
public final class PacketLimitClientGameTest implements FabricClientGameTest {
    private static final int BURST = 10;
    private static final Path CONFIG = FabricLoader.getInstance().getGameDir().resolve("packetlimitconfig.json");

    @Override
    public void runTest(ClientGameTestContext context) {
        limitCommandsTo(3);
        Properties properties = new Properties();
        properties.setProperty("enable-packet-rate-limiter", "true");
        try (TestDedicatedServerContext server = Worlds.server(context, properties);
            var connection = server.connect()) {
            Worlds.waitForChunks(connection);
            String player = server.computeOnServer(minecraft -> client(minecraft).getName().getString());
            server.runCommand("op " + player);
            server.runCommand("scoreboard objectives add bursts dummy");

            burst(context);
            assertScore(context, server, 3, "the first burst under a bucket of three");

            limitCommandsTo(100);
            burst(context);
            assertScore(context, server, 3, "a looser file nobody reloaded");

            server.runCommand("bedrock reloadpacketlimitconfig");
            burst(context);
            assertScore(context, server, 3 + BURST, "a burst after reloadpacketlimitconfig read the looser file");
        } finally {
            try {
                Files.deleteIfExists(CONFIG);
            } catch (IOException stuck) {
                throw new UncheckedIOException(stuck);
            }
        }
    }

    private static void limitCommandsTo(int bucket) {
        try {
            Files.writeString(CONFIG, """
                {"limitGroups": [{"minecraftPacketIds": [77], "algorithm": {"name": "BucketPacketLimitAlgorithm",
                    "params": {"drainRatePerSec": 0, "maxBucketSize": %d}}}]}
                """.formatted(bucket));
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    private static void burst(ClientGameTestContext context) {
        context.runOnClient(client -> {
            for (int sent = 0; sent < BURST; sent++) {
                client.player.connection.sendCommand("scoreboard players add @s bursts 1");
            }
        });
    }

    /** Waits until the score reaches what is expected, then a second longer to see it go no further. */
    private static void assertScore(ClientGameTestContext context, TestDedicatedServerContext server, int expected,
        String after) {
        for (int tick = 0; tick < 100 && server.computeOnServer(PacketLimitClientGameTest::score) < expected; tick++) {
            context.waitTick();
        }
        context.waitTicks(20);
        int score = server.computeOnServer(PacketLimitClientGameTest::score);
        Assert.that(score == expected, "Expected " + expected + " commands to reach the server after " + after
            + " and counted " + score);
    }

    private static int score(MinecraftServer minecraft) {
        Objective bursts = minecraft.getScoreboard().getObjective("bursts");
        ReadOnlyScoreInfo info = bursts == null ? null
            : minecraft.getScoreboard().getPlayerScoreInfo(client(minecraft), bursts);
        return info == null ? 0 : info.value();
    }

    private static ServerPlayer client(MinecraftServer minecraft) {
        return minecraft.getPlayerList().getPlayers().getFirst();
    }
}
