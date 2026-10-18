package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Runs, dives or swims straight at what it is hunting and hits it hard enough to send it flying.
 *
 * Bedrock's ram, swoop and aquatic charge are the same attack in three mediums: back off, line the
 * target up, cross the gap at speed, and knock whatever is hit away. What differs is where the aim
 * point sits, how much of the knock is upward and how long each rests between charges, so those
 * are what each of the three names. A ram also runs up to a target further off than it charges
 * from, and makes a sound before the charge and another on the hit.
 */
public final class ChargeAttackGoal extends Goal {
    private final AddonEntity mob;
    private final int cooldownLow;
    private final int cooldownHigh;
    private final double runSpeed;
    private final String preSound;
    private final String impactSound;
    private final double chargeSpeed;
    private final double minDistance;
    private final double maxDistance;
    private final double reach;
    private final double knockbackForce;
    private final double knockbackHeight;
    private final double overshoot;
    private final boolean aims;
    private final int chargeTicks;
    private final List<String> startEvents = new ArrayList<>();
    private int readyAt;
    private int charged;
    private boolean approaching;
    private Vec3 towards;

    private ChargeAttackGoal(AddonEntity mob, JsonObject component, int[] cooldown, double runSpeed,
        double chargeSpeed,
        double minDistance, double maxDistance, double reach, double knockbackForce,
        double knockbackHeight, double overshoot, boolean aims) {
        this.mob = mob;
        this.cooldownLow = cooldown[0];
        this.cooldownHigh = cooldown[1];
        this.runSpeed = runSpeed;
        this.preSound = Goals.text(component, "pre_ram_sound");
        this.impactSound = Goals.text(component, "ram_impact_sound");
        this.chargeSpeed = chargeSpeed;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
        this.reach = reach;
        this.knockbackForce = knockbackForce;
        this.knockbackHeight = knockbackHeight;
        this.overshoot = overshoot;
        this.aims = aims;
        this.chargeTicks = (int) Math.min(100, Math.max(20, maxDistance / Math.max(0.01, chargeSpeed)));
        for (JsonElement element : Json.list(component, "on_start")) {
            String event = element.isJsonPrimitive() ? element.getAsString()
                : element.isJsonObject() ? Json.text(element.getAsJsonObject(), "event", null) : null;
            if (event != null) startEvents.add(event);
        }
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.ram_attack`, which is a goat's shoulder at running speed. */
    public static ChargeAttackGoal ramming(AddonEntity mob, JsonObject component) {
        return new ChargeAttackGoal(mob, component, new int[] {Goals.ticksLow(component, "cooldown_range", 10),
                Goals.ticksHigh(component, "cooldown_range", 20)},
            Goals.number(component, "run_speed", 1), Goals.number(component, "ram_speed", 2) * 0.05,
            Goals.number(component, "min_ram_distance", 4),
            Goals.number(component, "ram_distance", 7),
            Goals.number(component, "attack_reach", 0.2),
            Goals.number(component, "knockback_force", 5) * 0.1,
            Goals.number(component, "knockback_height", 0.1), 0, false);
    }

    /** `minecraft:behavior.swoop_attack`, which is the same run made from above. */
    public static ChargeAttackGoal swooping(AddonEntity mob, JsonObject component) {
        return new ChargeAttackGoal(mob, component, new int[] {Goals.ticksLow(component, "delay_range", 10),
                Goals.ticksHigh(component, "delay_range", 20)},
            0, Goals.number(component, "speed_multiplier", 1) * 0.08, 0, 32,
            Goals.number(component, "damage_reach", 0.2), 0.3, 0.2, 1, true);
    }

    /** `minecraft:behavior.aquatic_charge_attack`, which aims past the target and swims through it. */
    public static ChargeAttackGoal chargingUnderwater(AddonEntity mob, JsonObject component) {
        return new ChargeAttackGoal(mob, component,
            new int[] {Goals.ticksLow(component, "charge_cooldown_time", 2),
                Goals.ticksHigh(component, "charge_cooldown_time", 6)},
            0, Goals.number(component, "charge_speed", 0.06), 0,
            Goals.number(component, "max_charge_distance", 16),
            Goals.number(component, "attack_reach", 0.05),
            Goals.number(component, "knockback_force", 2) * 0.1, 0.1,
            Goals.number(component, "charge_overshoot_distance", 1.5), true);
    }

    @Override
    public boolean canUse() {
        if (mob.tickCount < readyAt) return false;

        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        double away = mob.distanceTo(target);
        return away >= minDistance && (away <= maxDistance || runSpeed > 0);
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        return (approaching || towards != null) && target != null && target.isAlive() && charged < chargeTicks;
    }

    @Override
    public void start() {
        charged = 0;
        approaching = true;
    }

    /** Lines the target up and sets off, once a ram has run up to where it charges from. */
    private void charge(LivingEntity target) {
        approaching = false;
        mob.setValue("is_ram_attacking", true);
        Vec3 aim = target.position();
        if (overshoot > 0) {
            aim = aim.add(target.position().subtract(mob.position()).normalize().scale(overshoot));
        }
        towards = aim.subtract(mob.position()).normalize();
        if (!aims) towards = new Vec3(towards.x, 0, towards.z).normalize();

        mob.getNavigation().stop();
        mob.makeNoise(preSound);
        startEvents.forEach(event -> EntityEvents.trigger(mob, event, target, ServerHooks.state()));
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;
        if (approaching) {
            if (mob.distanceTo(target) > maxDistance) {
                mob.getNavigation().moveTo(target, runSpeed);
                return;
            }
            charge(target);
        }
        if (towards == null) return;

        charged++;
        mob.getLookControl().setLookAt(target, 30f, 30f);
        Vec3 push = towards.scale(chargeSpeed);
        mob.setDeltaMovement(push.x, aims ? push.y : mob.getDeltaMovement().y, push.z);

        if (mob.distanceTo(target) > mob.getBbWidth() + target.getBbWidth() + reach) return;

        hit(target);
        towards = null;
    }

    @Override
    public void stop() {
        mob.setValue("is_ram_attacking", false);
        readyAt = mob.tickCount + Goals.between(mob.getRandom(), cooldownLow, cooldownHigh);
        approaching = false;
        towards = null;
    }

    private void hit(LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        Platform.hurt(target, level, mob.damageSources().mobAttack(mob),
            (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE));
        mob.makeNoise(impactSound);
        Vec3 away = target.position().subtract(mob.position()).normalize();
        target.setDeltaMovement(target.getDeltaMovement().add(
            away.x * knockbackForce, knockbackHeight, away.z * knockbackForce));
    }
}
