package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * A goal a component group carries runs while the group is on, and only then.
 *
 * `tp:ember`'s `tp:ember_wary` group flees players within ten blocks. With the group off the ember
 * stays beside a player; with it on the ember says it is avoiding a mob and opens the distance; with
 * it off again it stops. The player is in creative on peaceful, which Java's own avoid goal would
 * ignore and the pack's filter does not.
 */
public final class GroupGoalClientGameTest implements FabricClientGameTest {
    private static final int SETTLE_TICKS = 60;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(ui -> ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("difficulty peaceful");
            world.getServer().runCommand("execute as @p at @s run summon tp:ember ~3 ~ ~");
            Worlds.waitForServer(context, world.getServer(), server -> ember(server) != null, 100,
                "tp:ember was summoned and never reached the world.");

            for (int tick = 0; tick < SETTLE_TICKS; tick += 5) {
                context.waitTicks(5);
                Assert.that(!world.getServer().computeOnServer(GroupGoalClientGameTest::avoiding),
                    "tp:ember fled a player while the group carrying its avoid goal was off.");
            }

            // The ember strolls while it waits, so it is put back within reach before it is made wary.
            world.getServer().runCommand("execute as @p at @s run tp @e[type=tp:ember] ~2 ~ ~");
            world.getServer().runCommand("bedrock event entity @e[type=tp:ember] tp:ember_wary_on");
            Worlds.waitForServer(context, world.getServer(), GroupGoalClientGameTest::avoiding, 200,
                "tp:ember never fled a player two blocks away once its tp:ember_wary group was on.");
            context.waitTicks(40);
            double distance = world.getServer()
                .computeOnServer(server -> (double) ember(server).distanceTo(player(server)));
            Assert.that(distance > 5, "tp:ember was avoiding the player and is still " + distance + " blocks away.");

            world.getServer().runCommand("bedrock event entity @e[type=tp:ember] tp:ember_wary_off");
            Worlds.waitForServer(context, world.getServer(), server -> !avoiding(server), 200,
                "tp:ember kept fleeing after its tp:ember_wary group was taken away.");
        }
    }

    private static boolean avoiding(MinecraftServer server) {
        AddonEntity ember = ember(server);
        return ember != null && Boolean.TRUE.equals(ember.value("is_avoiding_mobs"));
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static AddonEntity ember(MinecraftServer server) {
        ServerPlayer player = player(server);
        return player.level().getEntitiesOfClass(AddonEntity.class, player.getBoundingBox().inflate(64),
            entity -> entity.definition() != null && entity.definition().identifier().equals("tp:ember"))
            .stream().findFirst().orElse(null);
    }
}
