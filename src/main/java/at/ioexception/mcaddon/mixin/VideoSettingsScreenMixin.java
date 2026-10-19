package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.PaperDoll;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.OptionInstance;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lists the paper doll option with the video settings that change the heads-up display. */
//? if >=1.21 {
@Mixin(net.minecraft.client.gui.screens.options.VideoSettingsScreen.class)
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.VideoSettingsScreen.class)
*///?}
public abstract class VideoSettingsScreenMixin {
    //? if >=26 {
    @ModifyReturnValue(method = "preferenceOptions", at = @At("RETURN"))
    //?} else {
    /*@ModifyReturnValue(method = "options", at = @At("RETURN"))
    *///?}
    private static OptionInstance<?>[] mcaddon$addPaperDoll(OptionInstance<?>[] options) {
        return ArrayUtils.add(options, PaperDoll.OPTION);
    }
}
