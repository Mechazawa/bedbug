package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.server.ServerHooks;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * What loaded of one add-on and what this mod could not do, the same lines `/mcaddon packs`,
 * `/mcaddon entities` and `/mcaddon scripts` print.
 *
 * The gaps are counted for every add-on in the game together, because a component, a script member
 * or a command is counted by its name wherever it was asked for. Scripts run on the server, so a
 * client playing on another machine reports none running, and `/mcaddon scripts` asks the server.
 */
public final class AddonReportScreen extends ColumnScreen {
    private final AddonEntry addon;

    AddonReportScreen(Screen parent, AddonEntry addon) {
        super(Component.translatable("mcaddon.report.title", addon.converted().displayName()), parent);
        this.addon = addon;
    }

    @Override
    protected void fill() {
        addon.packs().stream().map(PackSet::report).forEach(lines -> lines.forEach(this::text));
        gap();
        text(Component.translatable("mcaddon.report.shared").getString());
        ServerHooks.entitiesReport().forEach(this::text);
        ServerHooks.scriptsReport().forEach(this::text);
    }
}
