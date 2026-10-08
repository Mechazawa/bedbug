package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;

/**
 * Walks to somewhere suitable, settles there a while and leaves an egg behind.
 *
 * `minecraft:behavior.lay_egg` is a Bedrock turtle's, and Java writes the same behaviour into its own
 * turtle and nowhere else. The pack names the ground it wants, how long the laying takes and the
 * block it leaves, so all three are read rather than assumed.
 */
public final class LayEggGoal extends Goal {
    private final AddonEntity mob;
    private final double speed;
    private final int searchRange;
    private final int searchHeight;
    private final int laySeconds;
    private final double goalRadius;
    private final Set<Block> ground;
    private final Block egg;
    private final String layEvent;
    private BlockPos nest;
    private int laid;

    public LayEggGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.searchRange = (int) Math.max(1, Goals.number(component, "search_range", 8));
        this.searchHeight = (int) Math.max(1, Goals.number(component, "search_height", 1));
        this.laySeconds = Goals.ticks(component, "lay_seconds", 10);
        this.goalRadius = Goals.number(component, "goal_radius", 0.5);
        this.ground = Goals.blocks(component, "target_blocks");
        this.egg = named(Goals.text(component, "egg_type"));
        this.layEvent = Goals.event(component, "on_lay");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel)) return false;

        nest = search();
        return nest != null;
    }

    @Override
    public boolean canContinueToUse() {
        return nest != null && laid < laySeconds;
    }

    @Override
    public void start() {
        laid = 0;
        mob.setValue("is_laying_egg", true);
        mob.getNavigation().moveTo(nest.getX() + 0.5, nest.getY() + 1, nest.getZ() + 0.5, speed);
    }

    @Override
    public void stop() {
        mob.setValue("is_laying_egg", false);
    }

    @Override
    public void tick() {
        if (nest == null) return;
        if (mob.distanceToSqr(nest.getX() + 0.5, nest.getY() + 1, nest.getZ() + 0.5)
            > goalRadius * goalRadius + 1) {
            mob.getNavigation().moveTo(nest.getX() + 0.5, nest.getY() + 1, nest.getZ() + 0.5, speed);
            return;
        }

        mob.getNavigation().stop();
        laid++;
        if (laid < laySeconds || !(mob.level() instanceof ServerLevel level)) return;

        level.setBlockAndUpdate(nest.above(), egg.defaultBlockState());
        mob.playSound(SoundEvents.TURTLE_LAY_EGG, mob.getSoundVolume(), mob.getVoicePitch());
        if (layEvent != null) EntityEvents.trigger(mob, layEvent, null, ServerHooks.state());
        nest = null;
    }

    /** The nearest of the blocks the pack named with room above it for the egg. */
    private BlockPos search() {
        BlockPos at = mob.blockPosition();
        return java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(
                at.offset(-searchRange, -searchHeight, -searchRange),
                at.offset(searchRange, searchHeight, searchRange)).spliterator(), false)
            .filter(this::nestable)
            .map(BlockPos::immutable)
            .min(Comparator.comparingDouble(at::distSqr))
            .orElse(null);
    }

    private boolean nestable(BlockPos at) {
        return (ground.isEmpty() || ground.contains(mob.level().getBlockState(at).getBlock()))
            && !mob.level().getBlockState(at).isAir()
            && mob.level().getBlockState(at.above()).canBeReplaced();
    }

    /** The block a pack lays, which is a turtle egg wherever the pack named nothing else. */
    private static Block named(String egg) {
        Block block = egg == null ? null : AddonBlocks.named(egg);
        return block == null ? Blocks.TURTLE_EGG : block;
    }
}
