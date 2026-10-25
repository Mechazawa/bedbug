package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.FleeSunGoal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * `minecraft:behavior.find_cover`, a walk out of the daylight into shade, followed by the
 * `cooldown_time` Bedrock waits, in seconds, before the entity looks for cover again.
 *
 * Java's own walk out of the sun starts only for a burning mob and picks a spot by how much a
 * monster likes the dark there. Bedrock's fox seeks shade unburnt, so this starts for any entity
 * under the open sky by day and takes any spot it can stand on that the sky cannot see.
 */
public final class FindCoverGoal extends FleeSunGoal {
    /** How many spots one look tries, and how far across and up it looks, as Java's walk does. */
    private static final int TRIES = 10;
    private static final int ACROSS = 10;
    private static final int UP = 3;

    private static final long DAY_TICKS = 24000L;
    private static final long NIGHT_FROM = 12000L;

    private final int cooldown;
    private int readyAt;

    public FindCoverGoal(PathfinderMob mob, JsonObject component) {
        super(mob, Goals.number(component, "speed_multiplier", 1));
        this.cooldown = Goals.ticks(component, "cooldown_time", 0);
    }

    @Override
    public boolean canUse() {
        return mob.tickCount >= readyAt && mob.getTarget() == null
            && Platform.dayTime(mob.level()) % DAY_TICKS < NIGHT_FROM
            && mob.level().canSeeSky(mob.blockPosition()) && setWantedPos();
    }

    @Override
    public void stop() {
        super.stop();
        readyAt = mob.tickCount + cooldown;
    }

    @Override
    protected Vec3 getHidePos() {
        Level level = mob.level();
        for (int i = 0; i < TRIES; i++) {
            BlockPos at = mob.blockPosition().offset(mob.getRandom().nextInt(ACROSS * 2) - ACROSS,
                mob.getRandom().nextInt(UP * 2) - UP, mob.getRandom().nextInt(ACROSS * 2) - ACROSS);
            if (!level.canSeeSky(at) && level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) {
                return Vec3.atBottomCenterOf(at);
            }
        }
        return null;
    }
}
