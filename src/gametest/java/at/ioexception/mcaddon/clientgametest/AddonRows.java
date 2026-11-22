package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.screen.AddonRow;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.UUID;
import java.util.function.Function;

/** An add-on's row on the open Add-Ons screen, and the On/Off switch laid over it. */
final class AddonRows {
    private AddonRows() {
    }

    static ConvertedPack installed(String namespace) {
        return ActiveAddons.installed().converted().stream()
            .filter(one -> one.namespace().equals(namespace))
            .findFirst()
            .orElseThrow(() -> new AssertionError("The add-on " + namespace + " is not installed."));
    }

    /** What a world's add-on list names the installed add-on by. */
    static UUID uuid(String namespace) {
        return installed(namespace).pack().manifest().uuid();
    }

    static AddonRow row(String namespace) {
        String title = installed(namespace).displayName() + " ";
        return Platform.currentScreen().children().stream()
            .filter(AddonRow.class::isInstance)
            .map(AddonRow.class::cast)
            .filter(row -> row.title().startsWith(title))
            .findFirst()
            .orElseThrow(() -> new AssertionError("The Add-Ons screen has no row for " + namespace + "."));
    }

    static String state(ClientGameTestContext context, String namespace) {
        return context.computeOnClient(client -> row(namespace).state());
    }

    static String warning(ClientGameTestContext context, String namespace) {
        return context.computeOnClient(client -> row(namespace).warning());
    }

    /** Clicks the switch over the add-on's row with the pointer, the way a player does. */
    static void turn(ClientGameTestContext context, String namespace) {
        press(context, screen -> {
            AddonRow row = row(namespace);
            return screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(one -> one.getY() > row.getY() && one.getY() < row.getY() + row.getHeight()
                    && one.getX() > row.getX())
                .filter(one -> one.getMessage().getString().equals(row.state()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The row of " + namespace + " has no switch."));
        });
    }

    static boolean shows(ClientGameTestContext context, String text) {
        return context.computeOnClient(client -> Platform.currentScreen().children().stream()
            .filter(AbstractWidget.class::isInstance)
            .anyMatch(widget -> ((AbstractWidget) widget).getMessage().getString().equals(text)));
    }

    /** Clicks the first widget of the open screen labelled `label` with the pointer. */
    static void click(ClientGameTestContext context, String label) {
        press(context, screen -> screen.children().stream()
            .filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast)
            .filter(one -> one.getMessage().getString().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No widget labelled " + label + " on " + screen + ".")));
    }

    private static void press(ClientGameTestContext context, Function<Screen, AbstractWidget> find) {
        double[] point = context.computeOnClient(client -> {
            AbstractWidget widget = find.apply(Platform.currentScreen());
            int scale = client.getWindow().getGuiScale();
            return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale,
                (widget.getY() + widget.getHeight() / 2.0) * scale};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
    }
}
