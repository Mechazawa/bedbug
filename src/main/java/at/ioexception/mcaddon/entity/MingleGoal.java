package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * `minecraft:behavior.mingle`, which has an entity walk up to one of its kind and chat.
 *
 * The partner is the nearest living entity of `mingle_partner_type` within {@link #SEARCH} blocks;
 * Mojang publishes no search distance, and a village's gathering place is about that wide. The
 * entity walks to within `mingle_distance` of it, faces it for `duration` seconds and then waits out
 * `cooldown_time` before it may start again. A pack naming no partner type has nobody to meet.
 */
public final class MingleGoal extends Goal {
    private static final double SEARCH = 16;

    private final AddonEntity mob;
    private final String partnerType;
    private final double distance;
    private final double speed;
    private final int duration;
    private final int cooldown;
    private LivingEntity partner;
    private int chatEndsAt;
    private int readyAt;

    public MingleGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        String named = Goals.text(component, "mingle_partner_type");
        this.partnerType = named == null || named.isEmpty() ? null : AddonEntities.identifierOf(named);
        this.distance = Goals.number(component, "mingle_distance", 2);
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.duration = Goals.ticks(component, "duration", 1);
        this.cooldown = Goals.ticks(component, "cooldown_time", 0);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (partnerType == null || mob.tickCount < readyAt) return false;

        partner = mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(SEARCH),
                candidate -> candidate != mob && candidate.isAlive()
                    && partnerType.equals(AddonEntities.identifierOf(candidate.getType())))
            .stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
        return partner != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!partner.isAlive()) return false;
        if (chatEndsAt > 0) return mob.tickCount < chatEndsAt;

        return !mob.getNavigation().isDone() || mob.distanceTo(partner) <= distance;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        chatEndsAt = 0;
        mob.getNavigation().moveTo(partner, speed);
    }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(partner, 30f, 30f);
        if (chatEndsAt == 0 && mob.distanceTo(partner) <= distance) {
            chatEndsAt = mob.tickCount + duration;
            mob.getNavigation().stop();
        } else if (chatEndsAt == 0 && mob.tickCount % 10 == 0) {
            mob.getNavigation().moveTo(partner, speed);
        }
    }

    @Override
    public void stop() {
        partner = null;
        chatEndsAt = 0;
        mob.getNavigation().stop();
        readyAt = mob.tickCount + cooldown;
    }
}
