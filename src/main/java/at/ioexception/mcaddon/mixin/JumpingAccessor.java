package at.ioexception.mcaddon.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Whether a living entity is holding its jump down.
 *
 * A Bedrock mount that rises when its rider jumps has to read that off the rider, and the game keeps
 * it as a field the rider's own class can see and nobody else can. 26 added a question for it; the
 * older versions have only the field, so this is how all four are asked the same way.
 */
@Mixin(LivingEntity.class)
public interface JumpingAccessor {
    @Accessor("jumping")
    boolean mcaddon$jumping();
}
