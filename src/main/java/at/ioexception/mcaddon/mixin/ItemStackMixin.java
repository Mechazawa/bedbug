package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonItems;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires `minecraft:durability_sensor` as an item of a pack's own wears down.
 *
 * Every path that spends durability on a held or worn item passes through the overload naming the
 * entity holding it, which is the one that can say where the effects belong. 1.20.1 hands the break
 * callback in directly and the newer versions name the slot instead, so the injector is written
 * twice: Mixin matches a method by its exact descriptor.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    //? if >=1.21 {
    @Inject(method = "hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;"
        + "Lnet/minecraft/world/entity/EquipmentSlot;)V", at = @At("TAIL"))
    private void mcaddon$worn(int amount, LivingEntity holder,
        net.minecraft.world.entity.EquipmentSlot slot, CallbackInfo info) {
        AddonItems.durabilityLost((ItemStack) (Object) this, holder);
    }
    //?} else {
    /*@Inject(method = "hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;"
        + "Ljava/util/function/Consumer;)V", at = @At("TAIL"))
    private void mcaddon$worn(int amount, LivingEntity holder,
        java.util.function.Consumer<LivingEntity> broke, CallbackInfo info) {
        AddonItems.durabilityLost((ItemStack) (Object) this, holder);
    }
    *///?}
}
