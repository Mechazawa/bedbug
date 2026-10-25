package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * `minecraft:behavior.go_home`, the walk back to the home `minecraft:home` saved.
 *
 * It starts at a chance of one in `interval` each tick and ends within `goal_radius` of home, raising
 * `on_home`. A path that ends short of home is planned again once the entity is within
 * `calculate_new_path_radius` of its end; when the new one gets no further, the walk raises
 * `on_failed` and gives up. Each trigger's filters ask about the block at home as the subject `block`.
 */
public final class GoHomeGoal extends Goal {
    private final AddonEntity mob;
    private final double speed;
    private final int interval;
    private final double radius;
    private final double replanWithin;
    private final List<Trigger> onHome;
    private final List<Trigger> onFailed;
    private BlockPos home;
    private Path path;
    private boolean done;

    public GoHomeGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.interval = Math.max(1, (int) Goals.number(component, "interval", 120));
        this.radius = Goals.number(component, "goal_radius", 0.5);
        this.replanWithin = Goals.number(component, "calculate_new_path_radius", 2);
        this.onHome = Trigger.listed(component, "on_home");
        this.onFailed = Trigger.listed(component, "on_failed");
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.getRandom().nextInt(reducedTickDelay(interval)) != 0) return false;

        home = mob.homePosition();
        return home != null && !arrived();
    }

    @Override
    public boolean canContinueToUse() {
        return !done;
    }

    @Override
    public void start() {
        done = false;
        plan();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        path = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (arrived()) {
            finish(onHome);
            return;
        }
        if (path == null) {
            finish(onFailed);
            return;
        }
        BlockPos end = path.getEndNode().asBlockPos();
        boolean nearEnd = mob.position().closerThan(Vec3.atBottomCenterOf(end), replanWithin);
        if (!mob.getNavigation().isDone() && (path.canReach() || !nearEnd)) return;

        plan();
        if (path == null || !path.getEndNode().asBlockPos().equals(end) || !nearEnd) return;

        // A path ending where the last one did gets no nearer: the entity stands at the end of a walk
        // home closer than Java's navigation can bring it, or home cannot be reached from here.
        finish(path.canReach() ? onHome : onFailed);
    }

    private void plan() {
        path = mob.getNavigation().createPath(home, 0);
        if (path != null) mob.getNavigation().moveTo(path, speed);
    }

    private boolean arrived() {
        return mob.position().closerThan(Vec3.atBottomCenterOf(home), radius);
    }

    private void finish(List<Trigger> triggers) {
        done = true;
        triggers.forEach(trigger -> trigger.raise(mob, home));
    }

    /** One entry of `on_home` or `on_failed`: an event, whom it is raised on and when. */
    private record Trigger(Filter filters, String event, String target) {
        static List<Trigger> listed(JsonObject component, String key) {
            List<Trigger> triggers = new ArrayList<>();
            JsonElement value = component.get(key);
            List<JsonElement> entries = value != null && value.isJsonObject()
                ? List.of(value) : Json.list(component, key);
            for (JsonElement entry : entries) {
                if (!entry.isJsonObject()) continue;

                JsonObject body = entry.getAsJsonObject();
                String event = Json.text(body, "event", null);
                if (event != null) {
                    triggers.add(new Trigger(Goals.filter(body, "filters"), event, Json.text(body, "target", "self")));
                }
            }
            return triggers;
        }

        void raise(AddonEntity mob, BlockPos home) {
            if (!filters.matches(Filter.Context.aboutBlock(mob, ServerHooks.state(), home))) return;
            if (!"self".equals(target)) {
                AddonEntities.unsupported("minecraft:behavior.go_home target " + target);
                return;
            }

            EntityEvents.trigger(mob, event, null, ServerHooks.state());
        }
    }
}
