package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonWorldgen;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.impl.biome.TheEndBiomeData;
import net.fabricmc.fabric.impl.biome.WeightedPicker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the end's biome choices without the biomes of add-ons outside the active set.
 *
 * Fabric's weighted pickers cannot drop an entry, so a dormant biome is resolved as the biome it
 * replaces: every pick that would have landed on it lands on that biome instead.
 */
@Mixin(value = TheEndBiomeData.Overrides.class, remap = false)
public abstract class TheEndBiomeOverridesMixin {
    @ModifyExpressionValue(method = "<init>", at = @At(value = "FIELD",
        target = "Lnet/fabricmc/fabric/impl/biome/TheEndBiomeData;ADDED_BIOMES:Ljava/util/Set;"))
    private Set<ResourceKey<Biome>> mcaddon$leaveOutDormant(Set<ResourceKey<Biome>> added) {
        return added.stream().filter(key -> !AddonWorldgen.isDormant(key)).collect(Collectors.toSet());
    }

    @WrapOperation(method = "resolveOverrides", at = @At(value = "INVOKE",
        target = "Lnet/fabricmc/fabric/impl/biome/WeightedPicker;map(Ljava/util/function/Function;)"
            + "Lnet/fabricmc/fabric/impl/biome/WeightedPicker;"))
    private WeightedPicker<?> mcaddon$resolveDormantAsReplaced(WeightedPicker<ResourceKey<Biome>> variants,
        Function<ResourceKey<Biome>, Object> resolve, Operation<WeightedPicker<?>> original,
        @Local Map.Entry<ResourceKey<Biome>, ?> replaced) {
        Function<ResourceKey<Biome>, Object> standIn =
            key -> resolve.apply(AddonWorldgen.isDormant(key) ? replaced.getKey() : key);
        return original.call(variants, standIn);
    }
}
