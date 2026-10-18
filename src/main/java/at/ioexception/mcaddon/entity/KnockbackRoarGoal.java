package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Roars, and throws back everything standing close enough to hear it.
 *
 * `minecraft:behavior.knockback_roar` holds the entity for `duration` seconds and strikes
 * `attack_time` seconds in: everything within `knockback_range` that `knockback_filters` accepts is
 * thrown back, and everything `damage_filters` accepts takes `knockback_damage`. Java has no goal of
 * its own for it: the ravager's roar is written into the ravager, which roars when its stun ends
 * whether or not it has a target, and so does this.
 */
public final class KnockbackRoarGoal extends Goal {
    private final AddonEntity mob;
    private final int duration;
    private final int strikesAt;
    private final double range;
    private final float damage;
    private final double horizontal;
    private final double vertical;
    private final double heightCap;
    private final int cooldown;
    private final Filter thrown;
    private final Filter hurt;
    private final String onEnd;
    private int held;
    private int untilNext;

    public KnockbackRoarGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.duration = Goals.ticks(component, "duration", 1);
        this.strikesAt = duration - Goals.ticks(component, "attack_time", 0.5);
        this.range = Goals.number(component, "knockback_range", 4);
        this.damage = (float) Goals.number(component, "knockback_damage", 6);
        this.horizontal = Goals.number(component, "knockback_horizontal_strength", 4);
        this.vertical = Goals.number(component, "knockback_vertical_strength", 4);
        this.heightCap = Goals.number(component, "knockback_height_cap", 0.4);
        this.cooldown = Goals.ticks(component, "cooldown_time", 0.1);
        this.thrown = Goals.filter(component, "knockback_filters");
        this.hurt = Goals.filter(component, "damage_filters");
        this.onEnd = Goals.event(component, "on_roar_end");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return untilNext-- <= 0;
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
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        held--;
        if (held == strikesAt && mob.level() instanceof ServerLevel level) strike(level);
        if (held == 0 && onEnd != null) mob.raiseEvent(onEnd);
    }

    /**
     * Bedrock's knockback halves what the entity was already doing, adds the push along the line away
     * from the roar and the lift, and caps the rise, all scaled down by its knockback resistance.
     */
    private void strike(ServerLevel level) {
        for (LivingEntity caught : level.getEntitiesOfClass(LivingEntity.class,
            mob.getBoundingBox().inflate(range), other -> other != mob && mob.distanceTo(other) <= range)) {
            Filter.Context context = new Filter.Context(mob, caught, caught, ServerHooks.state());
            if (hurt.matches(context)) Platform.hurt(caught, level, mob.damageSources().mobAttack(mob), damage);
            if (!thrown.matches(context)) continue;

            double kept = 1 - caught.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            Vec3 away = new Vec3(caught.getX() - mob.getX(), 0, caught.getZ() - mob.getZ()).normalize();
            Vec3 was = caught.getDeltaMovement().scale(0.5);
            caught.setDeltaMovement(was.x + away.x * horizontal * kept,
                Math.min(heightCap, was.y + vertical * kept), was.z + away.z * horizontal * kept);
            caught.hurtMarked = true;
        }
    }

    @Override
    public void stop() {
        held = 0;
        untilNext = cooldown;
    }
}
