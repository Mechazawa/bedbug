package at.ioexception.mcaddon.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Leaves out the models of a dormant add-on's items and blocks.
 *
 * Before 26 the model bakery asks for an inventory model for every registered item and a block
 * state model for every registered block, whatever pack is loaded. A dormant add-on stays
 * registered while its converted pack is not offered, so each of those requests would read a file
 * no pack holds and log the miss. Nothing can obtain or place a dormant entry, and a model left out
 * is drawn as the missing model, which is what the miss would have drawn anyway. From 26 the
 * models are found from the item and block state files the packs hold, and nothing asks for one a
 * pack does not hold.
 */
@Mixin(net.minecraft.client.resources.model.ModelBakery.class)
public abstract class ModelBakeryMixin {
    //? if >=1.21 && <26 {
    /*@org.spongepowered.asm.mixin.injection.Inject(method = "loadItemModelAndDependencies",
        at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void mcaddon$skipDormantItem(net.minecraft.resources.Identifier item,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        if (at.ioexception.mcaddon.registry.ActiveAddons.isDormant(item)) info.cancel();
    }
    *///?} else if <1.21 {
    /*@org.spongepowered.asm.mixin.injection.Inject(method = "loadTopLevel",
        at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void mcaddon$skipDormant(@org.spongepowered.asm.mixin.injection.Coerce
        net.minecraft.resources.Identifier model, org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        // A model location is the item's or the block's own identifier with a variant beside it.
        if (at.ioexception.mcaddon.registry.ActiveAddons.isDormant(
            new net.minecraft.resources.Identifier(model.getNamespace(), model.getPath()))) {
            info.cancel();
        }
    }
    *///?}
}
