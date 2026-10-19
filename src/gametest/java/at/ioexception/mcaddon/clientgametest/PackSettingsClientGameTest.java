package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.ScriptClock;
import at.ioexception.mcaddon.client.screen.AddonOptionsScreen;
import at.ioexception.mcaddon.client.screen.AddonsScreen;
import at.ioexception.mcaddon.client.screen.PackSettingsScreen;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.server.WorldPackSettings;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * The add-on screen opens from the options screen Mod Menu shows and from the pause menu, lists the
 * test add-on and opens its settings, drawn from both of its manifests.
 *
 * In a single player world the player owns the world, so the behaviour pack's toggle, slider and
 * dropdown are moved there with the pointer and the pack's script reads the new values back through
 * `World.getPackSettings` and hears each change through `packSettingChange`. The resource pack's
 * three are moved the same way and the client's own Molang reads them back on a `tp:gazed`, whose
 * `pre_animation` asks the three pack setting queries. On a dedicated server the same player, no
 * operator there, finds the world's controls greyed out and a change sent by hand refused, and
 * once made an operator changes one.
 */
public final class PackSettingsClientGameTest implements FabricClientGameTest {
    private static final String ADDON = "Test Add-On";
    private static final int PATIENCE = 200;

    /** The heading above the column and the Done button below it. */
    private static final int VIEW_MARGIN = 32;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            context.runOnClient(client -> Platform.setScreen(new AddonOptionsScreen(null)));
            context.waitForScreen(AddonOptionsScreen.class);
            context.runOnClient(client -> nested(Platform.currentScreen(),
                    net.minecraft.network.chat.Component.translatable("mcaddon.addons.open").getString())
                .onPress(new net.minecraft.client.input.MouseButtonInfo(0, 0)));
            context.waitForScreen(AddonsScreen.class);

            openFromThePauseMenu(context);
            Assert.that(shown(context).contains("How the sprite looks to you."),
                "Expected the resource pack's label resolved through its own texts, and found " + shown(context));

