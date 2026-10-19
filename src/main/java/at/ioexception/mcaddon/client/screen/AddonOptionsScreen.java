package at.ioexception.mcaddon.client.screen;

//? if >=26 {
import at.ioexception.mcaddon.client.render.ColourGrading;
import at.ioexception.mcaddon.client.render.PaperDoll;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * The paper doll and colour grading options on a screen of their own, for Mod Menu to open, with
 * the way on to the list of add-ons.
 */
public final class AddonOptionsScreen extends OptionsSubScreen {
    public AddonOptionsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("options.mcaddon.title"));
    }

    @Override
    protected void addOptions() {
        list.addBig(PaperDoll.OPTION);
        list.addBig(ColourGrading.OPTION);
        list.addSmall(java.util.List.of(Button.builder(Component.translatable("mcaddon.addons.open"),
            button -> Platform.setScreen(new AddonsScreen(this))).build()));
    }
}
//?}
