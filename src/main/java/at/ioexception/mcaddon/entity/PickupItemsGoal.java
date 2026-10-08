package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * Walks to an item lying nearby and picks it up, which is `minecraft:behavior.pickup_items`.
 *
 * Bedrock writes picking up as a behaviour with a priority like any other, so an entity busy with a
 * goal above it leaves the item lying. Where a taken item goes is the entity's own business, since
 * that depends on what it already carries.
 */
final class PickupItemsGoal extends Goal {
    /** How often the path to the item is planned again, which is how often Java's own pets do it. */
    private static final int REPATH_TICKS = 10;

    private final AddonEntity mob;
    private final JsonObject component;
    private final double speed;
    private final double reach;
    private final double height;
    private final double goalRadius;
    private final int cooldownAfterHurt;
    private final String startEvent;
    private final String endEvent;
    private ItemEntity wanted;
    private int untilRepath;
    private Boolean allowedByChance;

    PickupItemsGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.component = component;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.reach = Goals.number(component, "max_dist", 1);
        this.height = Goals.number(component, "search_height", 1);
        this.goalRadius = Goals.number(component, "goal_radius", 0.5);
        this.cooldownAfterHurt = Goals.ticks(component, "cooldown_after_being_attacked", 0);
        this.startEvent = Goals.event(component, "on_pickup_item_start");
        this.endEvent = Goals.event(component, "on_pickup_item_end");
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!allowedByChance()) return false;
        if (mob.getLastHurtByMob() != null
            && mob.tickCount - mob.getLastHurtByMobTimestamp() < cooldownAfterHurt) {
            return false;
        }

        wanted = mob.level().getEntitiesOfClass(ItemEntity.class,
                mob.getBoundingBox().inflate(reach, height, reach),
                item -> item.isAlive() && !item.hasPickUpDelay() && wants(item.getItem()))
            .stream()
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
        return wanted != null;
    }

    /**
     * `pickup_based_on_chance` is decided once per entity, the way Java decides whether its own
     * zombie may pick up loot: more often the harder the place it stands in is.
     */
    private boolean allowedByChance() {
        if (allowedByChance == null && mob.level() instanceof net.minecraft.server.level.ServerLevel level) {
            allowedByChance = !Goals.flag(component, "pickup_based_on_chance", false)
                || mob.getRandom().nextFloat() < LOOT_CHANCE
                    * level.getCurrentDifficultyAt(mob.blockPosition()).getSpecialMultiplier();
        }
        return Boolean.TRUE.equals(allowedByChance);
    }

    /** Java's own odds for a zombie that may pick up loot, scaled by the place's difficulty. */
    private static final float LOOT_CHANCE = 0.55f;

    @Override
    public boolean canContinueToUse() {
        return wanted != null && wanted.isAlive() && wants(wanted.getItem())
            && (!mob.getNavigation().isDone() || within());
    }

    @Override
    public void start() {
        untilRepath = 0;
        if (startEvent != null) mob.raiseEvent(startEvent);
    }

    @Override
    public void stop() {
        wanted = null;
        mob.getNavigation().stop();
        if (endEvent != null) mob.raiseEvent(endEvent);
    }

    @Override
    public void tick() {
        if (wanted == null) return;
        if (within()) {
            mob.pickUp(wanted);
            wanted = null;
            return;
        }
        if (--untilRepath > 0) return;

        untilRepath = REPATH_TICKS;
        mob.getNavigation().moveTo(wanted, speed);
    }

    /**
     * Whether the behaviour lets the entity take this stack, given what it already carries.
     *
     * `minecraft:shareables` names what is wanted unless `can_pickup_any_item` says anything is,
     * and `stop_if_holding_item` refuses a stack whose slot is taken, which is also what keeps an
     * entity with no inventory from swapping the item in its hand for the next one it walks past.
     */
    private boolean wants(net.minecraft.world.item.ItemStack stack) {
        if (AddonEntity.named(component, "excluded_items", stack)) return false;
        if (!Goals.flag(component, "can_pickup_any_item", false) && !mob.shareable(stack)) return false;
        if (Goals.flag(component, "pickup_same_items_as_in_hand", false)
            && !net.minecraft.world.item.ItemStack.isSameItem(mob.getMainHandItem(), stack)) {
            return false;
        }

        boolean slotFree = mob.slotFreeFor(stack);
        if (Goals.flag(component, "stop_if_holding_item", false) && !slotFree) return false;
        return mob.carriesInventory()
            || (Goals.flag(component, "can_pickup_to_hand_or_equipment", true) && slotFree);
    }

    /** Close enough to take it, which is `goal_radius` from the item and never less than touching it. */
    private boolean within() {
        double radius = Math.max(goalRadius, mob.getBbWidth() / 2 + 0.5);
        return mob.distanceToSqr(wanted) <= radius * radius;
    }
}
