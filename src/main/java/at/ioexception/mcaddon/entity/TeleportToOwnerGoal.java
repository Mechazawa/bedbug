package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Puts the pet back beside its owner when walking there would take too long.
 *
 * `minecraft:behavior.teleport_to_owner` is what keeps a Bedrock pet from being left behind by a
 * player who swam a river or climbed a cliff. The cooldown is the whole of the goal's pacing: the
 * conditions are the pack's own filters, so a pack that wants a distance says so there.
 */
public final class TeleportToOwnerGoal extends Goal {
    /** How far from the owner a pet has to be before being put back is the shorter way there. */
    private static final double FAR_ENOUGH = 12;

    /** Places tried around the owner before the pet gives up and walks instead. */
    private static final int ATTEMPTS = 16;

    private final AddonEntity mob;
    private final int cooldown;
    private final Filter conditions;
    private int untilNext;

    public TeleportToOwnerGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.cooldown = Goals.ticks(component, "cooldown", 1);
        this.conditions = Goals.filter(component, "filters");
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (untilNext-- > 0) return false;

        LivingEntity owner = mob.getOwner();
        if (owner == null || owner.level() != mob.level()) return false;
        if (mob.distanceTo(owner) < FAR_ENOUGH) return false;

        return conditions.matches(new Filter.Context(mob, owner, owner, ServerHooks.state()));
    }

    @Override
    public void start() {
        untilNext = cooldown;

        LivingEntity owner = mob.getOwner();
        if (owner == null) return;

        for (int i = 0; i < ATTEMPTS; i++) {
            double x = owner.getX() + mob.getRandom().nextInt(7) - 3;
            double y = owner.getY() + mob.getRandom().nextInt(3) - 1;
            double z = owner.getZ() + mob.getRandom().nextInt(7) - 3;
            if (!mob.level().noCollision(mob, mob.getBoundingBox().move(
                x - mob.getX(), y - mob.getY(), z - mob.getZ()))) {
                continue;
            }

            Platform.placeAt(mob, x, y, z, mob.getYRot(), mob.getXRot());
            mob.getNavigation().stop();
            return;
        }
    }
}
