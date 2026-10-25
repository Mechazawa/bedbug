package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.TargetSelection;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * `minecraft:behavior.drop_item_for`: goes to a spot beside somebody and leaves them something, which
 * is Bedrock's cat bringing its owner a gift in the morning.
 *
 * The target is the nearest entity the pack's list accepts inside `target_range`. The spot is the
 * standing place within `search_range` and `search_height` of the target whose distance across the
 * ground is nearest `offering_distance`. A spot further off than `minimum_teleport_distance` is
 * reached by teleporting, once per run; the rest is walked. On arrival the attempt event is raised
 * and the drop rolled, the item thrown along the look at the target.
 */
public final class DropItemForGoal extends Goal {
    /** How hard the gift is thrown, and how much of that goes upward. */
    private static final double THROW_FORCE = 0.2;
    private static final double THROW_RISE = 1.2;

    /** How long a run may take before the entity gives up on its spot. */
    private static final int GIVE_UP_TICKS = 20 * Goals.TICKS_PER_SECOND;

    private final AddonEntity mob;
    private final TargetSelection wanted;
    private final Vec3 targetRange;
    private final int searchRange;
    private final int searchHeight;
    private final double offeringDistance;
    private final double teleportDistance;
    private final Vec3 teleportOffset;
    private final double goalRadius;
    private final double speed;
    private final double maxLookHeight;
    private final double dropChance;
    private final int pickupDelay;
    private final int cooldown;
    private final double dayFrom;
    private final double dayTo;
    private final String lootTable;
    private final String attemptEvent;
    private LivingEntity target;
    private BlockPos spot;
    private boolean teleported;
    private boolean finished;
    private long readyAt;
    private long startedAt;

