package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * `minecraft:projectile` flying, and what it does where it lands.
 *
 * Java keeps flying and hitting in `Projectile`, a class a mob cannot also be, and none of what the
 * component describes needs that class: a ballistic arc is a velocity scaled by an inertia each
 * tick with a gravity taken off it, and a hit is the ray between two positions,
 * `ProjectileUtil.getHitResultOnMoveVector`, which asks about blocks and entities alike. Both are
 * done here on the entity the pack declared, so an entity a pack shoots out of
 * `minecraft:shooter` behaves like the projectile its own definition says it is.
 */
public final class Projectiles {
    private static final double DEFAULT_POWER = 1.3;
    private static final double DEFAULT_GRAVITY = 0.05;
    private static final double DEFAULT_INERTIA = 0.99;
    private static final double DEFAULT_LIQUID_INERTIA = 0.6;
    private static final double DEFAULT_ON_FIRE_SECONDS = 5;
    private static final int DEFAULT_OWNER_IMMUNITY_TICKS = 5;
    private static final double HOMING_PULL = 0.2;

    private final AddonEntity mob;
    private final JsonObject body;
    private final JsonObject onHit;
    private final Set<String> ignored = new LinkedHashSet<>();

    /** What the projectile has already hit, which it passes through from then on. */
    private final Set<Entity> struck = new java.util.HashSet<>();

    /** Whoever fired it, which is what its own damage names and what it may not hit at first. */
    private LivingEntity shooter;
    private int flownFor;
    private boolean stuck;

    private Projectiles(AddonEntity mob, JsonObject body) {
        this.mob = mob;
        this.body = body;
        JsonElement hits = body.get("on_hit");
        this.onHit = hits != null && hits.isJsonObject() ? hits.getAsJsonObject() : new JsonObject();
        for (JsonElement element : Json.list(body, "ignored_entities")) {
            if (element.isJsonPrimitive()) ignored.add(element.getAsString());
        }
    }

    /** Null for an entity whose pack never said it was a projectile. */
    public static Projectiles tryFrom(AddonEntity mob) {
        return mob.declares("minecraft:projectile")
            ? new Projectiles(mob, mob.componentBody("minecraft:projectile")) : null;
    }

    /** Where on the shooter the projectile starts, which Bedrock names by number. */
    public Vec3 muzzle(LivingEntity from) {
        double height = switch ((int) Json.number(body, "anchor", 0)) {
            case 1 -> from.getEyeHeight();
            case 2 -> from.getBbHeight() / 2;
            default -> 0;
        };
        java.util.List<JsonElement> offset = Json.list(body, "offset");
        Vec3 shift = offset.size() < 3 ? Vec3.ZERO
            : new Vec3(offset.get(0).getAsDouble(), offset.get(1).getAsDouble(),
                offset.get(2).getAsDouble());
        return from.position().add(0, height, 0).add(shift);
    }

    public LivingEntity shooter() {
        return shooter;
    }

    /** Whether the shot is aimed at what the shooter is hunting rather than straight ahead. */
    public boolean aimsAtTarget() {
        return Json.bool(body, "shoot_target", true);
    }

    /**
     * Sends the projectile off along an aim, spread by the inaccuracy the pack asked for.
     *
     * `angle_offset` tilts the whole shot up, and the two uncertainty numbers widen it the way
     * Bedrock widens a skeleton's: a base the difficulty then eats into.
     */
    public void launch(LivingEntity from, Vec3 aim, float difficulty) {
        shooter = from;
        double spread = Math.max(0, Json.number(body, "uncertainty_base", 0)
            - difficulty * Json.number(body, "uncertainty_multiplier", 0));
        double tilt = Math.toRadians(Json.number(body, "angle_offset", 0));
        Vec3 pointed = aim.normalize().add(0, Math.sin(tilt), 0).normalize()
            .scale(Json.number(body, "power", DEFAULT_POWER));
        net.minecraft.util.RandomSource random = mob.getRandom();
        mob.setDeltaMovement(pointed.add(
            random.triangle(0, spread * 0.0075), random.triangle(0, spread * 0.0075),
            random.triangle(0, spread * 0.0075)));
        mob.setNoGravity(true);
        play(Json.text(body, "shoot_sound", null));
    }

