package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;

/**
 * `minecraft:movement.sway`: Java's smooth swimming with the side to side sway of a swimming fish.
 *
 * Mojang publishes no formula, so while the entity swims towards somewhere it is pushed across the
 * way there by `sway_amplitude` blocks a tick at the peak of a sine whose phase moves `sway_frequency`
 * radians a tick.
 */
final class SwayMoveControl extends SmoothSwimmingMoveControl {
    private final double amplitude;
    private final double frequency;

    SwayMoveControl(Mob mob, JsonObject component) {
        super(mob, 85, 10, 0.02f, 0.1f, true);
        this.amplitude = Goals.number(component, "sway_amplitude", 0.05);
        this.frequency = Goals.number(component, "sway_frequency", 0.5);
    }

    @Override
    public void tick() {
        boolean swimming = operation == Operation.MOVE_TO && mob.isInWater();
        double towardsX = wantedX - mob.getX();
        double towardsZ = wantedZ - mob.getZ();
        super.tick();
        double distance = Math.hypot(towardsX, towardsZ);
        if (!swimming || amplitude == 0 || distance < 1.0E-4) return;

        double push = amplitude * Math.sin(mob.tickCount * frequency) / distance;
        mob.setDeltaMovement(mob.getDeltaMovement().add(-towardsZ * push, 0, towardsX * push));
    }
}
