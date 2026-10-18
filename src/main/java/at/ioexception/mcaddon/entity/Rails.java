package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

/**
 * What a rail does to an entity that says it belongs on one.
 *
 * `minecraft:rail_movement` keeps the entity on the track and caps how fast it runs along it, and
 * `minecraft:rail_sensor` answers the rail's power with the pack's own events. Both are written for
 * Bedrock's minecarts and neither says the entity has to be one, so what they need is the rail under
 * whatever declared them rather than a vehicle class.
 */
public final class Rails {
    private static final double DEFAULT_MAX_SPEED = 0.4;

    private final AddonEntity mob;
    private final double maxSpeed;
    private final boolean ridesRails;
    private final JsonObject sensor;

    /** What the rail under the entity said last tick, or null while it stood on none. */
    private Boolean powered;

    private Rails(AddonEntity mob, double maxSpeed, boolean ridesRails, JsonObject sensor) {
        this.mob = mob;
        this.maxSpeed = maxSpeed;
        this.ridesRails = ridesRails;
        this.sensor = sensor;
    }

    /** Null for an entity whose pack declared neither of the two components. */
    public static Rails tryFrom(AddonEntity mob) {
        boolean ridesRails = mob.declares("minecraft:rail_movement");
        boolean senses = mob.declares("minecraft:rail_sensor");
        if (!ridesRails && !senses) return null;

        return new Rails(mob,
            Json.number(mob.componentBody("minecraft:rail_movement"), "max_speed", DEFAULT_MAX_SPEED),
            ridesRails,
            senses ? mob.componentBody("minecraft:rail_sensor") : null);
    }

    public void tick() {
        BlockPos under = mob.blockPosition();
        BlockState rail = railAt(under);
        if (rail == null) rail = railAt(under.below());

        if (ridesRails) ride(rail);
        if (sensor != null) sense(rail);
    }

    private BlockState railAt(BlockPos position) {
        BlockState state = mob.level().getBlockState(position);
        return BaseRailBlock.isRail(state) ? state : null;
    }

    /**
     * Holds the entity to the track it is on and to the speed the pack allows.
     *
     * A rail's shape names the two ends it joins, so the direction to travel is the one of those
     * two the entity is already heading nearest to. Off a rail the entity keeps its fall and loses
     * the rest, because the component says it may move nowhere else.
     */
    private void ride(BlockState rail) {
        Vec3 movement = mob.getDeltaMovement();
        if (rail == null) {
            mob.setDeltaMovement(0, movement.y, 0);
            return;
        }

        Vec3 along = direction(rail);
        double speed = Math.min(maxSpeed, Math.sqrt(movement.x * movement.x + movement.z * movement.z));
        double sign = Math.signum(movement.x * along.x + movement.z * along.z);
        mob.setDeltaMovement(along.x * speed * sign, movement.y + along.y * speed,
            along.z * speed * sign);
    }

    /** The unit vector a rail's shape runs along, climbing where the shape ascends. */
    private static Vec3 direction(BlockState rail) {
        RailShape shape = rail.getValue(((BaseRailBlock) rail.getBlock()).getShapeProperty());
        return switch (shape) {
            case EAST_WEST -> new Vec3(1, 0, 0);
            case ASCENDING_EAST -> new Vec3(1, 1, 0).normalize();
            case ASCENDING_WEST -> new Vec3(-1, 1, 0).normalize();
            case ASCENDING_NORTH -> new Vec3(0, 1, -1).normalize();
            case ASCENDING_SOUTH -> new Vec3(0, 1, 1).normalize();
            case SOUTH_EAST -> new Vec3(1, 0, 1).normalize();
            case SOUTH_WEST -> new Vec3(-1, 0, 1).normalize();
            case NORTH_WEST -> new Vec3(-1, 0, -1).normalize();
            case NORTH_EAST -> new Vec3(1, 0, -1).normalize();
            default -> new Vec3(0, 0, 1);
        };
    }

    /**
     * Fires the pack's event on the tick the rail under the entity changes power.
     *
     * Bedrock calls a powered rail activated and an unpowered one deactivated, and only the moment
     * of the change is an event: standing still on a live rail raises nothing. A rail with no power
     * of its own, an ordinary one, counts as deactivated, and so does no rail at all when
     * `check_block_types` is set.
     */
    private void sense(BlockState rail) {
        if (rail == null && !Json.bool(sensor, "check_block_types", false)) {
            powered = null;
            return;
        }

        boolean live = rail != null && rail.hasProperty(BlockStateProperties.POWERED)
            && rail.getValue(BlockStateProperties.POWERED);
        if (powered != null && powered == live) return;

        powered = live;
        String event = live ? Goals.event(sensor, "on_activate") : Goals.event(sensor, "on_deactivate");
        if (event != null) EntityEvents.trigger(mob, event, null, ServerHooks.state());

        boolean ejects = live ? Json.bool(sensor, "eject_on_activate", true)
            : Json.bool(sensor, "eject_on_deactivate", false);
        if (ejects) mob.getPassengers().forEach(Entity::stopRiding);
    }
}