    /**
     * One tick of flight: the ray to where it is going, then the arc.
     *
     * A projectile stuck in the ground stops asking either question, which is what
     * `stick_in_ground` means and what keeps an arrow lying in a wall rather than sliding down it.
     */
    public void tick() {
        if (stuck || !(mob.level() instanceof ServerLevel level)) return;

        flownFor++;
        HitResult hit = sweep(level, ProjectileUtil.getHitResultOnMoveVector(mob, this::mayHit));
        if (hit.getType() != HitResult.Type.MISS) strike(level, hit);
        if (stuck || mob.isRemoved()) return;

        Vec3 movement = mob.getDeltaMovement();
        if (Json.bool(body, "homing", false) && mob.getTarget() != null) {
            movement = movement.add(mob.getTarget().getEyePosition().subtract(mob.position())
                .normalize().scale(HOMING_PULL * movement.length()));
        }
        // `isolated_physics` keeps the arc off the path every other entity moves along. Turned
        // off, the projectile is moved the way Java moves any entity: stopped by what it collides
        // with and slowed by the blocks that hold back whatever is inside them, such as a cobweb.
        if (Json.bool(body, "isolated_physics", true)) {
            mob.setPos(mob.position().add(movement));
        } else {
            mob.move(net.minecraft.world.entity.MoverType.SELF, movement);
        }
        double inertia = mob.isInWater()
            ? Json.number(body, "liquid_inertia", DEFAULT_LIQUID_INERTIA)
            : Json.number(body, "inertia", DEFAULT_INERTIA);
        mob.setDeltaMovement(movement.scale(inertia)
            .subtract(0, Json.number(body, "gravity", DEFAULT_GRAVITY), 0));
    }

    /**
     * The nearest entity the projectile's box sweeps through this tick, short of any block the ray
     * met. Bedrock hits with the box, and the ray from the projectile's feet alone lets it fly
     * straight through a mob standing in its way.
     */
    private HitResult sweep(ServerLevel level, HitResult ray) {
        if (ray.getType() == HitResult.Type.ENTITY) return ray;

        Vec3 from = mob.position();
        double blocked = ray.getType() == HitResult.Type.MISS
            ? Double.MAX_VALUE : ray.getLocation().distanceToSqr(from);
        return level.getEntities(mob, mob.getBoundingBox().expandTowards(mob.getDeltaMovement()), this::mayHit)
            .stream()
            .filter(other -> other.distanceToSqr(from) < blocked)
            .min(java.util.Comparator.comparingDouble(other -> other.distanceToSqr(from)))
            .<HitResult>map(EntityHitResult::new)
            .orElse(ray);
    }

    /**
     * Whether the projectile registers a hit on this entity.
     *
     * `ignored_entities` names what it passes through, and a shooter is safe from its own shot for
     * the few ticks it takes to leave the barrel, which is what stops a bow hurting its holder.
     */
    private boolean mayHit(Entity other) {
        if (other == mob || !other.isPickable() || struck.contains(other)) return false;
        if (other == shooter
            && flownFor <= Json.number(body, "owner_launch_immunity_ticks",
                DEFAULT_OWNER_IMMUNITY_TICKS)) {
            return false;
        }
        Identifier named = BuiltInRegistries.ENTITY_TYPE.getKey(other.getType());
        return !ignored.contains(named.toString()) && !ignored.contains(named.getPath());
    }

