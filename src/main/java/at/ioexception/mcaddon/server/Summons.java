package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Bedrock's `/summon`, which turns, names and starts the entity it makes as well as placing it.
 *
 * Java's own command reads those three out of the entity's saved data, which a Bedrock line does not
 * carry, so this tree holds Bedrock's form instead. The entity is made by Java's own summon, so a mob
 * is given what a summoned mob of its kind always gets, and the line then turns it to the rotation or
 * the point it names, puts the name tag on it and raises the spawn event the pack picked.
 */
public final class Summons {
    private static final DynamicCommandExceptionType UNKNOWN = new DynamicCommandExceptionType(
        type -> Component.literal("No entity type is called " + type + "."));
    private static final DynamicCommandExceptionType PEACEFUL = new DynamicCommandExceptionType(
        type -> Component.literal(type + " cannot be summoned while the difficulty is peaceful."));

    private Summons() {
    }

    /**
     * `summon <type> [pos] [yRot xRot] [event] [name]`, `summon <type> <name> [pos]` and `summon <type>
     * [pos] facing <entity|point> [event] [name]`.
     *
     * A name and a position can both follow the type directly; Brigadier keeps whichever reads the
     * whole line, so three coordinates are a position and anything else is a name.
     */
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("summon")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("entityType", IdentifierArgument.id())
                .executes(Summons::summon)
                .then(Commands.argument("spawnPos", Vec3Argument.vec3())
                    .executes(Summons::summon)
                    .then(named(Commands.argument("rotation", RotationArgument.rotation())))
                    .then(Commands.literal("facing")
                        .then(named(Commands.argument("lookAtEntity", EntityArgument.entity())))
                        .then(named(Commands.argument("lookAtPosition", Vec3Argument.vec3())))))
                .then(Commands.argument("nameTag", BasicString.basicString())
                    .executes(Summons::summon)
                    .then(Commands.argument("spawnPos", Vec3Argument.vec3())
                        .executes(Summons::summon)))));
    }

    /** What may follow a rotation or a point to face: the spawn event, then the name tag. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T named(T node) {
        return node.executes(Summons::summon)
            .then(Commands.argument("spawnEvent", BasicString.basicString())
                .executes(Summons::summon)
                .then(Commands.argument("nameTag", BasicString.basicString())
                    .executes(Summons::summon)));
    }

    private static int summon(CommandContext<CommandSourceStack> command) throws CommandSyntaxException {
        CommandSourceStack source = command.getSource();
        Vec3 at = BedrockCommands.given(command, "spawnPos")
            ? Vec3Argument.getVec3(command, "spawnPos") : source.getPosition();
        Entity summoned = spawn(source, IdentifierArgument.getId(command, "entityType").toString(), at,
            BedrockCommands.given(command, "spawnEvent") ? BasicString.get(command, "spawnEvent") : null,
            BedrockCommands.given(command, "nameTag") ? BasicString.get(command, "nameTag") : null);

        if (BedrockCommands.given(command, "rotation")) {
            Vec2 rotation = RotationArgument.getRotation(command, "rotation").getRotation(source);
            turn(summoned, rotation.y, rotation.x);
        } else if (BedrockCommands.given(command, "lookAtEntity")) {
            face(summoned, EntityArgument.getEntity(command, "lookAtEntity").getEyePosition());
        } else if (BedrockCommands.given(command, "lookAtPosition")) {
            face(summoned, Vec3Argument.getVec3(command, "lookAtPosition"));
        }

        source.sendSuccess(() -> Component.literal("Summoned " + summoned.getName().getString() + "."), true);
        return 1;
    }

    /**
     * Makes one entity of the type a Bedrock line names, the way Java's own summon makes it, then
     * names it and raises the event the line picked on it.
     *
     * Bedrock refuses a hostile mob on peaceful outright, where Java before 26 lets one appear and
     * despawn at once, so the refusal is made here for every version.
     */
    static Entity spawn(CommandSourceStack source, String typeName, Vec3 at, String event, String name)
        throws CommandSyntaxException {
        EntityType<?> type = AddonEntities.typeOf(typeName);
        if (type == null) throw UNKNOWN.create(typeName);
        if (Platform.refusedInPeaceful(type, source.getLevel())) throw PEACEFUL.create(typeName);

        Entity made = SummonCommand.createEntity(source, type.builtInRegistryHolder(), at, new CompoundTag(), true);
        if (name != null) made.setCustomName(Component.literal(name));
        if (event != null) EntityEvents.trigger(made, event, null, ServerHooks.state());
        return made;
    }

    /** Turns an entity's body, head and view together, which is how Bedrock states a spawn rotation. */
    static void turn(Entity entity, float yaw, float pitch) {
        Platform.placeAt(entity, entity.getX(), entity.getY(), entity.getZ(), yaw, pitch);
        entity.setYHeadRot(yaw);
        if (entity instanceof LivingEntity living) living.setYBodyRot(yaw);
    }

    /** Turns an entity so its eyes look at the point. */
    private static void face(Entity entity, Vec3 point) {
        Vec3 offset = point.subtract(entity.getEyePosition());
        double across = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        turn(entity, (float) Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90f,
            (float) -Math.toDegrees(Math.atan2(offset.y, across)));
    }
}
