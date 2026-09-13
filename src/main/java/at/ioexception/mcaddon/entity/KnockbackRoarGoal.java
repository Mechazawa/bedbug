package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Roars, and throws back everything standing close enough to hear it.
 *
 * `minecraft:behavior.knockback_roar` names how long the roar lasts, how far it carries and what it
 * does to whatever it reaches. Java has no goal of its own for it: the ravager's roar is written
 * into the ravager.
 */
public final class KnockbackRoarGoal extends Goal {
    private final AddonEntity mob;
    private final int duration;
    private final double range;
    private final float damage;
    private final double strength;
    private final int cooldown;
    private final Filter caught;
    private int held;
    private int untilNext;

    public KnockbackRoarGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.duration = (int) (number(component, "duration", 1) * 20);
        this.range = number(component, "attack_range", 4);
        this.damage = (float) number(component, "damage", 6);
        this.strength = number(component, "knockback_strength", 4) * 0.15;
        this.cooldown = (int) (number(component, "cooldown_time", 4) * 20);
        this.caught = Filter.parse(component.get("damage_filters"));
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return untilNext-- <= 0 && mob.getTarget() != null && mob.distanceTo(mob.getTarget()) <= range;
    }

    @Override
    public boolean canContinueToUse() {
        return held > 0;
    }

    @Override
    public void start() {
        held = duration;
        mob.getNavigation().stop();
        mob.playSound(SoundEvents.RAVAGER_ROAR, 2f, 1f);
    }

    @Override
    public void tick() {
        held--;
        if (held > 0 || !(mob.level() instanceof ServerLevel level)) return;

        for (LivingEntity caught : level.getEntitiesOfClass(LivingEntity.class,
            mob.getBoundingBox().inflate(range))) {
            if (caught == mob || !allowed(caught)) continue;

            Platform.hurt(caught, level, mob.damageSources().mobAttack(mob), damage);
            Vec3 away = caught.position().subtract(mob.position()).normalize().scale(strength);
            caught.setDeltaMovement(caught.getDeltaMovement().add(away.x, 0.4, away.z));
            caught.hurtMarked = true;
        }
    }

    @Override
    public void stop() {
        held = 0;
        untilNext = cooldown;
    }

    private boolean allowed(LivingEntity other) {
        return caught.matches(new Filter.Context(mob, other, other, ServerHooks.state()));
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