    /**
     * What a blow does to the projectile in flight, answering whether the blow is spent on that
     * rather than on hurting it. The component is read from the live set, so a group can change it.
     *
     * `destroy_on_hurt` removes the projectile and `stop_on_hurt` stops it dead. `reflect_on_hurt`
     * sends it off the way the attacker faces at the speed it had, the attacker's own from then on,
     * once `reflect_immunity` seconds of flight have passed, which is how a ghast's fireball is
     * batted back; Java's own fireballs turn the same way. `crit_particle_on_hurt` marks the blow
     * with a critical hit's sparks.
     */
    public boolean struck(net.minecraft.world.damagesource.DamageSource source) {
        JsonObject live = mob.liveComponent("minecraft:projectile");
        if (live == null || !(mob.level() instanceof ServerLevel level)) return false;

        if (Json.bool(live, "crit_particle_on_hurt", false)) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, mob.getX(), mob.getY(0.5),
                mob.getZ(), HURT_SPARKS, 0.1, 0.1, 0.1, 0);
        }
        if (Json.bool(live, "destroy_on_hurt", false)) {
            mob.discard();
            return true;
        }
        if (Json.bool(live, "stop_on_hurt", false)) {
            mob.setDeltaMovement(Vec3.ZERO);
            return true;
        }

        Entity attacker = source.getEntity();
        if (attacker == null || !Json.bool(live, "reflect_on_hurt", false)) return false;
        if (flownFor < Json.number(live, "reflect_immunity", 0) * Goals.TICKS_PER_SECOND) return false;

        mob.setDeltaMovement(attacker.getLookAngle().scale(mob.getDeltaMovement().length()));
        if (attacker instanceof LivingEntity living) shooter = living;
        return true;
    }

    /** How many sparks a blow on a projectile throws off, which is what Java throws off a crit. */
    private static final int HURT_SPARKS = 8;

    private void strike(ServerLevel level, HitResult hit) {
        if (hit instanceof EntityHitResult on && on.getEntity() instanceof LivingEntity target) {
            struck.add(target);
            hitEntity(target);
        } else if (hit instanceof BlockHitResult on) {
            hitBlock(on);
        }
        scatterParticles(level, hit, hit instanceof EntityHitResult);
        grantExperience(level, hit.getLocation());
        fireEvent(hit);
        if (onHit.has("remove_on_hit")) mob.discard();
    }

    /**
     * Hurts what the projectile hit, then sends it on, back or down.
     *
     * Under `should_bounce` it bounces back off an entity the blow did not hurt, or off one no blow
     * can, at a tenth of its speed as Java's arrow does. Otherwise it stops at the first entity it
     * hits, dropping where it struck as Java's trident does, unless `multiple_targets` lets it fly
     * on to the next.
     */
    private void hitEntity(LivingEntity struck) {
        play(Json.text(body, "hit_sound", null));
        applyEffect(struck);
        JsonObject damage = onHit.getAsJsonObject("impact_damage");
        boolean hurt = damage != null && damaged(damage, struck);
        if (damage == null || hurt) {
            burn(struck, Json.bool(body, "catch_fire", false) || onHit.has("catch_fire")
                || hurt && Json.bool(damage, "catch_fire", false));
        }
        if (hurt && Json.bool(damage, "destroy_on_hit", false)) {
            mob.discard();
            return;
        }

        JsonObject live = java.util.Objects.requireNonNullElse(mob.liveComponent("minecraft:projectile"), body);
        String bounce = Json.text(live, "should_bounce", "no");
        if ("if_no_damage_dealt".equals(bounce) && !hurt
            || "if_invulnerable".equals(bounce) && struck.isInvulnerable()) {
            mob.setDeltaMovement(mob.getDeltaMovement().scale(BOUNCE));
        } else if (!Json.bool(live, "multiple_targets", false)) {
            mob.setDeltaMovement(mob.getDeltaMovement().multiply(DROP));
        }
    }

    private static final double BOUNCE = -0.1;

    /** What Java's trident keeps of its speed after it hits, which leaves it falling where it struck. */
    private static final Vec3 DROP = new Vec3(-0.01, -0.1, -0.01);

    /** Rolls the damage range the pack wrote and deals it, with the knockback it asked for. */
    private boolean damaged(JsonObject damage, LivingEntity struck) {
        String family = Json.text(damage, "filter", null);
        at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition of =
            at.ioexception.mcaddon.registry.AddonEntities.definitionOf(struck.getType());
        if (family != null && (of == null || !of.families().contains(family))) return false;

        double low = Goals.low(damage, "damage", 0);
        double high = Goals.high(damage, "damage", low);
        float dealt = (float) (low + mob.getRandom().nextDouble() * Math.max(0, high - low)
            + Json.number(damage, "power_multiplier", 0) * mob.getDeltaMovement().length());
        if (!Platform.hurt(struck, (ServerLevel) mob.level(),
            mob.level().damageSources().mobProjectile(mob, shooter), dealt)) {
            return false;
        }
        if (Json.bool(damage, "knockback", false)) {
            Vec3 away = mob.getDeltaMovement().normalize();
            struck.push(away.x, 0.1, away.z);
        }
        return true;
    }

    private void burn(LivingEntity struck, boolean alight) {
        if (!alight) return;

        struck.setRemainingFireTicks((int) (Json.number(body, "on_fire_time", DEFAULT_ON_FIRE_SECONDS)
            * Goals.TICKS_PER_SECOND));
    }

    /** `mob_effect` writes one of the game's effects into whoever was hit. */
    private void applyEffect(LivingEntity struck) {
        JsonObject effect = onHit.getAsJsonObject("mob_effect");
        String named = effect == null ? null : Json.text(effect, "effect", null);
        Identifier id = named == null ? null : Identifier.tryParse(
            named.contains(":") ? named : "minecraft:" + named);
        if (id == null) return;

        Platform.addEffect(struck, id, (int) Json.number(effect, "durationnormal", 0),
            (int) Json.number(effect, "amplifier", 0), false, true);
    }

    private void hitBlock(BlockHitResult where) {
        play(Json.text(body, "hit_ground_sound", Json.text(body, "hit_sound", null)));
        if (!onHit.has("stick_in_ground")) return;

        stuck = true;
        mob.setDeltaMovement(Vec3.ZERO);
        mob.setNoGravity(true);
        mob.setPos(where.getLocation());
    }

    private void scatterParticles(ServerLevel level, HitResult hit, boolean onEntity) {
        JsonObject particles = onHit.getAsJsonObject("particle_on_hit");
        if (particles == null) return;
        if (!Json.bool(particles, onEntity ? "on_entity_hit" : "on_other_hit", false)) return;

        Vec3 at = hit.getLocation();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, at.x, at.y, at.z,
            (int) Json.number(particles, "num_particles", 1), 0.1, 0.1, 0.1, 0);
    }

    private void grantExperience(ServerLevel level, Vec3 at) {
        JsonObject experience = onHit.getAsJsonObject("grant_xp");
        if (experience == null) return;

        int low = (int) Json.number(experience, "minXP", 0);
        int high = (int) Json.number(experience, "maxXP", low);
        int amount = low + mob.getRandom().nextInt(Math.max(1, high - low + 1));
        if (amount > 0) ExperienceOrb.award(level, at, amount);
    }

    /** `definition_event` fires one of the pack's own events on whoever it named. */
    private void fireEvent(HitResult hit) {
        JsonObject definition = onHit.getAsJsonObject("definition_event");
        JsonObject trigger = definition == null ? null : definition.getAsJsonObject("event_trigger");
        String event = trigger == null ? null : Json.text(trigger, "event", null);
        if (event == null) return;

        Entity struck = hit instanceof EntityHitResult on ? on.getEntity() : null;
        if (Json.bool(definition, "affect_projectile", false)) {
            EntityEvents.trigger(mob, event, struck, ServerHooks.state());
        }
        if (Json.bool(definition, "affect_target", false) && struck != null) {
            EntityEvents.trigger(struck, event, mob, ServerHooks.state());
        }
        if (Json.bool(definition, "affect_shooter", false) && shooter != null) {
            EntityEvents.trigger(shooter, event, mob, ServerHooks.state());
        }
    }

    private void play(String named) {
        if (named == null) return;

        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(
            Identifier.tryParse(named.contains(":") ? named : "minecraft:" + named)).orElse(null);
        if (sound != null) mob.playSound(sound, 1f, 1f);
    }
}