            changeTheWorlds(context, world.getServer());
            changeThePlayers(context, world);
        }

        try (TestDedicatedServerContext server = Worlds.server(context);
            var connection = server.connect()) {
            Worlds.waitForChunks(connection);
            refusedWithoutOperator(context, server);
        }
    }

    private static void openFromThePauseMenu(ClientGameTestContext context) {
        context.runOnClient(client -> Platform.setScreen(new PauseScreen(true)));
        context.waitForScreen(PauseScreen.class);
        context.clickScreenButton("mcaddon.addons.button");
        context.waitForScreen(AddonsScreen.class);
        List<String> listed = shown(context);
        Assert.that(listed.stream().anyMatch(line -> line.startsWith(ADDON + " 1.0.0"))
                && listed.contains("By mcaddon-fabric")
                && listed.contains("Behaviour pack: " + ADDON + " (active)")
                && listed.contains("Resource pack: " + ADDON + " (active)"),
            "Expected the add-on screen to list the test add-on with its version, author and both packs, and found "
                + listed);
        context.runOnClient(client -> pressAfter(ADDON + " 1.0.0", "Settings"));
        context.waitForScreen(PackSettingsScreen.class);
        Worlds.waitForClient(context, client -> shown(Platform.currentScreen()).contains("Ember glows: On")
                && widget(Platform.currentScreen(), "Ember glows: On").active, PATIENCE,
            () -> "Expected the host's own world settings to be changeable, and found "
                + shown(Platform.currentScreen()));
    }

    private static void changeTheWorlds(ClientGameTestContext context, TestServerContext server) {
        click(context, "Ember glows: On");
        waitForShown(context, "Ember glows: Off");
        slide(context, "Ember heat: 5", 1, 8, 3);
        waitForShown(context, "Ember heat: 3");
        click(context, "Ember mood: Restless");
        waitForShown(context, "Ember mood: Calm");

        server.runCommand("bedrock execute as @a run scriptevent tp:pack_settings");
        Set<String> wanted = Set.of("tp:setting_read_ember_glows_false", "tp:setting_read_ember_heat_3",
            "tp:setting_read_ember_mood_calm", "tp:setting_heard_ember_glows_false",
            "tp:setting_heard_ember_heat_3", "tp:setting_heard_ember_mood_calm");
        Worlds.waitForServer(context, server, minecraft -> tags(minecraft).containsAll(wanted), PATIENCE,
            minecraft -> "Expected the pack's script to read and hear " + wanted + ", and found " + tags(minecraft));

        String saved = server.computeOnServer(minecraft -> String.valueOf(ServerHooks.state().section("packSettings")));
        Assert.that(saved.contains("\"tp:ember_heat\":3"),
            "Expected the world to keep its settings beside it, and found " + saved);
    }

    private static void changeThePlayers(ClientGameTestContext context, TestSingleplayerContext world) {
        click(context, "Sprite lit: Off");
        waitForShown(context, "Sprite lit: On");
        slide(context, "Sprite size: 2", 1, 4, 4);
        waitForShown(context, "Sprite size: 4");
        click(context, "Sprite hue: Red");
        waitForShown(context, "Sprite hue: Blue");

        world.getServer().runCommand("summon tp:gazed ~2 ~ ~2");
        AddonEntity gazed = Entities.waitForClient(context, "tp:gazed");
        String read = context.computeOnClient(client -> {
            ScriptClock clock = ScriptClock.of(gazed);
            clock.tick();
            return "lit " + clock.variable("lit") + ", size " + clock.variable("size") + ", blue "
                + clock.variable("blue") + ", heat " + clock.variable("heat");
        });
        Assert.that(read.equals("lit 1.0, size 4.0, blue 1.0, heat 3.0"),
            "Expected the client's Molang to read the player's resource pack settings and the world's, and found "
                + read);

        String kept = readConfig();
        Assert.that(kept.contains("\"tp:sprite_size\":4"),
            "Expected the player's settings kept in the client's configuration, and found " + kept);
    }

    private static void refusedWithoutOperator(ClientGameTestContext context, TestDedicatedServerContext server) {
        server.runOnServer(minecraft -> Platform.setOperator(minecraft, player(minecraft), false));
        context.runOnClient(client -> Platform.setScreen(new AddonsScreen(null)));
        context.waitForScreen(AddonsScreen.class);
        context.runOnClient(client -> pressAfter(ADDON + " 1.0.0", "Settings"));
        context.waitForScreen(PackSettingsScreen.class);
        Worlds.waitForClient(context, client -> shown(Platform.currentScreen()).stream()
                .anyMatch(line -> line.startsWith("Only an operator or the owner of the world")), PATIENCE,
            () -> "Expected a player who is no operator told they cannot change the world's settings, and found "
                + shown(Platform.currentScreen()));
        Assert.that(!context.computeOnClient(client -> widget(Platform.currentScreen(), "Ember glows: On").active),
            "Expected the world's toggle greyed out for a player who is no operator.");

        context.runOnClient(client -> Channels.sendRequest(WorldPackSettings.KIND,
            "{\"name\":\"tp:ember_glows\",\"value\":false}"));
        context.waitTicks(20);
        server.runCommand("bedrock execute as @a run scriptevent tp:pack_settings");
        Worlds.waitForServer(context, server, minecraft -> tags(minecraft).contains("tp:setting_read_ember_glows_true")
                && !tags(minecraft).contains("tp:setting_heard_ember_glows_false"), PATIENCE,
            minecraft -> "Expected a change from a player who is no operator refused, and found " + tags(minecraft));

        server.runOnServer(minecraft -> Platform.setOperator(minecraft, player(minecraft), true));
        context.runOnClient(client -> Platform.setScreen(new AddonsScreen(null)));
        context.waitForScreen(AddonsScreen.class);
        context.runOnClient(client -> pressAfter(ADDON + " 1.0.0", "Settings"));
        context.waitForScreen(PackSettingsScreen.class);
        Worlds.waitForClient(context, client -> widget(Platform.currentScreen(), "Ember glows: On").active, PATIENCE,
            () -> "Expected an operator able to change the world's settings, and found "
                + shown(Platform.currentScreen()));
        click(context, "Ember glows: On");
        Worlds.waitForServer(context, server,
            minecraft -> tags(minecraft).contains("tp:setting_heard_ember_glows_false"), PATIENCE,
            minecraft -> "Expected an operator's change taken, and found " + tags(minecraft));
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static Set<String> tags(MinecraftServer server) {
        return player(server).entityTags();
    }

    private static String readConfig() {
        try {
            return Files.readString(FabricLoader.getInstance().getConfigDir().resolve("mcaddon-pack-settings.json"));
        } catch (IOException missing) {
            return "nothing, " + missing;
        }
    }

    private static void waitForShown(ClientGameTestContext context, String label) {
        Worlds.waitForClient(context, client -> shown(Platform.currentScreen()).contains(label), PATIENCE,
            () -> "Expected " + label + " on the settings screen, and found " + shown(Platform.currentScreen()));
    }

    /** A button anywhere among the screen's widgets, a row of an options list included. */
    private static Button nested(net.minecraft.client.gui.components.events.ContainerEventHandler parent,
        String label) {
        return everything(parent)
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(button -> button.getMessage().getString().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No button labelled " + label + " on " + parent));
    }

    private static java.util.stream.Stream<net.minecraft.client.gui.components.events.GuiEventListener> everything(
        net.minecraft.client.gui.components.events.ContainerEventHandler parent) {
        return parent.children().stream().flatMap(child ->
            child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler inner
                ? java.util.stream.Stream.concat(java.util.stream.Stream.of(child), everything(inner))
                : java.util.stream.Stream.of(child));
    }

    /** Presses the first button labelled `label` that comes after the line starting with `heading`. */
    private static void pressAfter(String heading, String label) {
        List<AbstractWidget> widgets = widgets(Platform.currentScreen());
        int after = widgets.stream().map(widget -> widget.getMessage().getString()).toList().indexOf(
            widgets.stream().map(widget -> widget.getMessage().getString())
                .filter(text -> text.startsWith(heading)).findFirst().orElseThrow());
        widgets.stream().skip(after)
            .filter(widget -> widget instanceof Button && widget.getMessage().getString().equals(label))
            .map(Button.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No " + label + " button after " + heading))
            .onPress(new net.minecraft.client.input.MouseButtonInfo(0, 0));
    }

    /** A click with the pointer in the middle of the widget, which reaches it only while it is shown. */
    private static void click(ClientGameTestContext context, String label) {
        pointAndPress(context, label, 0.5);
    }

    /** A click on a slider where `value` lies between `low` and `high`. */
    private static void slide(ClientGameTestContext context, String label, double low, double high, double value) {
        pointAndPress(context, label, (value - low) / (high - low));
    }

    private static void pointAndPress(ClientGameTestContext context, String label, double along) {
        bringIntoView(context, label);
        double[] point = context.computeOnClient(client -> {
            AbstractWidget widget = widget(Platform.currentScreen(), label);
            int inset = widget instanceof AbstractSliderButton ? 4 : 0;
            int scale = client.getWindow().getGuiScale();
            return new double[] {(widget.getX() + inset + along * (widget.getWidth() - 2 * inset)) * scale,
                (widget.getY() + widget.getHeight() / 2.0) * scale};
        });
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(0);
        context.waitTick();
    }

    /** Turns the wheel until the widget lies wholly between the heading and the Done button. */
    private static void bringIntoView(ClientGameTestContext context, String label) {
        for (int notch = 0; notch < PATIENCE; notch++) {
            int off = context.computeOnClient(client -> {
                Screen screen = Platform.currentScreen();
                AbstractWidget widget = widget(screen, label);
                if (widget.getY() < VIEW_MARGIN) return 1;
                return widget.getY() + widget.getHeight() > screen.height - VIEW_MARGIN ? -1 : 0;
            });
            if (off == 0) return;
            context.getInput().scroll(off);
            context.waitTick();
        }
        throw new AssertionError("The wheel never brought " + label + " into view.");
    }

    private static AbstractWidget widget(Screen screen, String label) {
        return widgets(screen).stream()
            .filter(widget -> widget.getMessage().getString().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No widget labelled " + label + " in " + shown(screen)));
    }

    private static List<AbstractWidget> widgets(Screen screen) {
        return screen == null ? List.of() : screen.children().stream()
            .filter(AbstractWidget.class::isInstance)
            .map(AbstractWidget.class::cast)
            .toList();
    }

    private static List<String> shown(ClientGameTestContext context) {
        return context.computeOnClient(client -> shown(Platform.currentScreen()));
    }

    private static List<String> shown(Screen screen) {
        return widgets(screen).stream().map(widget -> widget.getMessage().getString()).toList();
    }
}
