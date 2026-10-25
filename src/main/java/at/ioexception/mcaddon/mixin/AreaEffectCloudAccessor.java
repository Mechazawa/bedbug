package at.ioexception.mcaddon.mixin;

import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The two cloud fields Java sets only from saved data: the gap between applications and who is skipped. */
@Mixin(AreaEffectCloud.class)
public interface AreaEffectCloudAccessor {
    @Accessor("reapplicationDelay")
    void mcaddon$reapplicationDelay(int ticks);

    @Accessor("victims")
    Map<Entity, Integer> mcaddon$victims();
}
