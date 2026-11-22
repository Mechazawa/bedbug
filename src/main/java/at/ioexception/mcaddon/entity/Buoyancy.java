package at.ioexception.mcaddon.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * How `minecraft:buoyant` holds an entity up in the liquids it names.
 *
 * Mojang publishes the fields and no arithmetic, so the motion is decided here. Each tick in a named
 * liquid lifts the entity by `base_buoyancy` times {@link #LIFT}. `bobbing` lets Java's gravity pull
 * it back once it is out, which is the bob. `none` stops it rising once its eyes are out, so it
 * holds at the surface. `waves` bobs and, at `big_wave_probability` a tick, a big wave lifts it by
 * `big_wave_speed` times the lift. `apply_gravity` false takes gravity away while it floats.
 */
final class Buoyancy {
    /** The lift a `base_buoyancy` of one gives each tick, in blocks a tick each tick. */
    static final double LIFT = 0.03;

    /** The vertical speed `LivingEntity.jumpOutOfFluid` sets as it steps a mob out onto a bank. */
    private static final double STEP_OUT = 0.30000001192092896;

    private final List<String> liquids;
    private final double lift;
    private final boolean appliesGravity;
    private final boolean stepsOut;
    private final String movement;
    private final double bigWaveChance;
    private final double bigWaveSpeed;
    private final double dragOnRemoval;

    private Buoyancy(JsonObject component) {
        this.liquids = at.ioexception.mcaddon.util.Json.list(component, "liquid_blocks").stream()
            .filter(JsonElement::isJsonPrimitive)
            .map(block -> block.getAsString().replace("minecraft:", "").replace("flowing_", ""))
            .toList();
        this.lift = Goals.number(component, "base_buoyancy", 1) * LIFT;
        this.appliesGravity = Goals.flag(component, "apply_gravity", true);
        this.stepsOut = Goals.flag(component, "can_auto_step_from_liquid", false);
        String type = Goals.text(component, "movement_type");
        this.movement = type == null ? "waves" : type;
        this.bigWaveChance = Goals.number(component, "big_wave_probability", 0.03);
        this.bigWaveSpeed = Goals.number(component, "big_wave_speed", 10);
        this.dragOnRemoval = Goals.number(component, "drag_down_on_buoyancy_removed", 0);
    }

    static Buoyancy tryFrom(JsonObject component) {
        return component == null ? null : new Buoyancy(component);
    }

    /** Whether the entity is in a liquid this names. A component naming none floats in water. */
    boolean floats(Entity entity) {
        return entity.isInWater() && named("water") || entity.isInLava() && named("lava");
    }

    private boolean named(String liquid) {
        return liquids.isEmpty() ? liquid.equals("water") : liquids.contains(liquid);
    }

    /** Whether gravity is taken away from the entity this tick. */
    boolean weightless(Entity entity) {
        return !appliesGravity && floats(entity);
    }

    /** One tick of floating, for an entity in a liquid it floats on. */
    void ride(Entity entity) {
        Vec3 moving = entity.getDeltaMovement();
        if (movement.equals("none") && !entity.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)
            && !entity.isEyeInFluid(net.minecraft.tags.FluidTags.LAVA)) {
            entity.setDeltaMovement(moving.x, Math.min(0, moving.y), moving.z);
            return;
        }

        double wave = movement.equals("waves") && entity.getRandom().nextDouble() < bigWaveChance
            ? bigWaveSpeed * lift : 0;
        entity.setDeltaMovement(moving.add(0, lift + wave, 0));
    }

    /** Undoes Java's step out onto a bank for an entity that may not take it. */
    void holdOffTheBank(Entity entity) {
        Vec3 moving = entity.getDeltaMovement();
        if (stepsOut || !entity.horizontalCollision || moving.y != STEP_OUT || !floats(entity)) return;

        entity.setDeltaMovement(moving.x, 0, moving.z);
    }

    /** Pulls the entity down as the component leaves it, by `drag_down_on_buoyancy_removed` a tick. */
    void dragDown(Entity entity) {
        if (dragOnRemoval == 0 || !floats(entity)) return;

        entity.setDeltaMovement(entity.getDeltaMovement().add(0, -dragOnRemoval, 0));
    }
}
