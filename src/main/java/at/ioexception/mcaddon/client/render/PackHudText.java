package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.ui.HudUi;
import at.ioexception.mcaddon.bedrock.ui.UiPlaced;
import at.ioexception.mcaddon.client.screen.PackUi;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Objects;

/**
 * The title, subtitle and action bar this client was sent last, and what the active packs'
 * `hud_screen.json` makes of them.
 *
 * Bedrock keeps the last title and subtitle as bindings for as long as nothing replaces them, so a
 * pack's control showing one stays after Java's own title has faded. Clearing the titles empties
 * both, and leaving a world forgets all three.
 */
public final class PackHudText {
    private static volatile String title;
    private static volatile String subtitle;
    private static volatile String actionbar;
    private static volatile Laid laid;

    private record Laid(List<Object> key, UiPlaced placed) {
    }

    private PackHudText() {
    }

    /** Takes a title as it arrives, answering whether a pack hides Java's own drawing of it. */
    public static boolean title(String text) {
        title = text;
        return ui().hidesTitle();
    }

    public static boolean subtitle(String text) {
        subtitle = text;
        return ui().hidesSubtitle();
    }

    public static boolean actionbar(String text) {
        actionbar = text;
        return ui().hidesActionbar();
    }

    public static void clearTitles() {
        title = null;
        subtitle = null;
    }

    public static void clear() {
        clearTitles();
        actionbar = null;
        laid = null;
    }

    private static HudUi ui() {
        Minecraft client = Minecraft.getInstance();
        return new HudUi(PackUi.definitions(), title, subtitle, actionbar, client.font::width, client.font.lineHeight);
    }

    /** What the packs inserted into the heads-up display, laid out again only when it can have changed. */
    public static UiPlaced layout(int width, int height) {
        List<Object> key = java.util.Arrays.asList(PackUi.definitions(), title, subtitle, actionbar, width, height);
        Laid last = laid;
        if (last != null && Objects.equals(last.key(), key)) return last.placed();
        UiPlaced placed = ui().layout(width, height);
        laid = new Laid(key, placed);
        return placed;
    }

    public static void draw(Platform.Painter painter) {
        UiPlaced root = layout(painter.width(), painter.height());
        if (root == null) return;
        root.drawn().forEach(control -> {
            switch (control.type()) {
                case "label" -> painter.text(control.text(),
                    control.textLeft(Minecraft.getInstance().font.width(control.text())), control.y(),
                    0xFF000000 | control.colour());
                case "image" -> PackUi.drawImage(painter, control);
                default -> {
                }
            }
        });
    }
}
