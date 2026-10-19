package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.Cheats;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/** Every command typed into the game, by a player, the console or a command block, passes here. */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    //? if >=1.21 {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void mcaddon$refuseWithoutCheats(ParseResults<CommandSourceStack> parsed, String command,
        CallbackInfo info) {
        if (Cheats.refuses(parsed.getContext().getSource(), command)) info.cancel();
    }
    //?} else {
    /*@Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void mcaddon$refuseWithoutCheats(ParseResults<CommandSourceStack> parsed, String command,
        CallbackInfoReturnable<Integer> info) {
        if (Cheats.refuses(parsed.getContext().getSource(), command)) info.setReturnValue(0);
    }
    *///?}
}
