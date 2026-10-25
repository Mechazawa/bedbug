package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.squid.Squid;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;
import java.util.List;

/**
 * `minecraft:behavior.follow_mob`, which walks after another entity within `search_range` and stops
 * `stop_distance` short of it.
 *
 * Who may be followed is the pack's `filters`, asked with the candidate as `other`. With none,
 * Mojang's own criteria leave out players, squid, fish, tadpoles, dolphins and the entity's own
 * kind. One of the kind `preferred_actor_type` names is followed where there is one in range, and
 * otherwise one picked at random. Under `use_home_position_restriction` an entity kept near its home
 * follows nothing standing outside it.
 */
public final class FollowAnyMobGoal extends Goal {
    /** Ticks between two plans of the walk, as long as Java's own goal waits. */
    private static final int REPLAN_TICKS = 10;

    private final PathfinderMob mob;
    private final double speed;
    private final double stop;
    private final double range;
    private final Filter filters;
    private final boolean filtered;
    private final String preferred;
    private final boolean keptHome;
    private LivingEntity followed;
    private int replanAt;

    public FollowAnyMobGoal(PathfinderMob mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.stop = Goals.number(component, "stop_distance", 2);
        this.range = Goals.number(component, "search_range", 0);
        this.filters = Goals.filter(component, "filters");
        this.filtered = component.has("filters");
        String named = Json.text(component, "preferred_actor_type", null);
        this.preferred = named == null || named.contains(":") ? named : "minecraft:" + named;
        this.keptHome = Goals.flag(component, "use_home_position_restriction", true);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        List<LivingEntity> found = mob.level().getEntitiesOfClass(LivingEntity.class,
            mob.getBoundingBox().inflate(range), this::follows);
        if (found.isEmpty()) return false;

        List<LivingEntity> liked = preferred == null ? List.of() : found.stream()
            .filter(candidate -> preferred.equals(AddonEntities.identifierOf(candidate.getType())))
            .toList();
        List<LivingEntity> from = liked.isEmpty() ? found : liked;
        followed = from.get(mob.getRandom().nextInt(from.size()));
        return true;
    }

    private boolean follows(LivingEntity candidate) {
        if (candidate == mob || !candidate.isAlive() || candidate.isInvisible()) return false;
        if (keptHome && !Platform.withinHome(mob, candidate.blockPosition())) return false;
        if (filtered) return filters.matches(new Filter.Context(mob, candidate, candidate, ServerHooks.state()));

        return !(candidate instanceof Player || candidate instanceof Squid || candidate instanceof Tadpole
            || candidate instanceof Dolphin || candidate instanceof AbstractFish
            || candidate.getType() == mob.getType());
    }

    @Override
    public boolean canContinueToUse() {
        return followed != null && follows(followed) && mob.distanceTo(followed) > stop;
    }

    @Override
    public void start() {
        replanAt = 0;
    }

    @Override
    public void stop() {
        followed = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(followed, 10f, mob.getMaxHeadXRot());
        if (mob.distanceTo(followed) <= stop) {
            mob.getNavigation().stop();
            return;
        }
        if (mob.tickCount < replanAt) return;

        replanAt = mob.tickCount + REPLAN_TICKS;
        mob.getNavigation().moveTo(followed, speed);
    }
}
