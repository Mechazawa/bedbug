package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.screen.AddonRow;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.UUID;

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

    /** Presses the switch over the add-on's row. */
    static void turn(ClientGameTestContext context, String namespace) {
        context.runOnClient(client -> {
            AddonRow row = row(namespace);
            Platform.currentScreen().children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getY() > row.getY() && button.getY() < row.getY() + row.getHeight()
                    && button.getX() > row.getX())
                .filter(button -> button.getMessage().getString().equals(row.state()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The row of " + namespace + " has no switch."))
                .onPress(new MouseButtonInfo(0, 0));
        });
        context.waitTick();
    }
}
