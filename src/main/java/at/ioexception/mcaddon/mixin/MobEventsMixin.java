package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.VanillaEvents;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobEventsMixin {
    @Inject(method = "finalizeSpawn", at = @At("TAIL"))
    private void mcaddon$spawned(net.minecraft.world.level.ServerLevelAccessor level,
        net.minecraft.world.DifficultyInstance difficulty, EntitySpawnReason reason,
        net.minecraft.world.entity.SpawnGroupData group,
        CallbackInfoReturnable<net.minecraft.world.entity.SpawnGroupData> info) {
        VanillaEvents.spawned((Mob) (Object) this, reason);
    }

    @Inject(method = "convertTo(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/ConversionParams;"
        + "Lnet/minecraft/world/entity/EntitySpawnReason;"
        + "Lnet/minecraft/world/entity/ConversionParams$AfterConversion;)Lnet/minecraft/world/entity/Mob;",
        at = @At("RETURN"))
    private void mcaddon$transformed(CallbackInfoReturnable<Mob> info) {
        if (info.getReturnValue() != null) VanillaEvents.transformed(info.getReturnValue());
    }
}
