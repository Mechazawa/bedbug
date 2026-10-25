package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * `minecraft:behavior.transport_items`: takes a stack out of one kind of container and carries it to
 * another, which is Bedrock's copper golem.
 *
 * An empty hand looks for a source and a full one for a destination. What a container holds is
 * learnt only on arriving at it, as Java's copper golem learns it, so a container that had nothing
 * to give or no room is remembered as visited and passed over. Once the pack's number of visited
 * containers is reached, or nothing is left to look at, the goal rests for its idle cooldown and
 * forgets what it visited.
 */
public final class TransportItemsGoal extends Goal {
    /** How close the entity has to stand to a container's block to open it. */
    private static final double REACH = 1.5;

    /** How far from the spot beside a container a path may end and still be finished on foot. */
    private static final double STEP = 2.5;

    /** How long a walk to one container may take before it counts as out of reach. */
    private static final int GIVE_UP_TICKS = 20 * Goals.TICKS_PER_SECOND;

    private enum Placing { ANY, WITH_MATCHING, WITH_MATCHING_OR_EMPTY }

    private final AddonEntity mob;
    private final Set<Block> sources;
    private final Set<Block> destinations;
    private final int maxStackSize;
    private final boolean nearest;
    private final int horizontalReach;
    private final int verticalReach;
    private final Placing placing;
    private final int maxVisited;
    private final int initialCooldown;
    private final int idleCooldown;
    private final Set<BlockPos> visited = new HashSet<>();
    private long readyAt = Long.MIN_VALUE;
    private BlockPos target;
    private Vec3 approach;
    private long startedAt;

    public TransportItemsGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.sources = Goals.blocks(component, "source_container_types");
        this.destinations = Goals.blocks(component, "destination_container_types");
        this.maxStackSize = (int) Math.clamp(Goals.number(component, "max_stack_size", 64), 1, 64);
        this.nearest = "nearest".equals(Goals.text(component, "search_strategy"));
        this.horizontalReach = (int) Goals.low(component, "search_distance", 64);
        this.verticalReach = (int) Goals.high(component, "search_distance", 32);
        this.placing = switch (String.valueOf(Goals.text(component, "place_strategy"))) {
            case "with_matching" -> Placing.WITH_MATCHING;
            case "with_matching_or_empty" -> Placing.WITH_MATCHING_OR_EMPTY;
            default -> Placing.ANY;
        };
        this.maxVisited = (int) Goals.number(component, "max_visited_containers", 16);
        this.initialCooldown = Goals.ticks(component, "initial_cooldown", 0);
        this.idleCooldown = Goals.ticks(component, "idle_cooldown", 20);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        long now = mob.level().getGameTime();
        if (readyAt == Long.MIN_VALUE) readyAt = now + initialCooldown;
        // A path is only planned from the ground, so a container picked in the air would read as unreachable.
        if (now < readyAt || !mob.onGround() || !(mob.level() instanceof ServerLevel level)) return false;

