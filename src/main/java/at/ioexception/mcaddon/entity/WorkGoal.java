package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Goes to a workstation and spends a while at it.
 *
 * `minecraft:behavior.work` sends a Bedrock villager to the job site it claimed. A pack's entity
 * claims nothing, so the nearest station of a kind a villager could work at is what it walks to.
 * `minecraft:behavior.work_composter` is the same walk to a composter, with the composting itself
 * as what the entity does once it is standing there.
 */
public final class WorkGoal extends Goal {
    /** How far a villager looks for its work, which is a village's own reach. */
    private static final int SEARCH_RADIUS = 48;

    /** How close counts as standing at the station, squared. */
    private static final double ARRIVED = 4;

    /** Ticks between two searches for somewhere to work. */
    private static final int SEARCH_INTERVAL = 100;

    /** How long the walk to the station may take before the entity gives up on it. */
    private static final int TRAVEL_LIMIT = 400;

    /** The items Java's farmer composts, which are what Bedrock's description calls excess seeds. */
    private static final Set<Item> COMPOSTABLE_SEEDS = Set.of(Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS);

    private final AddonEntity mob;
    private final boolean composts;
    private final double speed;
    private final int activeTicks;
    private final boolean worksInRain;
    private final String onArrival;
    private final String onUseBlock;
    private final int usesPerVisit;
    private final int useDelayMin;
    private final int useDelayMax;
    private final boolean canFill;
    private final boolean canEmpty;
    private final int itemsPerUse;
    private final int minItemCount;
    private final int rainTolerance;
    private final int cooldown;
    private final int soundDelayMin;
    private final int soundDelayMax;
    private BlockPos station;
    private long coolUntil;
    private int nextSoundAt;
    private int worked;
    private int used;
    private int nextUseAt;
    private int untilSearch;
    private int travelled;

