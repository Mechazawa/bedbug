package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/**
 * The sounds a behaviour names reach a player standing by, again and again at the interval it states.
 *
 * `tp:brute`'s wailing group panics by force and makes its `panic` sound every half second, and its
 * shy group flees gold and makes its `retreat` sound every half second while it flees. The resource
 * pack names both moments for the brute, so each is heard as a sound definition of its own.
 */
public final class GoalSoundClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(ui -> ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
            Worlds.waitForChunks(world);
            context.runOnClient(client -> Heard.clear());

            world.getServer().runCommand("execute as @p at @s run summon tp:brute ~3 ~ ~");
            world.getServer().runCommand("bedrock event entity @e[type=tp:brute] tp:brute_wail");
            Worlds.waitForClient(context, client -> Heard.of("tp:brute.panic").size() >= 2, PATIENCE,
                () -> "tp:brute panics by force and makes its panic sound every half second, and was heard "
                    + Heard.of("tp:brute.panic").size() + " times.");
            world.getServer().runCommand("kill @e[type=tp:brute]");

            world.getServer().runCommand("execute as @p at @s run summon tp:brute ~3 ~ ~");
            world.getServer().runCommand("execute as @p at @s run setblock ~4 ~ ~ minecraft:gold_block");
            world.getServer().runCommand("bedrock event entity @e[type=tp:brute] tp:brute_shy_away");
            Worlds.waitForClient(context, client -> !Heard.of("tp:brute.retreat").isEmpty(), PATIENCE,
                () -> "tp:brute flees gold with its retreat sound, and the gold beside it was never fled aloud.");
        }
    }
}
