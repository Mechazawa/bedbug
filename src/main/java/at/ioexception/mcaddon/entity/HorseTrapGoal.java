package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Waits for somebody to come close, then goes off.
 *
 * `minecraft:behavior.skeleton_horse_trap` is a mob left standing as bait: a player inside the radius
 * brings down lightning and a few more of the same entity, and a trap nobody walks into is taken out
 * of the world once its time is up. Java writes this into `SkeletonHorse` as a private goal, so a
 * pack's entity reaches it only here. How many arrive is Java's own answer for a horse trap.
 */
public final class HorseTrapGoal extends Goal {
    /** How many more of the entity a sprung trap brings with it. */
    private static final int COMPANIONS = 3;

    /** How far out of the trap each of them arrives. */
    private static final double SCATTER = 2;

    /** Ticks between two looks for somebody to spring it. */
    private static final int SEARCH_INTERVAL = 20;

    private final AddonEntity mob;
    private final double withinRadius;
    private final int lifetime;
    private int untilSearch;
    private boolean sprung;

    public HorseTrapGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.withinRadius = Goals.number(component, "within_radius", 10);
        this.lifetime = Goals.ticks(component, "duration", 900);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (untilSearch-- > 0) return false;

        untilSearch = SEARCH_INTERVAL;
        sprung = nearbyPlayer() != null;
        return sprung || mob.tickCount >= lifetime;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (!sprung) {
            mob.discard();
            return;
        }

        strikeLightning(level);
        for (int i = 0; i < COMPANIONS; i++) {
            Entity joining = Platform.createMob(mob.getType(), level);
            if (joining == null) continue;

            Platform.placeAt(joining, mob.getX() + scatter(), mob.getY(), mob.getZ() + scatter(),
                mob.getYRot(), 0);
            level.addFreshEntity(joining);
        }
    }

    private void strikeLightning(ServerLevel level) {
        // 26.2 moved the vanilla entity types out of the class that builds them.
        //? if >=26.2 {
        Entity bolt = Platform.createMob(net.minecraft.world.entity.EntityTypes.LIGHTNING_BOLT, level);
        //?} else {
        /*Entity bolt = Platform.createMob(net.minecraft.world.entity.EntityType.LIGHTNING_BOLT, level);
        *///?}
        if (bolt == null) return;

        Platform.placeAt(bolt, mob.getX(), mob.getY(), mob.getZ(), 0, 0);
        level.addFreshEntity(bolt);
    }

    private double scatter() {
        return (mob.getRandom().nextDouble() - 0.5) * 2 * SCATTER;
    }

    private Player nearbyPlayer() {
        return mob.level().getNearestPlayer(mob, withinRadius);
    }
}
