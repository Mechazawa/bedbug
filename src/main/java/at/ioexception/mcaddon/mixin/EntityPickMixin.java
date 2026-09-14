package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonItems;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lets `minecraft:liquid_clipped` aim at water and lava.
 *
 * What the player is pointing at is worked out by one ray, and whether a liquid stops that ray is
 * answered the same way for every item. Bedrock asks it of the item in hand, so that is who answers
 * it here, and the block the crosshair reports becomes the liquid one. The server takes the client
 * at its word for what was pointed at, so the interaction that follows reaches that block.
 */
@Mixin(Entity.class)
public abstract class EntityPickMixin {
    @ModifyVariable(method = "pick", at = @At("HEAD"), argsOnly = true, index = 4)
    private boolean mcaddon$throughLiquid(boolean hitFluids) {
        Entity self = (Entity) (Object) this;
        if (hitFluids || !(self instanceof LivingEntity living)) return hitFluids;

        return AddonItems.clipsLiquid(living.getItemInHand(InteractionHand.MAIN_HAND));
    }
}
