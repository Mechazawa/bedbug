package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.VanillaEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.CelebrateVillagersSurvivedRaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Behavior.class)
public abstract class VillagerCelebrateMixin {
    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)V",
        at = @At("HEAD"))
    private void mcaddon$celebrates(ServerLevel level, LivingEntity entity, long time, CallbackInfo info) {
        if ((Object) this instanceof CelebrateVillagersSurvivedRaid) VanillaEvents.celebrating(entity);
    }
}
