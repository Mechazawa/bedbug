package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.VanillaEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.animal.sheep.Sheep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Sheep.class, SnowGolem.class, CopperGolem.class})
public abstract class ShearEventMixin {
    @Inject(method = "shear", at = @At("TAIL"))
    private void mcaddon$sheared(CallbackInfo info) {
        VanillaEvents.sheared((Entity) (Object) this);
    }
}
