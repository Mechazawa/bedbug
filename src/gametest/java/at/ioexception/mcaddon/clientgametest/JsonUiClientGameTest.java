package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.ui.UiPlaced;
import at.ioexception.mcaddon.client.render.PackHudText;
import at.ioexception.mcaddon.client.screen.AddonFormScreen;
import at.ioexception.mcaddon.client.screen.PackUi;
import at.ioexception.mcaddon.clientgametest.mixin.HudTextAccessor;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * The test add-on's `ui/server_form.json` lays an action form whose title starts with `tp_tiles:` out
 * as a 180 by 120 panel holding its title, the prefix cut off, and a three by two grid of 50 by 32
 * tiles six pixels above its bottom edge, and hides Bedrock's own form for it. Its `hud_screen.json`
 * shows a title starting with `tp_banner:` as a line 12 across and 40 down from the top left corner
 * and an action bar starting with `tp_tally:` as one 12 in from the top right corner, hiding Bedrock's
 * own title and action bar for those. Each is read back off the screen the client built and off the
 * heads-up display's own state, and a form and a title without the prefix are drawn as Java's own.
 */
public final class JsonUiClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            tiles(context, world);
            plainTiles(context, world);
            banner(context, world);
            tally(context, world);
        }
    }

    private static void tiles(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock execute as @a run scriptevent tp:tiles");
        context.waitForScreen(AddonFormScreen.class);

        UiPlaced layout = context.computeOnClient(client -> ((AddonFormScreen) Platform.currentScreen()).packLayout());
        if (layout == null) throw new AssertionError("The tiles form was drawn as this mod's own form");
        int[] screen = context.computeOnClient(client -> new int[] {Platform.currentScreen().width,
            Platform.currentScreen().height});

        UiPlaced form = named(layout, "tp_tiles_form").get(0);
        expect(form.width() == 180 && form.height() == 120, "the form panel at 180 by 120, not "
            + form.width() + " by " + form.height());
        expect(Math.abs(form.x() + 90 - screen[0] / 2) <= 1 && Math.abs(form.y() + 60 - screen[1] / 2) <= 1,
            "the form panel in the middle of a " + screen[0] + " by " + screen[1] + " screen, not at "
                + form.x() + ", " + form.y());

        List<UiPlaced> cells = named(layout, "grid_item");
        expect(cells.size() == 6, "six tiles, not " + cells.size());
        for (UiPlaced cell : cells) {
            int column = cell.index() % 3;
            int row = cell.index() / 3;
            expect(cell.x() == form.x() + 15 + column * 50 && cell.y() == form.y() + 50 + row * 32
                && cell.width() == 50 && cell.height() == 32,
                "tile " + cell.index() + " at column " + column + " and row " + row + " of the grid, not at "
                    + (cell.x() - form.x()) + ", " + (cell.y() - form.y()) + " in the form");
        }

        UiPlaced icon = named(layout, "icon").get(0);
        expect(icon.texture().equals("textures/items/berry")
            && context.computeOnClient(client -> PackUi.texture(icon.texture())) != null,
            "each tile's icon drawn from the button's own texture, not " + icon.texture());

        List<String> labels = labels(context);
        expect(labels.contains("Tiles"), "the heading without its prefix among " + labels);
        expect(labels.contains("Tile 5"), "the tiles' own captions among " + labels);
        expect(!labels.contains("tp_tiles:Tiles") && javaButtons(context).isEmpty(),
            "nothing of Bedrock's own form, which the pack hides, but found " + javaButtons(context));

        UiPlaced fifth = cells.stream().filter(cell -> cell.index() == 4).findFirst().orElseThrow();
        clickAt(context, fifth.x() + fifth.width() / 2.0, fifth.y() + fifth.height() / 2.0);
        context.waitForScreen(null);
        waitForTag(context, world, "tp:tiles_4");
    }

    private static void plainTiles(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock execute as @a run scriptevent tp:tiles plain");
        context.waitForScreen(AddonFormScreen.class);
        expect(context.computeOnClient(client -> ((AddonFormScreen) Platform.currentScreen()).packLayout()) == null,
            "a form whose title the pack does not route drawn as this mod's own");
        expect(javaButtons(context).contains("Tile 2"), "Java buttons for the plain form, not "
            + javaButtons(context));
        context.clickScreenButton("Tile 2");
        context.waitForScreen(null);
        waitForTag(context, world, "tp:tiles_1");
    }

    private static void banner(ClientGameTestContext context, TestSingleplayerContext world) {
        Drawn.clearTexts();
        world.getServer().runCommand("title @a title \"tp_banner:Harvest moon\"");
        context.waitTicks(5);

        UiPlaced banner = hudControl(context, "tp_banner");
        expect(banner != null && banner.text().equals("Harvest moon") && banner.x() == 12 && banner.y() == 40,
            "the banner reading Harvest moon at 12, 40, not " + describe(banner));
        expect(Drawn.drewText("Harvest moon"), "the banner's line drawn on the display");
        expect(context.computeOnClient(client -> hud(client).mcaddon$title()) == null,
            "no title of Java's own while the pack hides Bedrock's");

        world.getServer().runCommand("title @a title \"Plain words\"");
        context.waitTicks(5);
        expect(hudControl(context, "tp_banner") == null, "no banner for a title without the prefix");
        expect("Plain words".equals(context.computeOnClient(client -> {
            net.minecraft.network.chat.Component title = hud(client).mcaddon$title();
            return title == null ? null : title.getString();
        })), "Java's own title for a title without the prefix");
    }

    private static void tally(ClientGameTestContext context, TestSingleplayerContext world) {
        Drawn.clearTexts();
        world.getServer().runCommand("title @a actionbar \"tp_tally:7 eggs\"");
        context.waitTicks(5);

        int displayWidth = context.computeOnClient(client -> client.getWindow().getGuiScaledWidth());
        UiPlaced tally = hudControl(context, "tp_tally");
        expect(tally != null && tally.text().equals("7 eggs") && tally.y() == 40
            && tally.x() + tally.width() == displayWidth - 12,
            "the tally reading 7 eggs 12 in from the right of a display " + displayWidth + " wide, not "
                + describe(tally));
        expect(Drawn.drewText("7 eggs"), "the tally's line drawn on the display");
        expect(context.computeOnClient(client -> hud(client).mcaddon$overlayMessageTime()) == 0,
            "no action bar of Java's own while the pack hides Bedrock's");

        world.getServer().runCommand("title @a actionbar \"Nothing to count\"");
        context.waitTicks(5);
        expect(hudControl(context, "tp_tally") == null, "no tally for an action bar without the prefix");
        expect(context.computeOnClient(client -> hud(client).mcaddon$overlayMessageTime()) > 0,
            "Java's own action bar for one without the prefix");
    }

    private static HudTextAccessor hud(Minecraft client) {
        //? if >=26.2 {
        return (HudTextAccessor) client.gui.hud;
        //?} else {
        /*return (HudTextAccessor) client.gui;
        *///?}
    }

    private static UiPlaced hudControl(ClientGameTestContext context, String name) {
        return context.computeOnClient(client -> {
            UiPlaced root = PackHudText.layout(client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight());
            return root == null ? null : named(root, name).stream().findFirst().orElse(null);
        });
    }

    private static List<UiPlaced> named(UiPlaced root, String name) {
        return root.drawn().filter(placed -> placed.control().name().equals(name)).toList();
    }

    private static String describe(UiPlaced placed) {
        return placed == null ? "nothing" : "'" + placed.text() + "' at " + placed.x() + ", " + placed.y()
            + ", " + placed.width() + " wide";
    }

    private static List<String> labels(ClientGameTestContext context) {
        return context.computeOnClient(client -> widgets(Platform.currentScreen()).stream()
            .filter(StringWidget.class::isInstance)
            .map(widget -> widget.getMessage().getString())
            .toList());
    }

    /** The labels of every Java button with a label, which the pack's unseen tile buttons have not. */
    private static List<String> javaButtons(ClientGameTestContext context) {
        return context.computeOnClient(client -> widgets(Platform.currentScreen()).stream()
            .filter(Button.class::isInstance)
            .map(widget -> widget.getMessage().getString())
            .filter(label -> !label.isEmpty())
            .toList());
    }

    private static List<AbstractWidget> widgets(Screen screen) {
        return screen == null ? List.of() : screen.children().stream()
            .filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast)
            .toList();
    }

    /** A click with the pointer at a point of the screen, in the screen's own units. */
    private static void clickAt(ClientGameTestContext context, double x, double y) {
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        context.getInput().setCursorPos(x * scale, y * scale);
        context.getInput().pressMouse(0);
        context.waitTick();
    }

    private static void waitForTag(ClientGameTestContext context, TestSingleplayerContext world, String tag) {
        for (int tick = 0; tick < ClientGameTestContext.DEFAULT_TIMEOUT; tick++) {
            if (world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().stream()
                .anyMatch(player -> player.entityTags().contains(tag)))) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("The tiles form never answered with " + tag);
    }

    private static void expect(boolean held, String what) {
        if (!held) throw new AssertionError("Expected " + what);
    }
}
