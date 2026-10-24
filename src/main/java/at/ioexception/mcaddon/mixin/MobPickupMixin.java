package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Tells a pack's script when a mob that picks up loot takes an item off the ground. */
@Mixin(Mob.class)
public abstract class MobPickupMixin {
    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Mob;pickUpItem(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/item/ItemEntity;)V"))
    private void mcaddon$pickedUp(Mob mob, ServerLevel level, ItemEntity item, Operation<Void> original) {
        WorldEvents.pickUp(mob, item, () -> {
            original.call(mob, level, item);
            return true;
        });
    }
}
