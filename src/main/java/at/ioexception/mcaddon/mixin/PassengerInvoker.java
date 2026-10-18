package at.ioexception.mcaddon.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Whether a vehicle has room for one more rider, asked apart from whether that rider may climb on.
 *
 * Java asks both in one go when an entity boards by itself, and the rider's half refuses for three
 * seconds after it last got off. Bedrock's `/ride` asks only the vehicle, so the two are asked apart.
 */
@Mixin(Entity.class)
public interface PassengerInvoker {
    @Invoker("canAddPassenger")
    boolean mcaddon$canAddPassenger(Entity passenger);

    @Invoker("couldAcceptPassenger")
    boolean mcaddon$couldAcceptPassenger();
}
