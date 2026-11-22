package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.entity.Dwelling;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The `passive` dwellers in the box Java counts a village's cats in count as cats. */
@Mixin(CatSpawner.class)
public abstract class CatSpawnerMixin {
    @ModifyExpressionValue(method = "spawnInVillage", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
    private int mcaddon$countPassiveDwellers(int cats, @Local(argsOnly = true) ServerLevel level,
                                             @Local(argsOnly = true) BlockPos spawnPos) {
        return cats + level.getEntitiesOfClass(AddonEntity.class, new AABB(spawnPos).inflate(48.0, 8.0, 48.0),
            entity -> Dwelling.Role.of(entity) == Dwelling.Role.PASSIVE).size();
    }
}