    private WorkGoal(AddonEntity mob, JsonObject component, boolean composts) {
        this.mob = mob;
        this.composts = composts;
        this.speed = Goals.number(component, "speed_multiplier", 0.5);
        this.activeTicks = (int) Goals.number(component, "active_time", 0);
        this.worksInRain = Goals.flag(component, "can_work_in_rain", false);
        this.rainTolerance = (int) Goals.number(component, "work_in_rain_tolerance", -1);
        this.cooldown = (int) Goals.number(component, "goal_cooldown", 0);
        this.onArrival = Goals.event(component, "on_arrival");
        this.onUseBlock = Goals.event(component, "on_use_block");
        this.usesPerVisit = (int) Goals.number(component, "block_interaction_max", 1);
        this.useDelayMin = (int) Goals.number(component, "use_block_min", 100);
        this.useDelayMax = (int) Goals.number(component, "use_block_max", 200);
        this.canFill = Goals.flag(component, "can_fill_composter", true);
        this.canEmpty = Goals.flag(component, "can_empty_composter", true);
        this.itemsPerUse = (int) Goals.number(component, "items_per_use_max", 20);
        this.minItemCount = (int) Goals.number(component, "min_item_count", 10);
        this.soundDelayMin = composts ? 0 : (int) Goals.number(component, "sound_delay_min", 0);
        this.soundDelayMax = composts ? 0 : (int) Goals.number(component, "sound_delay_max", 0);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.work`, which is a shift at whichever station is nearest. */
    public static WorkGoal working(AddonEntity mob, JsonObject component) {
        return new WorkGoal(mob, component, false);
    }

    /** `minecraft:behavior.work_composter`, which is a shift spent filling a composter. */
    public static WorkGoal composting(AddonEntity mob, JsonObject component) {
        return new WorkGoal(mob, component, true);
    }

    @Override
    public boolean canUse() {
        // Searching the places index over a village's reach is not a per-tick question, so the
        // goal asks it every few seconds instead of every time the selector comes round.
        if (untilSearch-- > 0) return false;
        if (!(mob.level() instanceof ServerLevel level) || level.getGameTime() < coolUntil) return false;

        untilSearch = SEARCH_INTERVAL;
        station = nearestStation(level);
        if (station != null && !worksInRain && rainedOn()) station = null;
        return station != null;
    }

    @Override
    public boolean canContinueToUse() {
        // A station the entity cannot reach would otherwise hold the goal, and with it every other
        // goal that wants to move, for as long as the entity lives.
        if (station == null || worked > 0 && worked >= activeTicks || travelled >= TRAVEL_LIMIT) return false;

        return worksInRain || activeTicks - worked <= rainTolerance || !rainedOn();
    }

    @Override
    public void start() {
        worked = 0;
        used = 0;
        travelled = 0;
        mob.getNavigation().moveTo(station.getX() + 0.5, station.getY(), station.getZ() + 0.5, speed);
    }

    @Override
    public void stop() {
        station = null;
        coolUntil = mob.level().getGameTime() + cooldown;
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (station == null) return;

        Vec3 at = Vec3.atCenterOf(station);
        mob.getLookControl().setLookAt(at.x, at.y, at.z);
        if (mob.distanceToSqr(at) > ARRIVED) {
            travelled++;
            if (mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(at.x, at.y, at.z, speed);
            }
            return;
        }

        if (worked == 0) {
            fire(onArrival);
            nextSoundAt = soundDelay();
            nextUseAt = useDelay();
        }
        worked++;
        if (composts) compost();
        if (soundDelayMax > 0 && worked >= nextSoundAt) {
            workSound();
            nextSoundAt = worked + soundDelay();
        }
    }

    /**
     * One visit to the composter every `use_block_min` to `use_block_max` ticks, up to
     * `block_interaction_max` of them. A full composter is emptied first, then seeds are filled in
     * the way Java's farmer does: per kind, only what is held beyond `min_item_count`, and at most
     * `items_per_use_max` items a visit.
     */
    private void compost() {
        if (used >= usesPerVisit || worked < nextUseAt) return;
        if (!(mob.level() instanceof ServerLevel level)) return;

        BlockState state = level.getBlockState(station);
        if (!(state.getBlock() instanceof ComposterBlock)) return;

        used++;
        nextUseAt = worked + useDelay();
        if (canEmpty && state.getValue(ComposterBlock.LEVEL) == ComposterBlock.READY) {
            state = ComposterBlock.extractProduce(mob, state, level, station);
        }
        if (canFill && state.getValue(ComposterBlock.LEVEL) < ComposterBlock.MAX_LEVEL) fillFromInventory(level, state);
        fire(onUseBlock);
    }

    private void fillFromInventory(ServerLevel level, BlockState state) {
        Container inventory = mob.inventory();
        if (inventory == null) return;

        int budget = itemsPerUse;
        Map<Item, Integer> held = new HashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (COMPOSTABLE_SEEDS.contains(stack.getItem())) {
                held.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        for (int slot = inventory.getContainerSize() - 1; slot >= 0 && budget > 0; slot--) {
            ItemStack stack = inventory.getItem(slot);
            if (!COMPOSTABLE_SEEDS.contains(stack.getItem())) continue;

            int spare = held.get(stack.getItem()) - minItemCount;
            int count = Math.min(Math.min(stack.getCount(), spare), budget);
            held.merge(stack.getItem(), -Math.max(0, count), Integer::sum);
            for (int i = 0; i < count; i++) {
                state = ComposterBlock.insertItem(mob, state, level, stack, station);
                budget--;
            }
        }
    }

    /** Whether rain falls on the station, which is what keeps a worker that may not work in rain away. */
    private boolean rainedOn() {
        return mob.level().isRainingAt(station.above());
    }

    private int useDelay() {
        return Goals.between(mob.getRandom(), useDelayMin, Math.max(useDelayMin, useDelayMax));
    }

    private int soundDelay() {
        return Goals.between(mob.getRandom(), soundDelayMin, Math.max(soundDelayMin, soundDelayMax));
    }

    /**
     * The sound Java's villager makes working at the station: that of the profession whose job site
     * the station is. A station no profession takes makes none.
     */
    private void workSound() {
        Holder<PoiType> kind = ((ServerLevel) mob.level()).getPoiManager().getType(station).orElse(null);
        if (kind == null) return;

        BuiltInRegistries.VILLAGER_PROFESSION.stream()
            .filter(profession -> profession.acquirableJobSite().test(kind) && profession.workSound() != null)
            .findFirst()
            .ifPresent(profession -> mob.makeSound(profession.workSound()));
    }

    /** The nearest place this entity could work at, which for composting is a composter. */
    private BlockPos nearestStation(ServerLevel level) {
        if (composts) {
            return level.getPoiManager().findClosest(
                type -> type.value().is(Blocks.COMPOSTER.defaultBlockState()),
                mob.blockPosition(), SEARCH_RADIUS, PoiManager.Occupancy.ANY).orElse(null);
        }
        return level.getPoiManager().findClosest(type -> type.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
            mob.blockPosition(), SEARCH_RADIUS, PoiManager.Occupancy.ANY).orElse(null);
    }

    private void fire(String event) {
        if (event != null) EntityEvents.trigger(mob, event, null, ServerHooks.state());
    }
}
