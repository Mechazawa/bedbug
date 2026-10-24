package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.BedrockVillager;
import at.ioexception.mcaddon.server.EntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a pack's script that a villager took a job, by the `minecraft:become_*` event Bedrock's own
 * villager raises then.
 *
 * A villager read from its save sets its data before its first tick, and Bedrock raises nothing for
 * a villager that only loaded, so a villager that has not ticked yet raises nothing either.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {
    @Inject(method = "setVillagerData", at = @At("HEAD"))
    private void mcaddon$becomeProfession(VillagerData data, CallbackInfo info) {
        Villager villager = (Villager) (Object) this;
        if (villager.tickCount == 0 || !(villager.level() instanceof ServerLevel)) return;

        String event = BedrockVillager.of(data, villager.getUUID()).eventFrom(BedrockVillager.tryFrom(villager));
        if (event != null) EntityEvents.raiseDataDriven(villager, event);
    }

    @Inject(method = "getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;"
        + "Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/npc/villager/Villager;",
        at = @At("RETURN"))
    private void mcaddon$bred(ServerLevel level, net.minecraft.world.entity.AgeableMob partner,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Villager> info) {
        if (info.getReturnValue() != null) at.ioexception.mcaddon.server.VanillaEvents.bred(info.getReturnValue());
    }
}
