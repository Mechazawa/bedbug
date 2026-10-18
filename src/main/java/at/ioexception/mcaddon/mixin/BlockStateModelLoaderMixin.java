package at.ioexception.mcaddon.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Leaves out the block state models of a dormant add-on's blocks on 1.21, for the reason
 * `ModelBakeryMixin` gives. 1.20.1 has no such class and asks the bakery itself.
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.resources.model.BlockStateModelLoader")
public abstract class BlockStateModelLoaderMixin {
    //? if >=1.21 && <26 {
    /*@org.spongepowered.asm.mixin.injection.Inject(method = "loadBlockStateDefinitions",
        at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private void mcaddon$skipDormantBlock(net.minecraft.resources.Identifier block,
        net.minecraft.world.level.block.state.StateDefinition<?, ?> states,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        if (at.ioexception.mcaddon.registry.ActiveAddons.isDormant(block)) info.cancel();
    }
    *///?}
}