        target = pick(level);
        if (target == null) rest(now);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null;
    }

    @Override
    public void start() {
        startedAt = mob.level().getGameTime();
        BlockPos beside = Direction.Plane.HORIZONTAL.stream().map(target::relative)
            .filter(at -> mob.level().getBlockState(at).getCollisionShape(mob.level(), at).isEmpty())
            .min(Comparator.comparingDouble(at -> at.distToCenterSqr(mob.position())))
            .orElse(target);
        approach = Vec3.atBottomCenterOf(beside);
        mob.getNavigation().moveTo(approach.x, approach.y, approach.z, 1);
    }

    @Override
    public void stop() {
        target = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null) return;

        mob.getLookControl().setLookAt(Vec3.atCenterOf(target));
        if (new AABB(target).distanceToSqr(mob.position()) > REACH * REACH) {
            boolean stalled = mob.level().getGameTime() - startedAt > GIVE_UP_TICKS;
            if (!mob.getNavigation().isDone() && !stalled) return;

            // A path ends a block short of its goal, so the last of the way is stepped straight.
            if (mob.position().distanceTo(approach) <= STEP && !stalled) {
                mob.getMoveControl().setWantedPosition(approach.x, approach.y, approach.z, 1);
            } else {
                arriveAt(null);
            }
            return;
        }

        mob.getNavigation().stop();
        arriveAt(HopperBlockEntity.getContainerAt(mob.level(), target));
    }

    /** Takes or leaves the stack, and remembers a container that had nothing for it. */
    private void arriveAt(Container container) {
        boolean done = container != null && (mob.getMainHandItem().isEmpty() ? take(container) : leave(container));
        if (done) {
            visited.clear();
        } else {
            visit(target);
        }
        target = null;
    }

    private boolean take(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack held = container.getItem(slot);
            if (held.isEmpty()) continue;

            ItemStack taken = container.removeItem(slot, Math.min(held.getCount(), maxStackSize));
            mob.setItemSlot(EquipmentSlot.MAINHAND, taken);
            container.setChanged();
            return true;
        }
        return false;
    }

    /** Puts the carried stack in where the pack's strategy allows, filling matching stacks and empty slots in order. */
    private boolean leave(Container container) {
        ItemStack carried = mob.getMainHandItem();
        boolean matching = hasMatching(container, carried);
        boolean allowed = switch (placing) {
            case ANY -> true;
            case WITH_MATCHING -> matching;
            case WITH_MATCHING_OR_EMPTY -> matching || container.isEmpty();
        };
        if (!allowed) return false;

        for (int slot = 0; slot < container.getContainerSize() && !carried.isEmpty(); slot++) {
            ItemStack there = container.getItem(slot);
            if (there.isEmpty()) {
                if (!container.canPlaceItem(slot, carried)) continue;
                container.setItem(slot, carried.split(carried.getCount()));
            } else if (ItemStack.isSameItemSameComponents(there, carried)
                && there.getCount() < there.getMaxStackSize()) {
                int moved = Math.min(there.getMaxStackSize() - there.getCount(), carried.getCount());
                there.grow(moved);
                carried.shrink(moved);
            }
        }
        container.setChanged();
        mob.setItemSlot(EquipmentSlot.MAINHAND, carried.isEmpty() ? ItemStack.EMPTY : carried);
        return carried.isEmpty();
    }

    private static boolean hasMatching(Container container, ItemStack carried) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (ItemStack.isSameItem(container.getItem(slot), carried)) return true;
        }
        return false;
    }

    private void visit(BlockPos container) {
        visited.add(container);
        BlockState state = mob.level().getBlockState(container);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            visited.add(ChestBlock.getConnectedBlockPos(container, state));
        }
        if (visited.size() >= maxVisited) rest(mob.level().getGameTime());
    }

    private void rest(long now) {
        readyAt = now + idleCooldown;
        visited.clear();
    }

    /** A container of the kind the hand needs, inside the search box and not yet visited. */
    private BlockPos pick(ServerLevel level) {
        Set<Block> wanted = mob.getMainHandItem().isEmpty() ? sources : destinations;
        if (wanted.isEmpty()) return null;

        BlockPos here = mob.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        int fromX = SectionPos.blockToSectionCoord(here.getX() - horizontalReach);
        int toX = SectionPos.blockToSectionCoord(here.getX() + horizontalReach);
        int fromZ = SectionPos.blockToSectionCoord(here.getZ() - horizontalReach);
        int toZ = SectionPos.blockToSectionCoord(here.getZ() + horizontalReach);
        for (int chunkX = fromX; chunkX <= toX; chunkX++) {
            for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;

                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    BlockPos at = entity.getBlockPos();
                    if (Math.abs(at.getX() - here.getX()) <= horizontalReach
                        && Math.abs(at.getZ() - here.getZ()) <= horizontalReach
                        && Math.abs(at.getY() - here.getY()) <= verticalReach
                        && wanted.contains(entity.getBlockState().getBlock())
                        && !visited.contains(at)
                        && entity instanceof Container) {
                        found.add(at.immutable());
                    }
                }
            }
        }
        if (found.isEmpty()) return null;

        return nearest
            ? found.stream().min(Comparator.comparingDouble(at -> at.distToCenterSqr(mob.position()))).orElseThrow()
            : found.get(mob.getRandom().nextInt(found.size()));
    }
}
