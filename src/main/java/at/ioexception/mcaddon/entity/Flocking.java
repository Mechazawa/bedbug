package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * How `minecraft:flocking` steers an entity among the others of its kind around it.
 *
 * The flock is every one of its kind within `influence_radius`, the nearest `high_flock_limit` of
 * them where a pack caps it, and under `match_variants` only those drawn the same way. A flockmate
 * nearer than `separation_threshold` pushes the entity away, harder the nearer it is, by
 * `separation_weight`; the middle of the flock pulls it in by `cohesion_weight` while it is further
 * off than `innner_cohesion_threshold` and nearer than `cohesion_threshold`; and `goal_weight` scales
 * the two together. Every weight defaults to what Mojang's documentation states, and `goal_weight`
 * to nothing, so a component with an empty body schools no one.
 */
final class Flocking {
    /** How hard one tick of flocking pushes, at a weight of one. */
    private static final double PUSH = 0.01;

    private final double influence;
    private final int largest;
    private final boolean matchesLooks;
    private final double separationDistance;
    private final double separationWeight;
    private final double cohesionDistance;
    private final double innerCohesion;
    private final double cohesionWeight;
    private final double goalWeight;
    private final double lonerChance;

    private Flocking(JsonObject component) {
        this.influence = Goals.number(component, "influence_radius", 0);
        this.largest = (int) Goals.number(component, "high_flock_limit", 0);
        this.matchesLooks = Goals.flag(component, "match_variants", false);
        this.separationDistance = Goals.number(component, "separation_threshold", 2);
        this.separationWeight = Goals.number(component, "separation_weight", 1);
        this.cohesionDistance = Goals.number(component, "cohesion_threshold", 1);
        // Mojang's schema spells this with three n, and every published pack writes it that way.
        this.innerCohesion = Goals.number(component, "innner_cohesion_threshold", 0);
        this.cohesionWeight = Goals.number(component, "cohesion_weight", 1);
        this.goalWeight = Goals.number(component, "goal_weight", 0);
        this.lonerChance = Goals.number(component, "loner_chance", 0);
    }

    /** Null for an entity whose live components hold no flocking. */
    static Flocking tryFrom(JsonObject component) {
        return component == null ? null : new Flocking(component);
    }

    /** Whether a newly flocking entity rolls `loner_chance` and keeps to itself for good. */
    boolean rollsLoner(net.minecraft.util.RandomSource random) {
        return random.nextDouble() < lonerChance;
    }

    /** The push one tick of flocking gives the entity. */
    Vec3 steer(AddonEntity self) {
        if (influence <= 0 || goalWeight == 0) return Vec3.ZERO;

        List<AddonEntity> flock = self.level().getEntitiesOfClass(AddonEntity.class,
                self.getBoundingBox().inflate(influence),
                other -> other != self && other.getType() == self.getType()
                    && other.distanceTo(self) <= influence && (!matchesLooks || looksLike(self, other)))
            .stream()
            .sorted(Comparator.comparingDouble(self::distanceToSqr))
            .limit(largest > 0 ? largest : Long.MAX_VALUE)
            .toList();
        if (flock.isEmpty()) return Vec3.ZERO;

        Vec3 apart = flock.stream()
            .map(other -> self.position().subtract(other.position()))
            .filter(away -> away.length() > 1.0E-4 && away.length() < separationDistance)
            .map(away -> away.normalize().scale((separationDistance - away.length()) / separationDistance))
            .reduce(Vec3.ZERO, Vec3::add)
            .scale(separationWeight);

        Vec3 middle = flock.stream().map(AddonEntity::position).reduce(Vec3.ZERO, Vec3::add).scale(1d / flock.size());
        Vec3 towards = middle.subtract(self.position());
        double off = towards.length();
        Vec3 together = off > innerCohesion && off < cohesionDistance && off > 1.0E-4
            ? towards.normalize().scale(cohesionWeight) : Vec3.ZERO;

        return apart.add(together).scale(goalWeight * PUSH);
    }

    private static boolean looksLike(AddonEntity self, AddonEntity other) {
        return List.of("variant", "mark_variant", "color").stream()
            .allMatch(look -> Objects.equals(self.value(look), other.value(look)));
    }
}
