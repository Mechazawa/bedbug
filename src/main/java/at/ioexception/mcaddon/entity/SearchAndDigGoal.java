package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * `minecraft:behavior.random_search_and_dig`, Bedrock's sniffer: a walk to a random patch of ground
 * it may dig, then a dig that turns up what the pack's item table holds.
 *
 * A spot is a place to stand whose ground is one of the target blocks and which the entity can path
 * to, drawn up to `find_valid_position_retries` times within `search_range_xz` blocks either way
 * and `search_range_y` up or down, the reach Java's own sniffer searches with. The dig fails when
 * its ground stops being a target block or something else takes the goal over.
 */
public final class SearchAndDigGoal extends Goal {
    /** The default list the schema names, in Bedrock's spelling. */
    private static final List<String> DIGGABLE = List.of("minecraft:dirt", "minecraft:grass_block",
        "minecraft:podzol", "minecraft:dirt_with_roots", "minecraft:moss_block", "minecraft:mud",
        "minecraft:muddy_mangrove_roots");

    private final AddonEntity mob;
    private final double speed;
    private final double radius;
    private final int rangeXz;
    private final int rangeY;
    private final int tries;
    private final Set<Block> diggable;
    private final Goals.Span cooldown;
    private final Goals.Span digging;
    private final int itemAfter;
    private final double itemOffset;
    private final String itemTable;
    private final String onSearching;
    private final String onSearchFailed;
    private final String onDigging;
    private final String onFound;
    private final String onDigFailed;
    private final String onSucceeded;

    private BlockPos spot;
    private Path path;
    private long readyAt;

    /** Ticks spent digging, or less than nothing while the entity is still walking to its spot. */
    private int dug;
    private int digFor;
    private boolean ended;
    private String outcome;

    public SearchAndDigGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.radius = Goals.number(component, "goal_radius", 1.5);
        this.rangeXz = (int) Goals.number(component, "search_range_xz", 10);
        this.rangeY = (int) Goals.number(component, "search_range_y", 7);
        this.tries = (int) Goals.number(component, "find_valid_position_retries", 5);
        Set<Block> listed = Goals.blocks(component, "target_blocks");
        this.diggable = listed.isEmpty() ? DIGGABLE.stream().map(AddonBlocks::named)
            .filter(Objects::nonNull).collect(Collectors.toSet()) : listed;
        this.cooldown = Goals.span(component, "cooldown_range", 0, 0);
        this.digging = Goals.span(component, "digging_duration_range", 8, 10);
        this.itemAfter = Goals.ticks(component, "spawn_item_after_seconds", 0);
        this.itemOffset = Goals.number(component, "spawn_item_pos_offset", 0);
        this.itemTable = Goals.text(component, "item_table");
        this.onSearching = Goals.event(component, "on_searching_start");
        this.onSearchFailed = Goals.event(component, "on_fail_during_searching");
        this.onDigging = Goals.event(component, "on_digging_start");
        this.onFound = Goals.event(component, "on_item_found");
        this.onDigFailed = Goals.event(component, "on_fail_during_digging");
        this.onSucceeded = Goals.event(component, "on_success");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.level().getGameTime() < readyAt || !mob.onGround() || mob.isInWater() || mob.isPassenger()) {
            return false;
        }
        for (int i = 0; i < tries; i++) {
            BlockPos drawn = mob.blockPosition().offset(between(rangeXz), between(rangeY), between(rangeXz));
            if (!diggable(drawn) || !Platform.withinHome(mob, drawn)) continue;

            Path towards = mob.getNavigation().createPath(drawn, 0);
            if (towards == null || !towards.canReach()) continue;

            spot = drawn;
            path = towards;
            return true;
        }
        return false;
    }

    private int between(int range) {
        return mob.getRandom().nextInt(2 * Math.max(0, range) + 1) - range;
    }

    /** Ground of a target block with room over it to stand. */
    private boolean diggable(BlockPos standing) {
        return diggable.contains(mob.level().getBlockState(standing.below()).getBlock())
            && mob.level().getBlockState(standing).getCollisionShape(mob.level(), standing).isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return !ended;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        dug = -1;
        ended = false;
        mob.setValue("is_searching", true);
        mob.getNavigation().moveTo(path, speed);
        raise(onSearching);
    }

    @Override
    public void tick() {
        if (!diggable(spot)) {
            end(dug < 0 ? onSearchFailed : onDigFailed);
            return;
        }
        if (dug < 0) {
            search();
        } else {
            dig();
        }
    }

    private void search() {
        if (mob.position().closerThan(Vec3.atBottomCenterOf(spot), radius)) {
            mob.getNavigation().stop();
            mob.setValue("is_searching", false);
            dug = 0;
            digFor = digging.roll(mob.getRandom());
            raise(onDigging);
            return;
        }
        if (!mob.getNavigation().isDone()) return;

        Path again = mob.getNavigation().createPath(spot, 0);
        if (again == null || !again.canReach()) {
            end(onSearchFailed);
            return;
        }
        mob.getNavigation().moveTo(again, speed);
    }

    private void dig() {
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(Vec3.atBottomCenterOf(spot));
        if (dug == Math.min(itemAfter, digFor) && mob.level() instanceof ServerLevel level && unearth(level)) {
            raise(onFound);
        }
        if (++dug >= digFor) end(onSucceeded);
    }

    /** Whether the item table handed anything out, dropped `spawn_item_pos_offset` ahead of the entity. */
    private boolean unearth(ServerLevel level) {
        Identifier table = itemTable == null ? null : AddonEntities.lootTable(mob.getType(), itemTable);
        if (table == null) return false;

        Vec3 at = mob.position().add(Vec3.directionFromRotation(0, mob.getYRot()).scale(itemOffset));
        boolean found = false;
        for (ItemStack stack : Platform.rollLoot(level, table, mob)) {
            if (stack.isEmpty()) continue;

            level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, stack));
            found = true;
        }
        return found;
    }

    @Override
    public void stop() {
        mob.setValue("is_searching", false);
        mob.getNavigation().stop();
        readyAt = mob.level().getGameTime() + cooldown.roll(mob.getRandom());
        raise(ended ? outcome : dug < 0 ? onSearchFailed : onDigFailed);
    }

    /** The event `stop` raises; one taken over before it ended raises the failure of where it was. */
    private void end(String event) {
        ended = true;
        outcome = event;
    }

    private void raise(String event) {
        if (event != null) mob.raiseEvent(event);
    }
}
