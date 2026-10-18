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
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Stands its ground and throws the projectile the pack named.
 *
 * `minecraft:behavior.fire_at_target` differs from Bedrock's ranged attack in that the entity does
 * not close the distance: it winds up where it stands, lets go, and waits. The projectile is named by
 * entity id, which is enough to build one and aim it. It leaves from `owner_anchor` on the shooter,
 * moved by `owner_offset`, toward `target_anchor` on the target moved by `target_offset`, each anchor
 * numbered as a projectile's own: the feet, the eyes or the middle of the box. The entity starts only
 * at a target inside its `ranged_fov`, measured across the ground from where its head faces.
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
    private final int ownerAnchor;
    private final Vec3 ownerOffset;
    private final int targetAnchor;
    private final Vec3 targetOffset;
    private final double halfFieldOfView;
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
        this.ownerAnchor = (int) Goals.number(component, "owner_anchor", 2);
        this.ownerOffset = offset(component, "owner_offset");
        this.targetAnchor = (int) Goals.number(component, "target_anchor", 2);
        this.targetOffset = offset(component, "target_offset");
        this.halfFieldOfView = Goals.number(component, "ranged_fov", 90) / 2;
        setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (untilNext-- > 0 || projectile == null) return false;

        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        double away = mob.distanceTo(target);
        if (away < rangeLow || (rangeHigh > 0 && away > rangeHigh) || !Goals.sees(mob, target, halfFieldOfView)) {
            return false;
        }

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
        Vec3 from = anchor(mob, ownerAnchor).add(ownerOffset);
        Vec3 aim = anchor(target, targetAnchor).add(targetOffset).subtract(from);
        if (shot instanceof AddonEntity own) {
            Platform.placeAt(own, from.x, from.y, from.z, mob.getYRot(), mob.getXRot());
            own.launchAsProjectile(mob, aim);
            level.addFreshEntity(own);
            return;
        }
        if (!(shot instanceof Projectile flying)) return;

        Platform.placeAt(flying, from.x, from.y, from.z, mob.getYRot(), mob.getXRot());
        flying.setOwner(mob);
        flying.shoot(aim.x, aim.y, aim.z, 1.6f, 2f);
        level.addFreshEntity(flying);
    }

    private static Vec3 anchor(Entity entity, int which) {
        return entity.position().add(0, switch (which) {
            case 0 -> 0;
            case 1 -> entity.getEyeHeight();
            default -> entity.getBbHeight() / 2;
        }, 0);
    }

    private static Vec3 offset(JsonObject component, String key) {
        java.util.List<com.google.gson.JsonElement> written = at.ioexception.mcaddon.util.Json.list(component, key);
        return written.size() < 3 ? Vec3.ZERO
            : new Vec3(written.get(0).getAsDouble(), written.get(1).getAsDouble(), written.get(2).getAsDouble());
    }

}
