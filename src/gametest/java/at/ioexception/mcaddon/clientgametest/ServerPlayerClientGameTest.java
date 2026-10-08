package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import at.ioexception.mcaddon.client.render.PlayerFog;
import at.ioexception.mcaddon.client.screen.AddonFormScreen;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * What a dedicated server tells one player reaches that player's client and nobody else's.
 *
 * The client is the server's first player and a {@link Bystander} its second. Each order is given
 * once to the bystander, where it has to arrive in the bystander's queue and leave the client alone,
 * and once to the client, where it has to change what the client shows: a form opened by the pack's
 * script, `/camera`, `/camerashake` and `/fog`. The form's answers go back the other way, so the
 * client's press has to reach the pack's handler and an answer naming the bystander's form has to
 * reach nothing.
 */
public final class ServerPlayerClientGameTest implements FabricClientGameTest {
    private static final String FORM = "mcaddon:form";
    private static final String CAMERA = "mcaddon:camera_view";
    private static final String SHAKE = "mcaddon:camera_shake";
    private static final String FOG = "mcaddon:fog";
    private static final String SECOND_PAGE = "The second page tells of sprites.";

    /** How long a client is watched for something meant for the bystander, which it must never show. */
    private static final int QUIET_TICKS = 20;

    private static final int PATIENCE = 200;
    private static final double TOLERANCE = 0.01;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = Worlds.server(context);
            var connection = server.connect()) {
            Worlds.waitForChunks(connection);
            String player = server.computeOnServer(minecraft -> client(minecraft).getName().getString());
            Bystander bystander = server.computeOnServer(Bystander::join);

            forms(context, server, player, bystander);
            catalogue(context, server, player);
            camera(context, server, player, bystander);
            fog(context, server, player, bystander);
        }
    }

    /**
     * The handbook is shown to the bystander first and answered by the client, which names the
     * bystander's form because a form's id is all an answer carries. Only then is it shown to the
     * client and paged through, and the bystander's form has to be waiting still.
     */
    private static void forms(ClientGameTestContext context, TestServerContext server, String player,
        Bystander bystander) {
        server.runCommand("execute as " + Bystander.NAME + " run scriptevent \"tp:handbook\"");
        waitForSent(context, server, bystander, FORM, 1);
        quiet(context, client -> !(Platform.currentScreen() instanceof AddonFormScreen),
            "a form shown to " + Bystander.NAME + " opened on the client");

        int theirs = (int) Bystander.field(server.computeOnServer(minecraft -> bystander.sent(FORM)).get(0), "formId");
        context.runOnClient(client -> Channels.sendFormResponse(theirs,
            "{\"canceled\":true,\"values\":{},\"cancelationReason\":\"UserClosed\"}"));

        server.runCommand("execute as " + player + " run scriptevent \"tp:handbook\"");
        Worlds.waitForClient(context, client -> Platform.currentScreen() instanceof AddonFormScreen, PATIENCE,
            () -> "Expected the handbook shown to " + player + " to open on the client and found "
                + Platform.currentScreen() + ".");
        stillSent(server, bystander, FORM, "the handbook shown to " + player);

        context.clickScreenButton("Next page");
        Worlds.waitForClient(context, client -> shown(Platform.currentScreen()).contains(SECOND_PAGE), PATIENCE,
            () -> "Expected the press on Next page to come back as the second page and found "
                + shown(Platform.currentScreen()) + ".");
        context.clickScreenButton("Put the book away");
        context.waitForScreen(null);
        Worlds.waitForServer(context, server, minecraft -> client(minecraft).entityTags()
                .contains("tp:handbook_ServerClosed"), PATIENCE,
            minecraft -> "Expected the handler of Put the book away to close the form and tag " + player
                + " with tp:handbook_ServerClosed, and found " + client(minecraft).entityTags() + ".");

        List<String> theirTags = server.computeOnServer(minecraft -> handbookTags(bystander.player()));
        Assert.that(theirTags.isEmpty(), "Expected the form shown to " + Bystander.NAME + " to stay open while "
            + player + " answered it, and found it settled with " + theirTags + ".");
    }

    /** A form whose description is longer than a plain network string, pressed and answered. */
    private static void catalogue(ClientGameTestContext context, TestServerContext server, String player) {
        server.runCommand("execute as " + player + " run scriptevent \"tp:catalogue\"");
        Worlds.waitForClient(context,
            client -> shown(Platform.currentScreen()).contains("Ware 800 of the catalogue"), PATIENCE,
            () -> "Expected the eight hundred buttons of the catalogue on the client and found the screen "
                + Platform.currentScreen() + " showing " + shown(Platform.currentScreen()).size() + " widgets.");

        context.clickScreenButton("Ware 1 of the catalogue");
        Worlds.waitForServer(context, server, minecraft -> client(minecraft).entityTags()
                .contains("tp:catalogue_0"), PATIENCE,
            minecraft -> "Expected the press on the catalogue's first ware to tag " + player
                + " with tp:catalogue_0, and found " + client(minecraft).entityTags() + ".");
    }

    private static void camera(ClientGameTestContext context, TestServerContext server, String player,
        Bystander bystander) {
        CameraType own = context.computeOnClient(client -> client.options.getCameraType());

        server.runCommand("camera " + Bystander.NAME + " set minecraft:third_person_front");
        waitForSent(context, server, bystander, CAMERA, 1);
        quiet(context, client -> client.options.getCameraType() == own,
            "/camera set for " + Bystander.NAME + " changed the client's camera");

        Vec3 above = server.computeOnServer(minecraft -> client(minecraft).position().add(0, 10, 0));
        server.runCommand("camera " + player + " set minecraft:free pos " + above.x + " " + above.y + " "
            + above.z + " rot 30 60");
        Worlds.waitForClient(context, client -> Platform.cameraPosition().distanceTo(above) < TOLERANCE, PATIENCE,
            () -> "Expected /camera set with pos " + above + " to put the client's camera there and found it at "
                + Platform.cameraPosition() + ".");
        stillSent(server, bystander, CAMERA, "the camera set for " + player);

        server.runCommand("camerashake add " + Bystander.NAME + " 4 10");
        waitForSent(context, server, bystander, SHAKE, 1);
        quiet(context, client -> Platform.cameraPosition().distanceTo(above) < TOLERANCE,
            "a camera shake for " + Bystander.NAME + " moved the client's camera off " + above);

        server.runCommand("camerashake add " + player + " 4 10");
        Worlds.waitForClient(context, client -> Platform.cameraPosition().distanceTo(above) > 0.05, PATIENCE,
            () -> "Expected a camera shake for " + player + " to move the client's camera off " + above
                + " and found it at " + Platform.cameraPosition() + ".");
        server.runCommand("camerashake stop " + player);
        Worlds.waitForClient(context, client -> Platform.cameraPosition().distanceTo(above) < TOLERANCE, PATIENCE,
            () -> "Expected /camerashake stop to put the client's camera back at " + above + " and found it at "
                + Platform.cameraPosition() + ".");
        stillSent(server, bystander, SHAKE, "the shakes for " + player);

        server.runCommand("camera " + player + " clear");
        Worlds.waitForClient(context, client -> client.options.getCameraType() == own, PATIENCE,
            () -> "Expected /camera clear to give the client back its " + own + " camera and found "
                + context.computeOnClient(client -> client.options.getCameraType()) + ".");
    }

    private static void fog(ClientGameTestContext context, TestServerContext server, String player,
        Bystander bystander) {
        server.runCommand("fog " + Bystander.NAME + " push tp:dusk theirs");
        waitForSent(context, server, bystander, FOG, 1);
        quiet(context, client -> PlayerFog.active() == null, "a fog pushed for " + Bystander.NAME
            + " reached the client");

        server.runCommand("fog " + player + " push tp:dusk mine");
        Worlds.waitForClient(context, client -> fogShown().equals("tp:dusk"), PATIENCE,
            () -> "Expected the fog pushed for " + player + " to be tp:dusk on the client and found "
                + fogShown() + ".");
        server.runCommand("fog " + player + " pop mine");
        Worlds.waitForClient(context, client -> PlayerFog.active() == null, PATIENCE,
            () -> "Expected /fog pop to leave the client with no fog and found " + fogShown() + ".");
        stillSent(server, bystander, FOG, "the fog pushed and popped for " + player);
    }

    private static String fogShown() {
        FogDefinition fog = PlayerFog.active();
        return fog == null ? "no fog" : fog.identifier();
    }

    /** The first player, which is the test client: the bystander joins after it. */
    private static ServerPlayer client(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static List<String> handbookTags(ServerPlayer player) {
        return player.entityTags().stream().filter(tag -> tag.startsWith("tp:handbook_")).toList();
    }

    private static void waitForSent(ClientGameTestContext context, TestServerContext server, Bystander bystander,
        String channel, int count) {
        Worlds.waitForServer(context, server, minecraft -> bystander.sent(channel).size() >= count, PATIENCE,
            minecraft -> "Expected " + count + " " + channel + " message sent to " + Bystander.NAME + " and found "
                + bystander.sent(channel).size() + ".");
    }

    /** The bystander holds the one message of this kind it was sent itself, and nothing meant for the client. */
    private static void stillSent(TestServerContext server, Bystander bystander, String channel, String what) {
        int sent = server.computeOnServer(minecraft -> bystander.sent(channel).size());
        Assert.that(sent == 1, "Expected " + what + " to send " + Bystander.NAME + " no " + channel
            + " message beside its own one and found " + sent + ".");
    }

    /** Holds for a while on the client, which is how something meant for another player is shown not to arrive. */
    private static void quiet(ClientGameTestContext context, Predicate<Minecraft> holds, String failure) {
        for (int tick = 0; tick < QUIET_TICKS; tick++) {
            Assert.that(context.computeOnClient(holds::test), "Expected nothing on the client and found " + failure
                + ".");
            context.waitTick();
        }
    }

    /** The text of every widget on a screen, which is how a form draws its labels and buttons. */
    private static List<String> shown(Screen screen) {
        if (screen == null) return List.of();
        return screen.children().stream()
            .filter(AbstractWidget.class::isInstance)
            .map(widget -> ((AbstractWidget) widget).getMessage().getString())
            .toList();
    }
}
