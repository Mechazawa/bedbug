package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Notices other entities looking at this one, which is what `minecraft:looked_at` asks of an
 * enderman and what a pack asks of anything that reacts to a stare.
 *
 * An entity looks when the angle between where it faces and the line to one of this entity's
 * `look_at_locations` is inside the field of view and the line is clear of blocks. The field of
 * view is the width of the cone at a distance of one block, narrowing with distance unless
 * `scale_fov_by_distance` is false. The state kept here is the time the stare began, the cooldown
 * after a detection and whether the last scan found anyone.
 */
final class LookedAt {
    private static final String COMPONENT = "minecraft:looked_at";

    private final AddonEntity mob;
    private long since = -1;
    private long coolUntil;
    private boolean watched;

    LookedAt(AddonEntity mob) {
        this.mob = mob;
    }

    void tick() {
        JsonObject body = mob.liveComponent(COMPONENT);
        if (body == null || !(mob.level() instanceof ServerLevel level)) return;

        long now = level.getGameTime();
        String targeting = Json.text(body, "set_target", "once_and_stop_scanning");
        if (now < coolUntil || (mob.getTarget() != null && targeting.equals("once_and_stop_scanning"))) return;

        Optional<LivingEntity> looker = lookers(level, body).findFirst();
        if (looker.isEmpty()) {
            if (watched) raise(Goals.event(body, "not_looked_at_event"), null);
            watched = false;
            since = -1;
            return;
        }

        watched = true;
        if (since < 0) since = now;
        if (now - since < Goals.ticks(body, "min_looked_at_duration", 0)) return;

        raise(Goals.event(body, "looked_at_event"), looker.get());
        since = -1;
        coolUntil = now + Goals.span(body, "looked_at_cooldown", 0, 0).roll(mob.getRandom());
        if (!targeting.equals("never") && mob.getTarget() == null) mob.setTarget(looker.get());
    }

    private java.util.stream.Stream<LivingEntity> lookers(ServerLevel level, JsonObject body) {
        double radius = Goals.number(body, "search_radius", 10);
        Filter filter = Goals.filter(body, "filters");
        Class<? extends LivingEntity> kind = Goals.flag(body, "find_players_only", false)
            ? Player.class : LivingEntity.class;
        java.util.stream.Stream<? extends LivingEntity> candidates = level
            .getEntitiesOfClass(kind, mob.getBoundingBox().inflate(radius))
            .stream()
            .filter(candidate -> candidate != mob && candidate.distanceTo(mob) <= radius)
            .filter(candidate -> filter.matches(new Filter.Context(mob, candidate, candidate, ServerHooks.state())))
            .sorted(Comparator.comparingDouble(mob::distanceToSqr));
        if (kind == Player.class) candidates = candidates.limit(1);

        return candidates.filter(candidate -> sees(candidate, body)).map(LivingEntity.class::cast);
    }

    private boolean sees(LivingEntity candidate, JsonObject body) {
        Vec3 eye = candidate.getEyePosition();
        Vec3 view = candidate.getViewVector(1f);
        double half = Math.toRadians(Goals.number(body, "field_of_view", 26) / 2);
        boolean scaled = Goals.flag(body, "scale_fov_by_distance", true);
        ClipContext.Block obstruction = switch (Json.text(body, "line_of_sight_obstruction_type", "collision")) {
            case "outline" -> ClipContext.Block.OUTLINE;
            case "collision_for_camera" -> ClipContext.Block.VISUAL;
            default -> ClipContext.Block.COLLIDER;
        };

        return locations(body).stream().anyMatch(spot -> {
            Vec3 line = spot.subtract(eye);
            double distance = line.length();
            double allowed = scaled ? Math.atan(Math.tan(half) / Math.max(1, distance)) : half;
            boolean inView = distance < 1.0E-6
                || Math.acos(Mth.clamp(view.dot(line) / distance, -1, 1)) <= allowed;

            return inView && mob.level().clip(new ClipContext(eye, spot, obstruction,
                ClipContext.Fluid.NONE, candidate)).getType() == HitResult.Type.MISS;
        });
    }

    /** The points on this entity a stare may be aimed at, which are its eyes when the pack names none. */
    private List<Vec3> locations(JsonObject body) {
        List<Vec3> named = Json.list(body, "look_at_locations").stream()
            .map(JsonElement::getAsString)
            .map(name -> switch (name) {
                case "feet" -> mob.position();
                case "body", "center", "torso" -> mob.getBoundingBox().getCenter();
                case "head", "eyes" -> mob.getEyePosition();
                default -> null;
            })
            .filter(java.util.Objects::nonNull)
            .toList();
        return named.isEmpty() ? List.of(mob.getEyePosition()) : named;
    }

    private void raise(String event, Entity other) {
        if (event != null) EntityEvents.trigger(mob, event, other, ServerHooks.state());
    }
}
