package at.ioexception.mcaddon.mixin;

import org.spongepowered.asm.mixin.Mixin;

//? if >=1.21 {
import at.ioexception.mcaddon.bedrock.behaviour.BagCapacity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
//?}

/**
 * Measures a bundle's contents against the capacity of the bag they belong to.
 *
 * Java measures every bundle against a whole, which is the 64 Bedrock's own bundle states; a bag
 * stating another `minecraft:storage_weight_limit` hands that to its contents through
 * `BagCapacity`. Before 1.21 there are no such contents, and `BundleItemMixin` reads the limit off
 * the bag itself.
 */
//? if >=1.21 {
@Mixin(BundleContents.Mutable.class)
public abstract class BundleCapacityMixin implements BagCapacity {
    @Unique
    private Fraction capacity = Fraction.ONE;

    @Override
    public void mcaddon$holdUpTo(int limit) {
        capacity = Fraction.getFraction(limit, 64);
    }

    @ModifyExpressionValue(method = "getMaxAmountToAdd",
        at = @At(value = "FIELD",
            target = "Lorg/apache/commons/lang3/math/Fraction;ONE:Lorg/apache/commons/lang3/math/Fraction;",
            opcode = Opcodes.GETSTATIC))
    private Fraction mcaddon$capacity(Fraction whole) {
        return capacity;
    }
}
//?} else {
/*@Mixin(net.minecraft.world.item.BundleItem.class)
public abstract class BundleCapacityMixin {
}
*///?}
