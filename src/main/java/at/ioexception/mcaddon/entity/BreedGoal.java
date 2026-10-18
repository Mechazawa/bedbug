package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * Walks to another of its kind that is also in the mood, and leaves a child where the two of them meet.
 *
 * Bedrock splits breeding in two: `minecraft:breedable` is the food and what is born, and
 * `minecraft:behavior.breed` is the pair finding each other. Java writes both halves into `Animal`,
 * which a pack's entity is not one of, so the walking and the birth happen here.
 */
public final class BreedGoal extends Goal {
    /** How far a willing pair will look for each other, which is Java's own courtship range. */
    private static final double SEARCH_RANGE = 8;

    /** How close the two have to stand before there is a child. */
    private static final double TOUCHING = 2;

    private final AddonEntity mob;
    private final double speed;
    private AddonEntity mate;

    public BreedGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.inTheMood()) return false;

        mate = mob.level().getEntitiesOfClass(AddonEntity.class,
                mob.getBoundingBox().inflate(SEARCH_RANGE)).stream()
            .filter(other -> other != mob && other.inTheMood()
                && (other.getType() == mob.getType() || mob.breedsWith(other)))
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
        return mate != null;
    }

    @Override
    public boolean canContinueToUse() {
        return mate != null && mate.isAlive() && mob.inTheMood() && mate.inTheMood();
    }

    @Override
    public void tick() {
        if (mate == null) return;

        mob.getLookControl().setLookAt(mate, 30f, 30f);
        if (mob.distanceTo(mate) > TOUCHING) {
            mob.getNavigation().moveTo(mate, speed);
            return;
        }

        mob.getNavigation().stop();
        bear();
    }

    /**
     * Puts the child where the pair stand, unless breeding made the entity pregnant instead, and
     * leaves both of them resting.
     */
    private void bear() {
        if (!(mob.level() instanceof ServerLevel level)) return;

        mate.restAfterBreeding();
        if (!mob.conceive(mate)) {
            mate = null;
            return;
        }

        EntityType<?> born = mob.offspringType();
        Entity child = born == null ? null : Platform.createMob(born, level);
        if (child == null) return;

        Platform.placeAt(child, mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), 0f);
        if (child instanceof AddonEntity young) {
            young.beBorn();
            young.inherit(mob, mate);
        }
        level.addFreshEntity(child);
        // After the child's own spawn event, whose groups would otherwise overwrite what it inherits.
        Offspring offspring = Offspring.tryFrom(mob.liveComponent("minecraft:offspring"));
        if (offspring != null && child instanceof AddonEntity young) {
            offspring.passOn(young, mob, mate, mob.getRandom());
        }
        for (int i = 0; i < 7; i++) {
            level.sendParticles(ParticleTypes.HEART, mob.getRandomX(1), mob.getRandomY() + 0.5,
                mob.getRandomZ(1), 1, 0, 0, 0, 0);
        }

        // Bedrock pays for a birth out of the same component that pays for a death.
        int paid = mob.definition().experienceWhenBred();
        if (paid > 0) {
            net.minecraft.world.entity.ExperienceOrb.award(level,
                new net.minecraft.world.phys.Vec3(mob.getX(), mob.getY(), mob.getZ()), paid);
        }
        mate = null;
    }
}
