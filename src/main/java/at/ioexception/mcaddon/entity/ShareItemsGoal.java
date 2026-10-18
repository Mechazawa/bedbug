package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Carries what the entity is holding to somebody else and puts it down in front of them.
 *
 * Four of Bedrock's behaviours are this same walk and drop, differing only in who is walked to and
 * why. Sharing and transporting go to another of the entity's own kind, dropping goes to whoever
 * the pack's filter picks out, and offering a flower goes to whoever is nearest. Java writes each
 * of these into the mob that does it, so none of them is reachable from a data-driven entity.
 */
public final class ShareItemsGoal extends Goal {
    /** How hard the handover is thrown, and how much of that goes upward. */
    private static final double THROW_FORCE = 0.2;
    private static final double THROW_RISE = 1.2;

    private final AddonEntity mob;
    private final boolean ownKindOnly;
    private final double searchRange;
    private final double reach;
    private final double speed;
    private final Filter wanted;
    private LivingEntity receiver;

    private ShareItemsGoal(AddonEntity mob, JsonObject component, boolean ownKindOnly,
        double defaultRange) {
        this.mob = mob;
        this.ownKindOnly = ownKindOnly;
        this.searchRange = Goals.number(component, "max_dist", defaultRange);
        this.reach = Goals.number(component, "goal_radius", 2);
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.wanted = component.has("entity_types") ? Filter.entityTypes(component.get("entity_types")) : null;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.share_items`, which hands a spare to another of the entity's own kind. */
    public static ShareItemsGoal sharing(AddonEntity mob, JsonObject component) {
        return new ShareItemsGoal(mob, component, true, 3);
    }

    /** `minecraft:behavior.transport_items`, which carries what was gathered back to the others. */
    public static ShareItemsGoal transporting(AddonEntity mob, JsonObject component) {
        return new ShareItemsGoal(mob, component, true, 16);
    }

    /** `minecraft:behavior.drop_item_for`, which leaves what it is carrying at somebody's feet. */
    public static ShareItemsGoal dropping(AddonEntity mob, JsonObject component) {
        return new ShareItemsGoal(mob, component, false, 16);
    }

    /** `minecraft:behavior.offer_flower`, which is a golem holding one out to a villager. */
    public static ShareItemsGoal offering(AddonEntity mob, JsonObject component) {
        return new ShareItemsGoal(mob, component, false, 8);
    }

    @Override
    public boolean canUse() {
        if (mob.getMainHandItem().isEmpty()) return false;

        receiver = nearest();
        return receiver != null;
    }

    @Override
    public boolean canContinueToUse() {
        return receiver != null && receiver.isAlive() && !mob.getMainHandItem().isEmpty();
    }

    @Override
    public void stop() {
        receiver = null;
    }

    @Override
    public void tick() {
        if (receiver == null) return;

        mob.getLookControl().setLookAt(receiver, 30f, 30f);
        if (mob.distanceTo(receiver) > reach) {
            mob.getNavigation().moveTo(receiver, speed);
            return;
        }

        mob.getNavigation().stop();
        handOver(receiver);
        receiver = null;
    }

    /** Lets go of what the entity is holding, aimed at whoever it walked to. */
    private void handOver(LivingEntity taker) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        ItemStack held = mob.getMainHandItem();
        if (held.isEmpty()) return;

        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ItemEntity dropped = new ItemEntity(level, mob.getX(), mob.getEyeY(), mob.getZ(), held.copy());

        Vec3 towards = taker.position().subtract(mob.position()).normalize();
        dropped.setDeltaMovement(towards.x * THROW_FORCE,
            towards.y * THROW_FORCE + THROW_FORCE * THROW_RISE, towards.z * THROW_FORCE);
        level.addFreshEntity(dropped);
    }

    /** Whoever this hands over to: the pack's own filter decides, or failing that the entity's kind. */
    private LivingEntity nearest() {
        List<LivingEntity> nearby = mob.level().getEntitiesOfClass(LivingEntity.class,
            mob.getBoundingBox().inflate(searchRange), other -> other != mob && accepts(other));
        return nearby.stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    private boolean accepts(LivingEntity other) {
        if (wanted != null) {
            return wanted.matches(new Filter.Context(mob, other, other, ServerHooks.state()));
        }
        return !ownKindOnly || other.getType() == mob.getType();
    }
}