    public DropItemForGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.wanted = component.has("entity_types") ? TargetSelection.from(component, LivingEntity.class, 0) : null;
        this.targetRange = vector(component, "target_range", new Vec3(1, 1, 1));
        this.searchRange = (int) Goals.number(component, "search_range", 0);
        this.searchHeight = (int) Goals.number(component, "search_height", 1);
        this.offeringDistance = Goals.number(component, "offering_distance", 1);
        this.teleportDistance = Goals.number(component, "minimum_teleport_distance", 2);
        this.teleportOffset = vector(component, "teleport_offset", new Vec3(0, 1, 0));
        this.goalRadius = Goals.number(component, "goal_radius", 0.5);
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.maxLookHeight = Goals.number(component, "max_head_look_at_height", 10);
        this.dropChance = Goals.number(component, "drop_item_chance", 1);
        this.pickupDelay = Goals.ticks(component, "seconds_before_pickup", 0);
        this.cooldown = Goals.ticks(component, "cooldown", 0.25);
        this.dayFrom = Goals.low(component, "time_of_day_range", 0);
        this.dayTo = Goals.high(component, "time_of_day_range", 1);
        this.lootTable = Goals.text(component, "loot_table");
        this.attemptEvent = Goals.event(component, "on_drop_attempt");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.level().getGameTime() < readyAt || !duringTheRightHours()) return false;

        target = nearestTarget();
        spot = target == null ? null : spotBeside(target);
        return spot != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && target.isAlive() && mob.level().getGameTime() - startedAt < GIVE_UP_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        finished = false;
        teleported = false;
        startedAt = mob.level().getGameTime();
        mob.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, speed);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
        spot = null;
        readyAt = mob.level().getGameTime() + cooldown;
    }

    @Override
    public void tick() {
        if (finished) return;

        mob.getLookControl().setLookAt(lookPoint());
        Vec3 standing = Vec3.atBottomCenterOf(spot);
        double away = mob.position().distanceTo(standing);
        if (!teleported && away > teleportDistance) {
            teleported = true;
            Vec3 landing = standing.add(teleportOffset);
            mob.teleportTo(landing.x, landing.y, landing.z);
            mob.getNavigation().stop();
            return;
        }

        double across = Math.hypot(mob.getX() - standing.x, mob.getZ() - standing.z);
        if (across > goalRadius || !mob.onGround()) {
            // A path ends a block short of its goal, so the last of the way is stepped straight.
            if (mob.getNavigation().isDone()) {
                mob.getMoveControl().setWantedPosition(standing.x, standing.y, standing.z, speed);
            }
            return;
        }

        mob.getNavigation().stop();
        offer();
        finished = true;
    }

    /** Raises the attempt and, at the pack's chance, leaves the gift. */
    private void offer() {
        if (attemptEvent != null) EntityEvents.trigger(mob, attemptEvent, target, ServerHooks.state());
        if (!(mob.level() instanceof ServerLevel level) || mob.getRandom().nextDouble() >= dropChance) return;

        Vec3 from = mob.getEyePosition();
        Vec3 towards = lookPoint().subtract(from).normalize();
        for (ItemStack gift : gifts(level)) {
            ItemEntity dropped = new ItemEntity(level, from.x, from.y, from.z, gift);
            dropped.setPickUpDelay(pickupDelay);
            dropped.setDeltaMovement(towards.x * THROW_FORCE,
                towards.y * THROW_FORCE + THROW_FORCE * THROW_RISE, towards.z * THROW_FORCE);
            level.addFreshEntity(dropped);
        }
    }

    /** What the pack's table rolls, or what the entity holds when it names no table. */
    private List<ItemStack> gifts(ServerLevel level) {
        if (lootTable == null) {
            ItemStack held = mob.getMainHandItem();
            if (held.isEmpty()) return List.of();

            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return List.of(held);
        }

        Identifier table = AddonEntities.lootTable(mob.getType(), lootTable);
        return table == null ? List.of()
            : Platform.rollLoot(level, table, mob).stream().filter(stack -> !stack.isEmpty()).toList();
    }

    /** The target's eyes, held no higher than `max_head_look_at_height` above the entity's feet. */
    private Vec3 lookPoint() {
        return new Vec3(target.getX(), Math.min(target.getEyeY(), mob.getY() + maxLookHeight), target.getZ());
    }

    /**
     * Whether the clock is inside `time_of_day_range`, on this goal's scale where noon is 0 and dawn
     * 0.75. A range whose low end is above its high end runs through noon.
     */
    private boolean duringTheRightHours() {
        double sinceDawn = Math.floorMod(Platform.dayTime(mob.level()), 24000L) / 24000.0;
        double sinceNoon = (sinceDawn + 0.75) % 1;
        return dayFrom <= dayTo ? sinceNoon >= dayFrom && sinceNoon <= dayTo
            : sinceNoon >= dayFrom || sinceNoon <= dayTo;
    }

    /** Nobody, for a goal whose pack lists nobody to leave anything for. */
    private LivingEntity nearestTarget() {
        if (wanted == null) return null;

        AABB around = new AABB(mob.position(), mob.position())
            .inflate(targetRange.x, targetRange.y, targetRange.z);
        return mob.level().getEntitiesOfClass(LivingEntity.class, around,
                other -> other != mob && other.isAlive() && wanted.accepts(mob, other)).stream()
            .min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    /** The standing place round the target nearest the offering distance from it, closer to the entity on a tie. */
    private BlockPos spotBeside(LivingEntity taker) {
        BlockPos centre = taker.blockPosition();
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos at : BlockPos.betweenClosed(centre.offset(-searchRange, -searchHeight, -searchRange),
            centre.offset(searchRange, searchHeight, searchRange))) {
            if (standable(mob.level(), at)) spots.add(at.immutable());
        }
        return spots.stream().min(Comparator
                .comparingDouble((BlockPos at) -> Math.abs(Math.hypot(at.getX() + 0.5 - taker.getX(),
                    at.getZ() + 0.5 - taker.getZ()) - offeringDistance))
                .thenComparingDouble(at -> at.distToCenterSqr(mob.position())))
            .orElse(null);
    }

    private static boolean standable(Level level, BlockPos at) {
        BlockPos below = at.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
            && level.getBlockState(at).getCollisionShape(level, at).isEmpty()
            && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty();
    }

    private static Vec3 vector(JsonObject component, String key, Vec3 fallback) {
        JsonElement value = component.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() < 3) return fallback;

        JsonArray parts = value.getAsJsonArray();
        return new Vec3(parts.get(0).getAsDouble(), parts.get(1).getAsDouble(), parts.get(2).getAsDouble());
    }
}
