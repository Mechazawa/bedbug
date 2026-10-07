package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.screen.AddonFormScreen;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * The test add-on's handbook is a 2.x `CustomForm` whose pages are turned by buttons setting
 * observables. Turning a page has to leave the same screen open and redraw it with the other page,
 * and the promise the pack waits on has to settle only when a handler closes the form.
 */
public final class FormClientGameTest implements FabricClientGameTest {
    private static final String FIRST_PAGE = "The first page tells of embers.";
    private static final String SECOND_PAGE = "The second page tells of sprites.";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            world.getServer().runCommand("execute as @a run scriptevent \"tp:handbook\"");

            context.waitForScreen(AddonFormScreen.class);
            Screen handbook = context.computeOnClient(client -> Platform.currentScreen());
            List<String> firstShown = shown(context);
            expect(firstShown.contains("Handbook, page 1 of 2"), "the first page's heading", firstShown);
            expect(firstShown.contains(FIRST_PAGE), "the first page's text", firstShown);
            expect(!firstShown.contains(SECOND_PAGE), "no text from the second page", firstShown);

            context.clickScreenButton("Next page");
            context.waitFor(client -> shown(Platform.currentScreen()).contains(SECOND_PAGE));
            List<String> secondShown = shown(context);
            expect(context.computeOnClient(client -> Platform.currentScreen()) == handbook,
                "the same screen after a page turn", secondShown);
            expect(secondShown.contains("Handbook, page 2 of 2"), "the second page's heading", secondShown);
            expect(!secondShown.contains(FIRST_PAGE), "no text from the first page", secondShown);
            expect(world.getServer().computeOnServer(server -> tagged(server).isEmpty()),
                "the form's promise still waiting while it shows", secondShown);

            context.clickScreenButton("Put the book away");
            context.waitForScreen(null);
            world.getServer().waitFor(server -> tagged(server).contains("tp:handbook_ServerClosed"));

            // The close button is the player closing the form rather than the pack.
            world.getServer().runCommand("execute as @a run scriptevent \"tp:handbook\"");
            context.waitForScreen(AddonFormScreen.class);
            context.clickScreenButton("Close");
            context.waitForScreen(null);
            world.getServer().waitFor(server -> tagged(server).contains("tp:handbook_ClientClosed"));
        }
    }

    /** The handbook tags a player carries, which the script writes once the form's promise settles. */
    private static List<String> tagged(net.minecraft.server.MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
            .flatMap(player -> player.entityTags().stream())
            .filter(tag -> tag.startsWith("tp:handbook_"))
            .toList();
    }

    private static List<String> shown(ClientGameTestContext context) {
        return context.computeOnClient(client -> shown(Platform.currentScreen()));
    }

    /** The text of every widget on a screen, which is how the form draws its labels and buttons. */
    private static List<String> shown(Screen screen) {
        if (screen == null) return List.of();
        return screen.children().stream()
            .filter(AbstractWidget.class::isInstance)
            .map(widget -> ((AbstractWidget) widget).getMessage().getString())
            .toList();
    }

    private static void expect(boolean held, String what, List<String> shown) {
        if (!held) throw new AssertionError("Expected " + what + "; the screen shows " + shown);
    }
}
