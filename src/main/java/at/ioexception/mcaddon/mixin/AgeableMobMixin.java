package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a pack's script that one of the game's own young mobs grew up, by the event its
 * `minecraft:ageable` names in Bedrock's own entity file.
 *
 * A pack drawing its own model over a vanilla mob makes that model young from the mob's
 * `minecraft:is_baby` as it spawns, and grown again only when this event reaches it.
 */
@Mixin(AgeableMob.class)
public abstract class AgeableMobMixin {
    @Inject(method = "setAge", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/AgeableMob;ageBoundaryReached()V"))
    private void mcaddon$growUp(int age, CallbackInfo info) {
        AgeableMob mob = (AgeableMob) (Object) this;
        if (age < 0 || !(mob.level() instanceof ServerLevel)) return;

        EntityEvents.raiseDataDriven(mob, switch (AddonEntities.identifierOf(mob.getType())) {
            case "minecraft:rabbit" -> "grow_up";
            case "minecraft:dolphin" -> "ageable_grow_up";
            default -> "minecraft:ageable_grow_up";
        });
    }
}
