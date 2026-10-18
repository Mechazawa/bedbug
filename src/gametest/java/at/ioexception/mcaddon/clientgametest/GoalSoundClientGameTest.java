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
 * pack names both moments for the brute, so each is heard as a sound definition of its own. The
 * admiring group makes its `admire` moment while it turns gold over, the cheering group its `cheer`
 * moment while it celebrates, and the nesting group its `lay` moment as it lays on sand.
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
            world.getServer().runCommand("kill @e[type=tp:brute]");

            world.getServer().runCommand("execute as @p at @s run summon tp:brute ~3 ~ ~");
            world.getServer().runCommand("bedrock event entity @e[type=tp:brute] tp:brute_admire");
            world.getServer().runCommand(
                "item replace entity @e[type=tp:brute] weapon.mainhand with minecraft:gold_ingot");
            Worlds.waitForClient(context, client -> Heard.of("tp:brute.admire").size() >= 2, PATIENCE,
                () -> "tp:brute admires gold with its admire_item_sound every fifth of a second, and was heard "
                    + Heard.of("tp:brute.admire").size() + " times.");
            world.getServer().runCommand("kill @e[type=tp:brute]");

            world.getServer().runCommand("execute as @p at @s run summon tp:brute ~3 ~ ~");
            world.getServer().runCommand("bedrock event entity @e[type=tp:brute] tp:brute_cheer");
            Worlds.waitForClient(context, client -> Heard.of("tp:brute.cheer").size() >= 2, PATIENCE,
                () -> "tp:brute celebrates with its celebration_sound every fifth of a second, and was heard "
                    + Heard.of("tp:brute.cheer").size() + " times.");
            world.getServer().runCommand("kill @e[type=tp:brute]");

            world.getServer().runCommand("execute as @p at @s run setblock ~5 ~-1 ~ minecraft:sand");
            world.getServer().runCommand("execute as @p at @s run summon tp:brute ~3 ~ ~");
            world.getServer().runCommand("bedrock event entity @e[type=tp:brute] tp:brute_nest");
            Worlds.waitForClient(context, client -> !Heard.of("tp:brute.lay").isEmpty(), PATIENCE,
                () -> "tp:brute lays an egg on the sand beside it and never made its lay_egg_sound.");
        }
    }
}
