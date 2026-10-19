package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Bedrock's pack list: every add-on this game has loaded, what it is made of, whether it is playing
 * now, and the way to its settings and to what this mod could not do for it.
 *
 * Open it with `new AddonsScreen(parent)`; Done goes back to `parent`.
 */
public final class AddonsScreen extends ColumnScreen {
    private static final int HALF = (WIDTH - 4) / 2;

    public AddonsScreen(Screen parent) {
        super(Component.translatable("mcaddon.addons.title"), parent);
    }

    /**
     * Puts an Add-Ons button on the pause menu, in the row of the button that leaves the world, which
     * moves down a row to make room.
     */
    public static void addToPauseMenu() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof PauseScreen)) return;

            //? if >=26 {
            List<AbstractWidget> buttons = Screens.getWidgets(screen);
            //?} else {
            /*List<AbstractWidget> buttons = Screens.getButtons(screen);
            *///?}
            buttons.stream()
                .filter(Button.class::isInstance)
                .max(Comparator.comparingInt(AbstractWidget::getY))
                .ifPresent(leave -> {
                    buttons.add(Button.builder(Component.translatable("mcaddon.addons.button"),
                            button -> Platform.setScreen(new AddonsScreen(screen)))
                        .bounds(leave.getX(), leave.getY(), leave.getWidth(), BUTTON_HEIGHT)
                        .build());
                    leave.setY(leave.getY() + ROW_HEIGHT);
                });
        });
    }

    @Override
    protected void fill() {
        List<AddonEntry> addons = AddonEntry.loaded();
        if (addons.isEmpty()) text(Component.translatable("mcaddon.addons.none").getString());
        addons.forEach(this::show);
    }

    private void show(AddonEntry addon) {
        text(addon.converted().displayName() + " " + addon.converted().pack().manifest().version());
        if (!addon.authors().isEmpty()) {
            text(Component.translatable("mcaddon.addons.by", String.join(", ", addon.authors())).getString());
        }
        if (!addon.description().isBlank()) text(addon.description());
        addon.packs().forEach(pack -> text(packLine(pack, addon.active())));

        Button settings = widget(Button.builder(Component.translatable("mcaddon.addons.settings"),
                button -> Platform.setScreen(new PackSettingsScreen(this, addon)))
            .bounds(left(), 0, HALF, BUTTON_HEIGHT).build());
        settings.active = addon.hasSettings();
        widget(Button.builder(Component.translatable("mcaddon.addons.report"),
                button -> Platform.setScreen(new AddonReportScreen(this, addon)))
            .bounds(left() + WIDTH - HALF, 0, HALF, BUTTON_HEIGHT).build());
        nextRow();
        gap();
    }

    private static String packLine(BedrockPack pack, boolean active) {
        String kind = pack.role() == PackRole.RESOURCE
            ? "mcaddon.addons.pack.resources" : "mcaddon.addons.pack.behaviour";
        String state = active ? "mcaddon.addons.pack.active" : "mcaddon.addons.pack.inactive";
        return Component.translatable(state, Component.translatable(kind, pack.displayName())).getString();
    }
}
