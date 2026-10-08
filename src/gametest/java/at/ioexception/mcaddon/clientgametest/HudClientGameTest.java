package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.hud.HudElement;
import at.ioexception.mcaddon.client.render.PlayerHud;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Map;

/**
 * `/hud` hides pieces of the heads-up display on the clients of the players it names and nobody
 * else's, and `reset` draws them again.
 *
 * Each piece is watched through the sprite Java draws it with, so a hidden piece is one whose sprite
 * stops being drawn while the rest of the display goes on being drawn around it. The client is the
 * dedicated server's first player and a {@link Bystander} its second: the bystander's display is
 * hidden first and has to leave the client's alone, and nothing the client is told reaches the
 * bystander. The script's `ScreenDisplay.setHudVisibility` changes the same set as the command, and
 * a piece no Java client draws is refused the way a function line naming it is.
 */
public final class HudClientGameTest implements FabricClientGameTest {
    private static final String HUD = "mcaddon:hud";
    private static final int PATIENCE = 200;

    /** How many ticks a piece is watched for being drawn, or for not being drawn. */
    private static final int WATCHED = 10;

    /** The sprite each piece is drawn with, which a survival player with an effect on sees every frame. */
    private static final Map<HudElement, String> SPRITES = Map.of(
        HudElement.CROSSHAIR, "minecraft:hud/crosshair",
        HudElement.HOTBAR, "minecraft:hud/hotbar",
        HudElement.HEALTH, "minecraft:hud/heart/container",
        HudElement.HUNGER, "minecraft:hud/food_empty",
        HudElement.PROGRESS_BAR, "minecraft:hud/experience_bar_background",
        HudElement.STATUS_EFFECTS, "minecraft:hud/effect_background");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = Worlds.server(context);
            var connection = server.connect()) {
            Worlds.waitForChunks(connection);
            String player = server.computeOnServer(minecraft -> client(minecraft).getName().getString());
            server.runCommand("gamemode survival " + player);
            server.runCommand("effect give " + player + " minecraft:speed infinite");
            SPRITES.forEach((element, sprite) -> drawn(context, sprite, element + " before anything was hidden"));
            Bystander bystander = server.computeOnServer(Bystander::join);

            theBystandersAlone(context, server, bystander);
            SPRITES.keySet().forEach(element -> oneAtATime(context, server, player, element));
            everything(context, server, player);
            refused(server, player);
            fromTheScript(context, server, player);

            int sent = server.computeOnServer(minecraft -> bystander.sent(HUD).size());
            Assert.that(sent == 1, "Expected nothing the client's display was told to reach " + Bystander.NAME
                + " beside its own one message, and found " + sent + ".");
        }
    }

    /** The bystander's whole display is hidden, which reaches its queue and leaves the client drawing. */
    private static void theBystandersAlone(ClientGameTestContext context, TestServerContext server,
        Bystander bystander) {
        server.runCommand("bedrock hud " + Bystander.NAME + " hide");
        Worlds.waitForServer(context, server, minecraft -> !bystander.sent(HUD).isEmpty(), PATIENCE,
            "Expected /hud hide for " + Bystander.NAME + " to send it a message, and none was sent.");
        int hidden = (int) Bystander.field(server.computeOnServer(minecraft -> bystander.sent(HUD).get(0)), "hidden");
        Assert.that(HudElement.fromBits(hidden).containsAll(Arrays.asList(HudElement.values())),
            "Expected /hud hide with no element to hide every piece of " + Bystander.NAME + "'s display and it"
                + " hid " + HudElement.fromBits(hidden) + ".");

        SPRITES.forEach((element, sprite) -> drawn(context, sprite, element + " with only " + Bystander.NAME
            + "'s display hidden"));
        Assert.that(context.computeOnClient(client -> Arrays.stream(HudElement.values()).noneMatch(PlayerHud::hides)),
            "Expected the client to hide nothing while only " + Bystander.NAME + "'s display was hidden.");
    }

    private static void oneAtATime(ClientGameTestContext context, TestServerContext server, String player,
        HudElement element) {
        server.runCommand("bedrock hud " + player + " hide " + element.bedrockName());
        Worlds.waitForClient(context, client -> PlayerHud.hides(element), PATIENCE,
            () -> "Expected /hud hide " + element.bedrockName() + " to reach the client.");
        notDrawn(context, element);
        SPRITES.forEach((other, sprite) -> {
            if (other != element) drawn(context, sprite, other + " while only " + element + " was hidden");
        });

        server.runCommand("bedrock hud " + player + " reset " + element.bedrockName());
        Worlds.waitForClient(context, client -> !PlayerHud.hides(element), PATIENCE,
            () -> "Expected /hud reset " + element.bedrockName() + " to reach the client.");
        drawn(context, SPRITES.get(element), element + " once it was reset");
    }

    /** `all` hides every piece a Java client draws, and `reset` with no element draws them all again. */
    private static void everything(ClientGameTestContext context, TestServerContext server, String player) {
        server.runCommand("bedrock hud " + player + " hide all");
        Worlds.waitForClient(context, client -> Arrays.stream(HudElement.values())
                .filter(HudElement::drawnByJava).allMatch(PlayerHud::hides), PATIENCE,
            () -> "Expected /hud hide all to hide every piece a Java client draws.");
        SPRITES.keySet().forEach(element -> undrawn(context, element));

        server.runCommand("bedrock hud " + player + " reset");
        Worlds.waitForClient(context, client -> Arrays.stream(HudElement.values()).noneMatch(PlayerHud::hides),
            PATIENCE, () -> "Expected /hud reset with no element to draw every piece again.");
        SPRITES.forEach((element, sprite) -> drawn(context, sprite, element + " once everything was reset"));
    }

    private static void refused(TestServerContext server, String player) {
        String said = Worlds.said(server, "bedrock hud " + player + " hide paperdoll");
        Assert.that(said.contains("hud paperdoll, which Java has no form of"),
            "Expected /hud hide paperdoll to be refused as a piece Java has no form of, and the command said: "
                + said);
    }

    /** The script hides the hotbar, reads that back, and resets every piece through the same set. */
    private static void fromTheScript(ClientGameTestContext context, TestServerContext server, String player) {
        server.runCommand("bedrock execute as " + player + " run scriptevent tp:hud_hide");
        Worlds.waitForClient(context, client -> PlayerHud.hides(HudElement.HOTBAR), PATIENCE,
            () -> "Expected the script's setHudVisibility to hide the client's hotbar.");
        notDrawn(context, HudElement.HOTBAR);
        Worlds.waitForServer(context, server, minecraft -> client(minecraft).entityTags()
                .contains("tp:hud_hotbar_hidden"), PATIENCE,
            minecraft -> "Expected the script to read the hotbar back as hidden, and " + player + " carries "
                + client(minecraft).entityTags() + ".");

        server.runCommand("bedrock execute as " + player + " run scriptevent tp:hud_reset");
        Worlds.waitForClient(context, client -> !PlayerHud.hides(HudElement.HOTBAR), PATIENCE,
            () -> "Expected the script's resetHudElementsVisibility to draw the client's hotbar again.");
        drawn(context, SPRITES.get(HudElement.HOTBAR), "the hotbar once the script reset the display");
        Worlds.waitForServer(context, server, minecraft -> client(minecraft).entityTags()
                .contains("tp:hud_all_drawn"), PATIENCE,
            minecraft -> "Expected the script to read back no hidden piece, and " + player + " carries "
                + client(minecraft).entityTags() + ".");
    }

    private static void drawn(ClientGameTestContext context, String sprite, String what) {
        long before = context.computeOnClient(client -> Drawn.times(sprite));
        Worlds.waitForClient(context, client -> Drawn.times(sprite) > before, PATIENCE,
            () -> "Expected " + sprite + " to be drawn for " + what + ", and it was not.");
    }

    /** The piece's sprite is drawn no more, while another piece goes on being drawn beside it. */
    private static void notDrawn(ClientGameTestContext context, HudElement element) {
        String witness = SPRITES.get(element == HudElement.CROSSHAIR ? HudElement.HEALTH : HudElement.CROSSHAIR);
        long beside = context.computeOnClient(client -> Drawn.times(witness));
        undrawn(context, element);
        Assert.that(context.computeOnClient(client -> Drawn.times(witness)) > beside,
            "Expected " + witness + " to go on being drawn while " + element + " was hidden.");
    }

    private static void undrawn(ClientGameTestContext context, HudElement element) {
        String sprite = SPRITES.get(element);
        context.waitTicks(2);
        long hidden = context.computeOnClient(client -> Drawn.times(sprite));
        context.waitTicks(WATCHED);
        long since = context.computeOnClient(client -> Drawn.times(sprite)) - hidden;
        Assert.that(since == 0, "Expected " + element + " to be drawn no more once hidden, and " + sprite
            + " was drawn " + since + " more times.");
    }

    /** The first player, which is the test client: the bystander joins after it. */
    private static ServerPlayer client(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }
}
