package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.EnumSet;

/**
 * Stands its ground and throws the projectile the pack named.
 *
 * `minecraft:behavior.fire_at_target` differs from Bedrock's ranged attack in that the entity does
 * not close the distance: it winds up where it stands, lets go, and waits. The projectile is named by
 * entity id, which is enough to build one and aim it.
 */
public final class FireAtTargetGoal extends Goal {
    private final AddonEntity mob;
    private final int cooldown;
    private final int preShoot;
    private final int postShoot;
    private final double rangeLow;
    private final double rangeHigh;
    private final Filter conditions;
    private final String projectile;
    private int held;
    private int untilNext;
    private boolean fired;

    public FireAtTargetGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.cooldown = Goals.ticks(component, "attack_cooldown", 0.5);
        this.preShoot = Goals.ticks(component, "pre_shoot_delay", 0.75);
        this.postShoot = Goals.ticks(component, "post_shoot_delay", 0.2);
        this.rangeLow = Goals.low(component, "attack_range", 0);
        this.rangeHigh = Goals.high(component, "attack_range", 16);
        this.conditions = Goals.filter(component, "filters");
        this.projectile = Goals.text(component, "projectile_def");
        setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (untilNext-- > 0 || projectile == null) return false;

        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        double away = mob.distanceTo(target);
        if (away < rangeLow || (rangeHigh > 0 && away > rangeHigh)) return false;

        return conditions.matches(new Filter.Context(mob, target, target, ServerHooks.state()));
    }

    @Override
    public boolean canContinueToUse() {
        return held < preShoot + postShoot && mob.getTarget() != null;
    }

    @Override
    public void start() {
        held = 0;
        fired = false;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;

        mob.getLookControl().setLookAt(target, 30f, 30f);
        if (++held < preShoot || fired) return;

        fired = true;
        shoot(target);
    }

    @Override
    public void stop() {
        untilNext = cooldown;
    }

    private void shoot(LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        EntityType<?> type = AddonEntities.typeOf(projectile);
        Entity shot = type == null ? null : Platform.createMob(type, level);
        if (!(shot instanceof Projectile flying)) return;

        Platform.placeAt(flying, mob.getX(), mob.getEyeY() - 0.1, mob.getZ(),
            mob.getYRot(), mob.getXRot());
        flying.setOwner(mob);
        flying.shoot(target.getX() - mob.getX(), target.getY(0.5) - flying.getY(),
            target.getZ() - mob.getZ(), 1.6f, 2f);
        level.addFreshEntity(flying);
    }
}
