package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonItems;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if >=1.21 {
import at.ioexception.mcaddon.bedrock.behaviour.BagCapacity;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//?} else {
/*import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
*///?}

/**
 * A bag holds what its `minecraft:storage_weight_limit` says, and shows itself as full against
 * that rather than against Java's one capacity.
 *
 * Limits are in Bedrock's units, of which Java's bundle holds 64. A bag holding nothing is measured
 * against one, which it never fills.
 */
@Mixin(BundleItem.class)
public abstract class BundleItemMixin {
    //? if >=1.21 {
    //? if >=26.3 {
    /*@ModifyExpressionValue(method = {"overrideStackedOnOther", "overrideOtherStackedOnMe"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/BundleContents;"
            + "asMutable()Lnet/minecraft/world/item/component/BundleContents$Mutable;"))
    *///?} else {
    @ModifyExpressionValue(method = {"overrideStackedOnOther", "overrideOtherStackedOnMe"},
        at = @At(value = "NEW", target = "Lnet/minecraft/world/item/component/BundleContents$Mutable;"))
    //?}
    private BundleContents.Mutable mcaddon$sized(BundleContents.Mutable contents,
        @Local(argsOnly = true, ordinal = 0) ItemStack bag) {
        ((BagCapacity) contents).mcaddon$holdUpTo(AddonItems.bagLimitOf(bag.getItem()));
        return contents;
    }

    @ModifyReturnValue(method = "getFullnessDisplay", at = @At("RETURN"))
    private static float mcaddon$fullness(float fullness, @Local(argsOnly = true) ItemStack bag) {
        return fullness * 64 / Math.max(1, AddonItems.bagLimitOf(bag.getItem()));
    }

    @ModifyArg(method = "getBarWidth",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;mulAndTruncate(Lorg/apache/commons/lang3/math/Fraction;I)I"))
    private Fraction mcaddon$barShare(Fraction weight, @Local(argsOnly = true) ItemStack bag) {
        return weight.multiplyBy(Fraction.getFraction(64, Math.max(1, AddonItems.bagLimitOf(bag.getItem()))));
    }
    //?} else {
    /*@ModifyConstant(method = "overrideStackedOnOther", constant = @Constant(intValue = 64))
    private int mcaddon$takeLimit(int whole, @Local(argsOnly = true, ordinal = 0) ItemStack bag) {
        return AddonItems.bagLimitOf(bag.getItem());
    }

    @ModifyConstant(method = "add", constant = @Constant(intValue = 64))
    private static int mcaddon$addLimit(int whole, @Local(argsOnly = true, ordinal = 0) ItemStack bag) {
        return AddonItems.bagLimitOf(bag.getItem());
    }

    @ModifyConstant(method = "getBarWidth", constant = @Constant(intValue = 64))
    private int mcaddon$barLimit(int whole, @Local(argsOnly = true) ItemStack bag) {
        return Math.max(1, AddonItems.bagLimitOf(bag.getItem()));
    }

    @ModifyConstant(method = "getFullnessDisplay", constant = @Constant(floatValue = 64.0f))
    private static float mcaddon$fullnessLimit(float whole, @Local(argsOnly = true) ItemStack bag) {
        return Math.max(1, AddonItems.bagLimitOf(bag.getItem()));
    }
    *///?}
}
