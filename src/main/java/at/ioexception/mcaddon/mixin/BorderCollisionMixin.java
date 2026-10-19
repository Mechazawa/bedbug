package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.block.BorderColumns;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Stops an entity at a border block's column before Java collides it with the blocks themselves.
 *
 * Every entity that collides moves through `collide`, a player on its own client as well as a mob on
 * the server, so both sides stop it at the same place. An entity without physics never gets here,
 * which is why the ender dragon crosses a border as it does in Bedrock.
 */
@Mixin(Entity.class)
public abstract class BorderCollisionMixin {
    @ModifyVariable(method = "collide", at = @At("HEAD"), argsOnly = true)
    private Vec3 mcaddon$stopAtBorder(Vec3 movement) {
        return BorderColumns.clip((Entity) (Object) this, movement);
    }
}
