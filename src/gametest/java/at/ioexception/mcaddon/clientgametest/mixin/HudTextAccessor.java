package at.ioexception.mcaddon.clientgametest.mixin;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The title and action bar Java's own heads-up display is showing, which it gives no getter for. */
//? if >=26.2 {
@Mixin(net.minecraft.client.gui.Hud.class)
//?} else {
/*@Mixin(net.minecraft.client.gui.Gui.class)
*///?}
public interface HudTextAccessor {
    @Accessor("title")
    Component mcaddon$title();

    @Accessor("overlayMessageTime")
    int mcaddon$overlayMessageTime();
}
