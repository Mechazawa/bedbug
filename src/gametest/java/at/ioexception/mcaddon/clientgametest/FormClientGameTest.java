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
 *
 * The test add-on's ledger is a form taller than a small window. Its last buttons start out of
 * sight; the wheel has to scroll them into view where a click with the pointer lands on them, and a
 * redraw the pack sends while the form shows has to leave it scrolled where it was.
 */
public final class FormClientGameTest implements FabricClientGameTest {
    private static final String FIRST_PAGE = "The first page tells of embers.";
    private static final String SECOND_PAGE = "The second page tells of sprites.";
    private static final String TALLY = "Tally";
    private static final String SIGN = "Sign the ledger";

    /** Small enough at any scale the game picks that forty lines of text do not fit. */
    private static final int SMALL_WIDTH = 640;
    private static final int SMALL_HEIGHT = 360;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:handbook");

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
            expect(world.getServer().computeOnServer(server -> tagged(server, "tp:handbook_").isEmpty()),
                "the form's promise still waiting while it shows", secondShown);

            context.clickScreenButton("Put the book away");
            context.waitForScreen(null);
            waitForTag(context, world, "tp:handbook_ServerClosed");

            // The close button is the player closing the form rather than the pack.
            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:handbook");
            context.waitForScreen(AddonFormScreen.class);
            context.clickScreenButton("Close");
            context.waitForScreen(null);
            waitForTag(context, world, "tp:handbook_ClientClosed");

            readTheLedger(context, world);
        }
    }

    private static void readTheLedger(ClientGameTestContext context, TestSingleplayerContext world) {
        int[] window = context.computeOnClient(client -> new int[] {
            client.getWindow().getWidth(), client.getWindow().getHeight()});
        context.getInput().resizeWindow(SMALL_WIDTH, SMALL_HEIGHT);
        try {
            world.getServer().runCommand("bedrock execute as @a run scriptevent tp:ledger");
            context.waitForScreen(AddonFormScreen.class);
            expect(!inView(context, SIGN), "the ledger's last button out of sight before scrolling", shown(context));

            context.getInput().setCursorPos(SMALL_WIDTH / 2.0, SMALL_HEIGHT / 2.0);
            int before;
            do {
                before = top(context, SIGN);
                context.getInput().scroll(-1);
                context.waitTick();
            } while (top(context, SIGN) != before);
            expect(inView(context, SIGN), "the wheel to bring the ledger's last button into view", shown(context));

            int scrolledTo = top(context, SIGN);
            clickWithPointer(context, TALLY);
            context.waitFor(client -> shown(Platform.currentScreen()).contains("Tallied 1 times"));
            expect(top(context, SIGN) == scrolledTo, "the ledger scrolled where it was after the pack redrew"
                + " it, with its last button at " + scrolledTo + " and not " + top(context, SIGN), shown(context));

            clickWithPointer(context, SIGN);
            context.waitForScreen(null);
            waitForTag(context, world, "tp:ledger_signed");
        } finally {
            context.getInput().resizeWindow(window[0], window[1]);
        }
    }

    private static AbstractWidget widget(Screen screen, String label) {
        return screen.children().stream()
            .filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast)
            .filter(widget -> widget.getMessage().getString().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No widget labelled " + label + " in " + shown(screen)));
    }

    private static int top(ClientGameTestContext context, String label) {
        return context.computeOnClient(client -> widget(Platform.currentScreen(), label).getY());
    }

    private static boolean inView(ClientGameTestContext context, String label) {
        return context.computeOnClient(client -> {
            Screen screen = Platform.currentScreen();
            AbstractWidget widget = widget(screen, label);
            return widget.getY() >= 0 && widget.getY() + widget.getHeight() <= screen.height;
        });
    }

    /** A click placed where the widget is drawn, which reaches a widget only while it is shown. */
    private static void clickWithPointer(ClientGameTestContext context, String label) {
        double[] centre = context.computeOnClient(client -> {
            AbstractWidget widget = widget(Platform.currentScreen(), label);
            int scale = client.getWindow().getGuiScale();
            return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale,
                (widget.getY() + widget.getHeight() / 2.0) * scale};
        });
        context.getInput().setCursorPos(centre[0], centre[1]);
        context.getInput().pressMouse(0);
        context.waitTick();
    }

    /** Asked from the test thread a tick at a time, because a server-side wait is not on every version. */
    private static void waitForTag(ClientGameTestContext context, TestSingleplayerContext world, String tag) {
        for (int tick = 0; tick < ClientGameTestContext.DEFAULT_TIMEOUT; tick++) {
            if (world.getServer().computeOnServer(server -> tagged(server, "tp:").contains(tag))) return;
            context.waitTick();
        }
        throw new AssertionError("The form's promise never settled with " + tag);
    }

    /** The tags a player carries that the script writes once a form's promise settles. */
    private static List<String> tagged(net.minecraft.server.MinecraftServer server, String prefix) {
        return server.getPlayerList().getPlayers().stream()
            .flatMap(player -> player.entityTags().stream())
            .filter(tag -> tag.startsWith(prefix))
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
