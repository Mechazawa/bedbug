package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Stands still, holds a target in view, winds up, and hits it from where it stands.
 *
 * A guardian's beam and a warden's sonic boom are one shape with two sets of numbers: stop, keep
 * looking, wait out the wind-up, then damage whatever is still in range without closing the gap.
 * Java writes the first into `Guardian` and the second into the warden's brain, so a data-driven
 * entity reaches neither. The wind-up, the damage and the range carry across; so does the knockback
 * the boom names, which the beam has none of.
 */
public final class BeamAttackGoal extends Goal {
    private final AddonEntity mob;
    private final int windUpTicks;
    private final int durationTicks;
    private final int cooldownTicks;
    private final double damage;
    private final double extraOnHard;
    private final boolean addsAttackDamage;
    private final double minDistance;
    private final double reachHorizontal;
    private final double reachVertical;
    private final double knockbackFlat;
    private final double knockbackRise;
    private final double knockbackCap;
    private final float headTurn;
    private final float pitchTurn;
    private final SoundEvent windUpSound;
    private final SoundEvent hitSound;
    private int held;
    private int untilNext;

    private BeamAttackGoal(AddonEntity mob, JsonObject component, int windUpTicks, int durationTicks,
        int cooldownTicks, double damage, double extraOnHard, boolean addsAttackDamage,
        double minDistance, double reachHorizontal, double reachVertical, double knockbackFlat,
        double knockbackRise, double knockbackCap, SoundEvent windUpSound, SoundEvent hitSound) {
        this.mob = mob;
        this.windUpTicks = windUpTicks;
        this.durationTicks = durationTicks;
        this.cooldownTicks = cooldownTicks;
        this.damage = damage;
        this.extraOnHard = extraOnHard;
        this.addsAttackDamage = addsAttackDamage;
        this.minDistance = minDistance;
        this.reachHorizontal = reachHorizontal;
        this.reachVertical = reachVertical;
        this.knockbackFlat = knockbackFlat;
        this.knockbackRise = knockbackRise;
        this.knockbackCap = knockbackCap;
        this.headTurn = (float) Goals.number(component, "y_max_head_rotation", 30);
        this.pitchTurn = (float) Goals.number(component, "x_max_rotation", 30);
        this.windUpSound = windUpSound;
        this.hitSound = hitSound;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /**
     * `minecraft:behavior.guardian_attack`, whose damage is added to the entity's own attack damage.
     *
     * The wind-up is stated as the delay before the attack sound, which is also when the beam lands.
     * A target that comes closer than `min_distance` ends the attack, which is what stops a guardian
     * beaming something standing on top of it.
     */
    public static BeamAttackGoal beam(AddonEntity mob, JsonObject component) {
        return new BeamAttackGoal(mob, component, Goals.ticks(component, "sound_delay_time", 4),
            Goals.ticks(component, "sound_delay_time", 4) + 1, 20,
            Goals.number(component, "magic_damage", 2),
            Goals.number(component, "hard_mode_extra_magic_damage", 2), true,
            Goals.number(component, "min_distance", 3), 16, 16, 0, 0, 0,
            SoundEvents.GUARDIAN_ATTACK, SoundEvents.GUARDIAN_HURT);
    }

    /** `minecraft:behavior.sonic_boom`, which throws what it hits as well as damaging it. */
    public static BeamAttackGoal sonicBoom(AddonEntity mob, JsonObject component) {
        return new BeamAttackGoal(mob, component,
            Goals.ticks(component, "duration_until_attack_sound", 1.7),
            Goals.ticks(component, "duration", 3), Goals.ticks(component, "attack_cooldown", 2),
            Goals.number(component, "attack_damage", 10), 0, false, 0,
            Goals.number(component, "attack_range_horizontal", 15),
            Goals.number(component, "attack_range_vertical", 20),
            Goals.number(component, "knockback_horizontal_strength", 2.5),
            Goals.number(component, "knockback_vertical_strength", 0.5),
            Goals.number(component, "knockback_height_cap", 0.5),
            SoundEvents.WARDEN_SONIC_CHARGE, SoundEvents.WARDEN_SONIC_BOOM);
    }

    @Override
    public boolean canUse() {
        if (untilNext-- > 0) return false;

        LivingEntity target = mob.getTarget();
        return inRange(target) && mob.getSensing().hasLineOfSight(target);
    }

    @Override
    public boolean canContinueToUse() {
        return held < durationTicks && inRange(mob.getTarget());
    }

    @Override
    public void start() {
        held = 0;
        mob.getNavigation().stop();
        mob.setValue("is_charging", true);
        mob.playSound(windUpSound, 1f, 1f);
    }

    @Override
    public void stop() {
        untilNext = cooldownTicks;
        mob.setValue("is_charging", false);
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;

        mob.getLookControl().setLookAt(target, headTurn, pitchTurn);
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
        if (held++ != windUpTicks) return;

        mob.playSound(hitSound, 1f, 1f);
        hit(target);
    }

    private void hit(LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        double hurtBy = damage + (addsAttackDamage ? mob.getAttributeValue(Attributes.ATTACK_DAMAGE) : 0)
            + (level.getDifficulty() == Difficulty.HARD ? extraOnHard : 0);
        Platform.hurt(target, level, mob.damageSources().indirectMagic(mob, mob), (float) hurtBy);
        if (knockbackFlat <= 0 && knockbackRise <= 0) return;

        Vec3 away = target.position().subtract(mob.position()).normalize();
        target.setDeltaMovement(target.getDeltaMovement().add(away.x * knockbackFlat,
            Math.min(knockbackCap, knockbackRise), away.z * knockbackFlat));
    }

    /** Whether the target is alive and inside the box this attack reaches, and not too close. */
    private boolean inRange(LivingEntity target) {
        if (target == null || !target.isAlive()) return false;

        double flat = Math.hypot(target.getX() - mob.getX(), target.getZ() - mob.getZ());
        return flat <= reachHorizontal && flat >= minDistance
            && Math.abs(target.getY() - mob.getY()) <= reachVertical;
    }
}
