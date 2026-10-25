package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Drags what it is hunting in and swallows it whole.
 *
 * `minecraft:behavior.eat_mob` is a frog's tongue. Nothing in Java swallows a mob, so the pull, the
 * pause while the mouth closes and what is left behind are all carried out here; the loot table the
 * pack named is what the meal leaves on the ground.
 */
public final class EatMobGoal extends Goal {
    private final AddonEntity mob;
    private final double runSpeed;
    private final double reachDistance;
    private final double pullInForce;
    private final int eatTime;
    private final String lootTable;
    private final String eatSound;
    private int eaten;
    private LivingEntity meal;

    public EatMobGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.runSpeed = Goals.number(component, "run_speed", 1);
        this.reachDistance = Goals.number(component, "reach_mob_distance", 1);
        this.pullInForce = Goals.number(component, "pull_in_force", 1);
        this.eatTime = Goals.ticks(component, "eat_animation_time", 1);
        this.lootTable = Goals.text(component, "loot_table");
        this.eatSound = Goals.text(component, "eat_mob_sound");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        meal = mob.getTarget();
        return meal != null && meal.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return meal != null && meal.isAlive();
    }

    @Override
    public void start() {
        eaten = 0;
        mob.setValue("is_eating_mob", true);
    }

    @Override
    public void stop() {
        mob.setValue("is_eating_mob", false);
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (meal == null) return;

        mob.getLookControl().setLookAt(meal, 30f, 30f);
        if (mob.distanceTo(meal) > reachDistance) {
            mob.getNavigation().moveTo(meal, runSpeed);
            return;
        }

        mob.getNavigation().stop();
        Vec3 towards = mob.position().subtract(meal.position()).normalize().scale(pullInForce * 0.1);
        meal.setDeltaMovement(meal.getDeltaMovement().add(towards));

        if (++eaten < eatTime || !(mob.level() instanceof ServerLevel level)) return;

        mob.makeNoise(eatSound);
        mob.setKilled(meal);
        Platform.kill(meal, level);
        dropMeal(level);
        meal = null;
    }

    private void dropMeal(ServerLevel level) {
        Identifier table = lootTable == null ? null
            : AddonEntities.lootTable(mob.getType(), lootTable);
        if (table == null) return;

        for (ItemStack stack : Platform.rollLoot(level, table, mob)) {
            if (stack.isEmpty()) continue;

            level.addFreshEntity(new ItemEntity(level, mob.getX(), mob.getY(), mob.getZ(), stack));
        }
    }
}
