package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.PaperDoll;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Reads and writes the paper doll option in `options.txt` beside Java's own.
 *
 * `Options.FieldAccess` is package private, so the option is handed to the operation that processes
 * Java's first option, which can call the interface where this class cannot name it.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @WrapOperation(method = "processOptions", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/client/Options$FieldAccess;"
            + "process(Ljava/lang/String;Lnet/minecraft/client/OptionInstance;)V"))
    private void mcaddon$processPaperDoll(@Coerce Object access, String name, OptionInstance<?> option,
        Operation<Void> original) {
        original.call(access, name, option);
        original.call(access, "mcaddon.paperDoll", PaperDoll.OPTION);
    }
}
