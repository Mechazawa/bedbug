package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.item.Repairable;
import at.ioexception.mcaddon.registry.AddonItems;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.OptionalInt;

/**
 * An anvil repairs a pack's item with what its `minecraft:repairable` entries name, by the
 * `repair_amount` the entry states.
 *
 * Java works out a material's repair as a quarter of the durability, in the two `Math.min` calls
 * of `createResult`, and a second copy of the item in the one `setDamageValue` after the material
 * loop. An item whose entry states no amount keeps Java's numbers.
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilRepairMixin {
    /**
     * A second copy of the item is left to Java's combining branch, where its entry's amount is
     * read too, though Bedrock lists the item among its own repair items.
     */
    @WrapOperation(method = "createResult", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;isValidRepairItem(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean mcaddon$accepts(ItemStack repaired, ItemStack other, Operation<Boolean> original) {
        Repairable repairs = AddonItems.repairsOf(repaired.getItem());
        if (repairs == null) return original.call(repaired, other);
        return !other.is(repaired.getItem()) && repairs.accepts(other);
    }

    @ModifyExpressionValue(method = "createResult",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
    private int mcaddon$materialRepair(int java, @Local(ordinal = 1) ItemStack result,
        @Local(ordinal = 2) ItemStack addition) {
        Repairable repairs = AddonItems.repairsOf(result.getItem());
        OptionalInt amount = repairs == null ? OptionalInt.empty() : repairs.amount(result, addition);
        return amount.isPresent() ? Math.min(result.getDamageValue(), amount.getAsInt()) : java;
    }

    @WrapOperation(method = "createResult", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;setDamageValue(I)V", ordinal = 1))
    private void mcaddon$combine(ItemStack result, int java, Operation<Void> original,
        @Local(ordinal = 2) ItemStack addition) {
        Repairable repairs = AddonItems.repairsOf(result.getItem());
        OptionalInt damage = repairs == null ? OptionalInt.empty() : repairs.combined(result, addition, 12);
        original.call(result, damage.orElse(java));
    }
}
