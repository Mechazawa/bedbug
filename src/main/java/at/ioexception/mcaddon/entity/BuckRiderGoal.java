package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Bolts about with a rider on its back until it throws them.
 *
 * `minecraft:behavior.run_around_like_crazy` is what an untamed horse does the moment somebody climbs
 * on. Java writes it into `AbstractHorse`; here the owner a pack's taming component stores decides
 * instead: an entity nobody owns bucks, and one that has been tamed carries its rider. Each buck is
 * a try at breaking the entity in under `minecraft:tamemount`, which the entity answers.
 */
public final class BuckRiderGoal extends Goal {
    /** How far ahead a bolt aims, in blocks. */
    private static final int REACH = 5;

    /** How long the entity runs before it tries to throw the rider, as a low and a high bound. */
    private static final int BUCK_LOW = 20;
    private static final int BUCK_HIGH = 60;

    /** How hard the throw lifts whoever was aboard. */
    private static final double BUCK_LIFT = 0.5;

    private final AddonEntity mob;
    private final double speed;
    private int untilBuck;

    public BuckRiderGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1.2);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return mob.ownerUuid() == null && rider() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        untilBuck = Goals.between(mob.getRandom(), BUCK_LOW, BUCK_HIGH);
    }

    @Override
    public void tick() {
        if (mob.getNavigation().isDone()) bolt();
        if (--untilBuck > 0) return;

        untilBuck = Goals.between(mob.getRandom(), BUCK_LOW, BUCK_HIGH);
        Player rider = rider();
        if (rider != null && !mob.brokenInBy(rider)) throwRider(rider);
    }

    /** Somewhere the entity can reach at a run, which is Java's own answer for a panicking mob. */
    private void bolt() {
        Vec3 towards = LandRandomPos.getPos(mob, REACH, REACH);
        if (towards == null) return;

        mob.getNavigation().moveTo(towards.x, towards.y, towards.z, speed);
    }

    private void throwRider(Player rider) {
        rider.stopRiding();
        rider.setDeltaMovement(rider.getDeltaMovement().add(0, BUCK_LIFT, 0));
    }

    private Player rider() {
        return mob.getPassengers().stream()
            .filter(Player.class::isInstance)
            .map(Player.class::cast)
            .findFirst()
            .orElse(null);
    }
}
