package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets an add-on entity carrying `minecraft:can_stand_on_powder_snow` stand on powder snow.
 *
 * Java answers from an entity type tag and the boots being worn, and a component group can bring
 * the component in partway through an entity's life, so the entity is asked first.
 */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {
    @Inject(method = "canEntityWalkOnPowderSnow", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$standOn(Entity entity, CallbackInfoReturnable<Boolean> info) {
        if (entity instanceof AddonEntity addon && addon.standsOnPowderSnow()) {
            info.setReturnValue(true);
        }
    }
}
