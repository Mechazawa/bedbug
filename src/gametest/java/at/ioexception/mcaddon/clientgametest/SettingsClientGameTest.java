package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.integration.SodiumIntegration;
import at.ioexception.mcaddon.client.render.ColourGrading;
import at.ioexception.mcaddon.client.render.PaperDoll;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.List;
import java.util.stream.Stream;

/**
 * The paper doll and colour grading options are reachable from Mod Menu's config screen and from
 * Sodium's video settings, which replace Java's Video Settings screen.
 *
 * The Mod Menu screen is built from the factory Mod Menu itself finds through the `modmenu`
 * entrypoint of this mod, and its buttons are found by label and pressed. Sodium's own option
 * registry is asked for both options and for the value each one is bound to.
 */
public final class SettingsClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        modMenu(context);
        sodium(context);
    }

    private static void modMenu(ClientGameTestContext context) {
        var factory = FabricLoader.getInstance().getEntrypointContainers("modmenu", ModMenuApi.class).stream()
            .filter(container -> container.getProvider().getMetadata().getId().equals("mcaddon"))
            .map(container -> container.getEntrypoint().getModConfigScreenFactory())
            .findFirst()
            .orElseThrow(() -> new AssertionError("Mod Menu finds no entrypoint of mcaddon."));
        context.setScreen(() -> factory.create(new TitleScreen()));

        List<String> labels = context.computeOnClient(client -> widgets(Worlds.screen(client))
            .map(widget -> widget.getMessage().getString()).toList());
        Assert.that(labels.contains("Paper Doll: ON"), "The Mod Menu screen has no Paper Doll button: " + labels);
        Assert.that(labels.contains("Vibrant Visuals Colour Grading: OFF"),
            "The Mod Menu screen has no colour grading button: " + labels);

        press(context, "Paper Doll: ON");
        Assert.that(!PaperDoll.OPTION.get(), "Pressing the Paper Doll button left the option on.");
        press(context, "Vibrant Visuals Colour Grading: OFF");
        Assert.that(ColourGrading.OPTION.get(), "Pressing the colour grading button left the option off.");

        context.runOnClient(client -> {
            PaperDoll.OPTION.set(true);
            ColourGrading.OPTION.set(false);
        });
        context.setScreen(() -> new TitleScreen());
    }

    private static void press(ClientGameTestContext context, String label) {
        context.runOnClient(client -> widgets(Worlds.screen(client))
            .filter(widget -> widget.getMessage().getString().equals(label))
            .map(AbstractButton.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No button is labelled " + label))
            .onPress(new MouseButtonInfo(0, 0)));
        context.waitTick();
    }

    private static void sodium(ClientGameTestContext context) {
        Assert.that(FabricLoader.getInstance().isModLoaded("sodium"), "The client test run carries no Sodium.");
        context.runOnClient(client -> {
            var config = ConfigManager.CONFIG;
            Assert.that(config != null, "Sodium has built no config.");
            Assert.that(config.getOption(SodiumIntegration.PAPER_DOLL) != null, "Sodium lists no Paper Doll option.");
            Assert.that(config.getOption(SodiumIntegration.COLOUR_GRADING) != null,
                "Sodium lists no colour grading option.");
            Assert.that(config.readBooleanOption(SodiumIntegration.PAPER_DOLL) == PaperDoll.OPTION.get(),
                "Sodium reads another Paper Doll value than the game.");
            Assert.that(config.readBooleanOption(SodiumIntegration.COLOUR_GRADING) == ColourGrading.OPTION.get(),
                "Sodium reads another colour grading value than the game.");
        });
    }

    private static Stream<AbstractWidget> widgets(Object parent) {
        Stream<AbstractWidget> own = parent instanceof AbstractWidget widget ? Stream.of(widget) : Stream.empty();
        Stream<AbstractWidget> inner = parent instanceof ContainerEventHandler container
            ? container.children().stream().flatMap(SettingsClientGameTest::widgets) : Stream.empty();
        return Stream.concat(own, inner);
    }
}
