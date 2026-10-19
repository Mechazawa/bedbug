package at.ioexception.mcaddon.client.screen;

//? if >=26 {
import at.ioexception.mcaddon.client.render.ColourGrading;
import at.ioexception.mcaddon.client.render.PaperDoll;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** The paper doll and colour grading options on a screen of their own, for Mod Menu to open. */
public final class AddonOptionsScreen extends OptionsSubScreen {
    public AddonOptionsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("options.mcaddon.title"));
    }

    @Override
    protected void addOptions() {
        list.addBig(PaperDoll.OPTION);
        list.addBig(ColourGrading.OPTION);
    }
}
//?}
