package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.screen.AddonFormScreen;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;

import java.util.List;

/**
 * Talking to an NPC a scene has been given, from the use key to the commands its button runs.
 *
 * `dialogue/keeper.json`'s `tp:greeting` is headed "The Ember Keeper" and offers two buttons. Its
 * opening commands set the reader's `tp.dialogue` score to one, "Feed it" adds a hundred and its
 * closing commands add ten, each naming the reader as `@initiator`, so a reader who opens the scene
 * and feeds the ember ends on 111.
 */
public final class DialogueClientGameTest implements FabricClientGameTest {
    private static final Vec3 NPC = new Vec3(0.5, Stage.FLOOR + 1, 2.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "survival");
            world.getServer().runCommand("scoreboard objectives add tp.dialogue dummy");
            world.getServer().runCommand("summon tp:wisp " + NPC.x + " " + NPC.y + " " + NPC.z + " {NoAI:1b}");
            Entities.waitForClient(context, "tp:wisp");
            world.getServer().runCommand("bedrock dialogue change @e[type=tp:wisp] tp:greeting");

            Stage.lookAt(context, NPC.add(0, 0.3, 0));
            context.waitFor(client -> client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof AddonEntity);
            context.getInput().pressKey(options -> options.keyUse);
            context.waitForScreen(AddonFormScreen.class);

            String title = context.computeOnClient(client -> Platform.currentScreen().getTitle().getString());
            Assert.that(title.equals("The Ember Keeper"), "Expected the scene's screen to carry the name it gives its"
                + " NPC and it carries \"" + title + "\".");
            List<String> shown = context.computeOnClient(client -> Platform.currentScreen().children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(widget -> ((AbstractWidget) widget).getMessage().getString())
                .toList());
            Assert.that(shown.contains("Feed it") && shown.contains("Walk away"), "Expected the scene's two buttons"
                + " on its screen and it shows " + shown + ".");
            int opened = world.getServer().computeOnServer(DialogueClientGameTest::score);
            Assert.that(opened == 1, "Expected the scene's opening command to set the reader's score to 1 and it is "
                + opened + ".");

            context.clickScreenButton("Feed it");
            context.waitForScreen(null);
            Worlds.waitForServer(context, world.getServer(), server -> score(server) == 111, 40, server ->
                "Expected \"Feed it\" to add 100 and the scene's closing to add 10, leaving the reader on 111, and the"
                    + " score is " + score(server) + ".");
        }
    }

    private static int score(MinecraftServer server) {
        Scoreboard board = server.getScoreboard();
        Objective objective = board.getObjective("tp.dialogue");
        ReadOnlyScoreInfo score = objective == null ? null
            : board.getPlayerScoreInfo(Stage.player(server), objective);
        return score == null ? -1 : score.value();
    }
}
