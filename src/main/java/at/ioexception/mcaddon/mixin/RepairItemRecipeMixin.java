package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.item.RepairAmounts;
import at.ioexception.mcaddon.registry.AddonItems;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RepairItemRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.OptionalInt;

/** Two of a pack's item combined in the crafting grid repair by the `repair_amount` its entry states. */
@Mixin(RepairItemRecipe.class)
public abstract class RepairItemRecipeMixin {
    @WrapOperation(method = "assemble", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;setDamageValue(I)V"))
    private void mcaddon$combine(ItemStack made, int java, Operation<Void> original,
        @Local(ordinal = 0) ItemStack first, @Local(ordinal = 1) ItemStack second) {
        RepairAmounts repairs = AddonItems.repairsOf(first.getItem());
        OptionalInt damage = repairs == null ? OptionalInt.empty() : repairs.combined(first, second, 5);
        original.call(made, damage.orElse(java));
    }
}
