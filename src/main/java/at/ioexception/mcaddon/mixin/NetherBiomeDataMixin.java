package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonWorldgen;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.fabricmc.fabric.impl.biome.NetherBiomeData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds the nether's biome list without the biomes of add-ons outside the active set. */
@Mixin(value = NetherBiomeData.class, remap = false)
public abstract class NetherBiomeDataMixin {
    @ModifyExpressionValue(method = "withModdedBiomeEntries",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;entrySet()Ljava/util/Set;"))
    private static Set<Map.Entry<ResourceKey<Biome>, Climate.ParameterPoint>> mcaddon$leaveOutDormant(
        Set<Map.Entry<ResourceKey<Biome>, Climate.ParameterPoint>> added) {
        return added.stream().filter(entry -> !AddonWorldgen.isDormant(entry.getKey())).collect(Collectors.toSet());
    }
}
