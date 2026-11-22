package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.Dwelling;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.GolemSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager that sees a `defender` dweller wants no iron golem, as one that sees a golem does. */
@Mixin(GolemSensor.class)
public abstract class GolemSensorMixin {
    @Inject(method = "checkForNearbyGolem", at = @At("TAIL"))
    private static void mcaddon$countDefenders(LivingEntity body, CallbackInfo info) {
        body.getBrain().getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES)
            .filter(seen -> seen.stream().anyMatch(entity -> Dwelling.Role.of(entity) == Dwelling.Role.DEFENDER))
            .ifPresent(seen -> GolemSensor.golemDetected(body));
    }
}
