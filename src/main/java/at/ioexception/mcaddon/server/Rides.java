package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.mixin.PassengerInvoker;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock's `/ride`, which mounts and dismounts as Java's does and also empties a vehicle, mounts a
 * whole group or none of it, and summons the other half of the pair.
 *
 * Whether a vehicle takes a rider is the vehicle's own answer, `canAddPassenger`, which is where Java
 * counts the seats and where a pack's `minecraft:rideable` states its seat count and the families it
 * carries. A player is never a vehicle here, as in Java's own `/ride`.
 */
public final class Rides {
    private enum Fill { UNTIL_FULL, IF_GROUP_FITS }

    private enum Rule { REASSIGN_RIDES, NO_RIDE_CHANGE, SKIP_RIDERS }

    private Rides() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ride")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.literal("start_riding")
                    .then(Commands.argument("ride", EntityArgument.entity())
                        .executes(command -> startRiding(command, false, Fill.UNTIL_FULL))
                        .then(teleportRule("teleport_ride", true))
                        .then(teleportRule("teleport_rider", false))))
                .then(Commands.literal("stop_riding").executes(Rides::stopRiding))
                .then(Commands.literal("evict_riders").executes(Rides::evictRiders))
                .then(Commands.literal("summon_rider")
                    .then(Commands.argument("entityType", IdentifierArgument.id())
                        .executes(Rides::summonRider)
                        .then(Commands.argument("spawnEvent", BasicString.basicString())
                            .executes(Rides::summonRider)
                            .then(Commands.argument("nameTag", BasicString.basicString())
                                .executes(Rides::summonRider)))))
                .then(Commands.literal("summon_ride")
                    .then(Commands.argument("entityType", IdentifierArgument.id())
                        .executes(command -> summonRide(command, Rule.REASSIGN_RIDES))
                        .then(rideRule("reassign_rides", Rule.REASSIGN_RIDES))
                        .then(rideRule("no_ride_change", Rule.NO_RIDE_CHANGE))
                        .then(rideRule("skip_riders", Rule.SKIP_RIDERS))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> teleportRule(String word, boolean moveRide) {
        return Commands.literal(word)
            .executes(command -> startRiding(command, moveRide, Fill.UNTIL_FULL))
            .then(Commands.literal("until_full")
                .executes(command -> startRiding(command, moveRide, Fill.UNTIL_FULL)))
            .then(Commands.literal("if_group_fits")
                .executes(command -> startRiding(command, moveRide, Fill.IF_GROUP_FITS)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rideRule(String word, Rule rule) {
        return Commands.literal(word)
            .executes(command -> summonRide(command, rule))
            .then(Commands.argument("spawnEvent", BasicString.basicString())
                .executes(command -> summonRide(command, rule))
                .then(Commands.argument("nameTag", BasicString.basicString())
                    .executes(command -> summonRide(command, rule))));
    }

    /**
     * Mounts the riders one at a time. `until_full` mounts each the vehicle has room for and passes
     * over the rest; `if_group_fits` puts back everything it did the moment one does not fit.
     * `teleport_ride` brings the vehicle to its one rider rather than the rider to the vehicle.
     */
    private static int startRiding(CommandContext<CommandSourceStack> command, boolean moveRide, Fill fill)
        throws CommandSyntaxException {
        Collection<? extends Entity> riders = EntityArgument.getEntities(command, "targets");
        Entity ride = EntityArgument.getEntity(command, "ride");
        if (moveRide && riders.size() > 1) {
            return failed(command, "teleport_ride moves the ride to one rider, and the line names "
                + riders.size() + ".");
        }

        Map<Entity, Entity> mounted = new LinkedHashMap<>();
        for (Entity rider : riders) {
            if (rider == ride || rider.getVehicle() == ride) continue;
            if (!hasRoom(ride, rider)) {
                if (fill == Fill.IF_GROUP_FITS) {
                    putBack(mounted);
                    return failed(command, "The whole group does not fit on " + ride.getName().getString() + ".");
                }
                continue;
            }

            Entity before = rider.getVehicle();
            if (moveRide) {
                Platform.placeAt(ride, rider.getX(), rider.getY(), rider.getZ(), ride.getYRot(), ride.getXRot());
            }
            if (Platform.mount(rider, ride)) mounted.put(rider, before);
        }

        if (mounted.isEmpty()) return failed(command, "Nothing could ride " + ride.getName().getString() + ".");
        return succeeded(command, mounted.size() + " started riding " + ride.getName().getString() + ".",
            mounted.size());
    }

    private static int stopRiding(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        int stopped = 0;
        for (Entity rider : EntityArgument.getEntities(command, "targets")) {
            if (!rider.isPassenger()) continue;
            rider.stopRiding();
            stopped++;
        }
        return stopped == 0 ? failed(command, "None of them was riding anything.")
            : succeeded(command, stopped + " stopped riding.", stopped);
    }

    private static int evictRiders(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        int emptied = 0;
        for (Entity ride : EntityArgument.getEntities(command, "targets")) {
            if (!ride.isVehicle()) continue;
            ride.ejectPassengers();
            emptied++;
        }
        return emptied == 0 ? failed(command, "None of them was carrying anybody.")
            : succeeded(command, emptied + " lost their riders.", emptied);
    }

    /** A rider for every target with room for one, made where the target stands. */
    private static int summonRider(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        String type = IdentifierArgument.getId(command, "entityType").toString();
        int seated = 0;
        for (Entity ride : EntityArgument.getEntities(command, "targets")) {
            net.minecraft.world.entity.EntityType<?> known = AddonEntities.typeOf(type);
            Entity candidate = known == null ? null : Platform.createMob(known, command.getSource().getLevel());
            if (candidate != null && !hasRoom(ride, candidate)) continue;

            Entity rider = Summons.spawn(command.getSource(), type, ride.position(), event(command), name(command));
            if (Platform.mount(rider, ride)) seated++;
        }
        return seated == 0 ? failed(command, "None of them had room for a " + type + ".")
            : succeeded(command, "Summoned " + seated + " rider(s).", seated);
    }

    /**
     * A vehicle for every target the rule lets have one, made where the target stands and ridden by
     * it. `reassign_rides` takes a target off whatever it rides first, `skip_riders` passes over a
     * target that is riding, and `no_ride_change` passes over one that is riding or ridden.
     */
    private static int summonRide(CommandContext<CommandSourceStack> command, Rule rule) throws CommandSyntaxException {
        String type = IdentifierArgument.getId(command, "entityType").toString();
        List<Entity> riders = new ArrayList<>(EntityArgument.getEntities(command, "targets"));
        int ridden = 0;
        for (Entity rider : riders) {
            if (rule == Rule.SKIP_RIDERS && rider.isPassenger()) continue;
            if (rule == Rule.NO_RIDE_CHANGE && (rider.isPassenger() || rider.isVehicle())) continue;
            if (rider.isPassenger()) rider.stopRiding();

            Entity ride = Summons.spawn(command.getSource(), type, rider.position(), event(command), name(command));
            if (hasRoom(ride, rider) && Platform.mount(rider, ride)) ridden++;
        }
        return ridden == 0 ? failed(command, "No summoned " + type + " could be ridden.")
            : succeeded(command, "Summoned " + ridden + " ride(s).", ridden);
    }

    private static boolean hasRoom(Entity ride, Entity rider) {
        if (ride instanceof Player || ride == rider) return false;
        PassengerInvoker vehicle = (PassengerInvoker) ride;
        return vehicle.mcaddon$couldAcceptPassenger() && vehicle.mcaddon$canAddPassenger(rider);
    }

    /** Takes back a group that did not fit, returning each rider to what it rode before. */
    private static void putBack(Map<Entity, Entity> mounted) {
        mounted.forEach((rider, before) -> {
            rider.stopRiding();
            if (before != null) Platform.mount(rider, before);
        });
    }

    private static String event(CommandContext<CommandSourceStack> command) {
        return BedrockCommands.given(command, "spawnEvent") ? BasicString.get(command, "spawnEvent") : null;
    }

    private static String name(CommandContext<CommandSourceStack> command) {
        return BedrockCommands.given(command, "nameTag") ? BasicString.get(command, "nameTag") : null;
    }

    private static int failed(CommandContext<CommandSourceStack> command, String why) {
        command.getSource().sendFailure(Component.literal(why));
        return 0;
    }

    private static int succeeded(CommandContext<CommandSourceStack> command, String what, int count) {
        command.getSource().sendSuccess(() -> Component.literal(what), true);
        return count;
    }
}
