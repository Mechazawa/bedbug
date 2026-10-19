package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if >=1.21 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;
//?} else {
/*import net.minecraft.world.item.BundleItem;
import org.spongepowered.asm.mixin.Shadow;
*///?}
//? if >=26 {
import com.mojang.serialization.DataResult;
import net.minecraft.world.item.ItemInstance;
//?} else {
/*import net.minecraft.world.item.ItemStack;
*///?}

/**
 * What one of an item weighs in a bag, where its `minecraft:storage_weight_modifier` says.
 *
 * The weight is in Bedrock's units, of which a bag holds 64 and Java's bundle a whole, and a bag
 * stating one still carries what is inside it on top, as Java's bundle does.
 */
//? if >=1.21 {
@Mixin(BundleContents.class)
//?} else {
/*@Mixin(BundleItem.class)
*///?}
public abstract class BundleWeightMixin {
    //? if >=26 {
    @Inject(method = "getWeight", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$statedWeight(ItemInstance stack, CallbackInfoReturnable<DataResult<Fraction>> info) {
        int weight = AddonItems.bagWeightOf(stack.typeHolder().value());
        if (weight < 0) return;

        Fraction own = Fraction.getFraction(weight, 64);
        BundleContents held = stack.get(DataComponents.BUNDLE_CONTENTS);
        info.setReturnValue(held == null ? DataResult.success(own) : held.weight().map(own::add));
    }
    //?} else if >=1.21 {
    /*@Inject(method = "getWeight", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$statedWeight(ItemStack stack, CallbackInfoReturnable<Fraction> info) {
        int weight = AddonItems.bagWeightOf(stack.getItem());
        if (weight < 0) return;

        Fraction own = Fraction.getFraction(weight, 64);
        BundleContents held = stack.get(DataComponents.BUNDLE_CONTENTS);
        info.setReturnValue(held == null ? own : own.add(held.weight()));
    }
    *///?} else {
    /*@Shadow
    private static int getContentWeight(ItemStack bag) {
        throw new AssertionError();
    }

    @Inject(method = "getWeight", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$statedWeight(ItemStack stack, CallbackInfoReturnable<Integer> info) {
        int weight = AddonItems.bagWeightOf(stack.getItem());
        if (weight >= 0) info.setReturnValue(weight + getContentWeight(stack));
    }
    *///?}
}
