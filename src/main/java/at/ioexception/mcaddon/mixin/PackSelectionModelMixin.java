package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.McAddon;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.stream.Stream;

/**
 * Keeps add-on packs out of the Resource Packs and Data Packs screens.
 *
 * Add-ons are managed in the Add-Ons screen. Only the streams the screen lists are filtered: the
 * model's own lists keep the packs, so committing the screen selects them again.
 */
@Mixin(PackSelectionModel.class)
public abstract class PackSelectionModelMixin {
    @ModifyReturnValue(method = "getSelected", at = @At("RETURN"))
    private Stream<PackSelectionModel.Entry> mcaddon$hideSelected(Stream<PackSelectionModel.Entry> entries) {
        return entries.filter(PackSelectionModelMixin::mcaddon$listed);
    }

    @ModifyReturnValue(method = "getUnselected", at = @At("RETURN"))
    private Stream<PackSelectionModel.Entry> mcaddon$hideUnselected(Stream<PackSelectionModel.Entry> entries) {
        return entries.filter(PackSelectionModelMixin::mcaddon$listed);
    }

    private static boolean mcaddon$listed(PackSelectionModel.Entry entry) {
        return !entry.getId().startsWith(McAddon.MOD_ID + "/");
    }
}
