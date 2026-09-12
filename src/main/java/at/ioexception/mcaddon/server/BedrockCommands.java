package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

/**
 * The Bedrock commands a pack expects to exist and Java does not ship.
 *
 * Both are things this mod already does from script, so a `.mcfunction` or a command block gets the
 * same behaviour the pack's script half gets. What Bedrock's own form takes and this does not is
 * `/playanimation`'s `next_state` and `controller`, which name a scratch animation controller; a
 * stop expression covers what packs actually use them for.
 */
public final class BedrockCommands {
    private BedrockCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("playanimation")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("targets", EntityArgument.entities())
                .then(Commands.argument("animation", StringArgumentType.string())
                    .executes(command -> playAnimation(command, "", 0f))
                    .then(Commands.argument("next_state", StringArgumentType.string())
                        .executes(command -> playAnimation(command, "", 0f))
                        .then(Commands.argument("blend_out_time", FloatArgumentType.floatArg(0))
                            .executes(command -> playAnimation(command, "",
                                FloatArgumentType.getFloat(command, "blend_out_time")))
                            .then(Commands.argument("stop_expression", StringArgumentType.string())
                                .executes(command -> playAnimation(command,
                                    StringArgumentType.getString(command, "stop_expression"),
                                    FloatArgumentType.getFloat(command, "blend_out_time")))))))));

        // An event carries the namespace of the pack that declared it, and Brigadier's unquoted
        // string stops at the colon. Greedy is what lets `vn:become_angry` through unquoted, which
        // is how Bedrock writes it, and nothing follows the event for greed to swallow.
        // Bedrock plays a track for everyone; Java's own music is a sound in its own category, which
        // is what `volume` and `fade` in Bedrock's form control and what a pack ships as a sound.
        dispatcher.register(Commands.literal("music")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("stop").executes(command -> stopMusic(command.getSource())))
            .then(Commands.literal("play")
                .then(Commands.argument("track", StringArgumentType.string())
                    .executes(command -> playMusic(command, 1f))
                    .then(Commands.argument("volume", FloatArgumentType.floatArg(0))
                        .executes(command -> playMusic(command,
                            FloatArgumentType.getFloat(command, "volume"))))))
            .then(Commands.literal("queue")
                .then(Commands.argument("track", StringArgumentType.string())
                    .executes(command -> playMusic(command, 1f)))));

        // Bedrock toggles between clear and raining, which is the pair Java's own command names.
        dispatcher.register(Commands.literal("toggledownfall")
            .requires(source -> Platform.allowedAt(source, 1))
            .executes(command -> toggleDownfall(command.getSource())));

        // Bedrock saves a region under a name and places it again; Java has the same two operations
        // behind a structure block and `/place template`, and no command that names them.
        dispatcher.register(Commands.literal("structure")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                            .executes(BedrockCommands::saveStructure)))))
            .then(Commands.literal("load")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(BedrockCommands::loadStructure)))));

        // A mob event switches a kind of spawning on or off, which Java states as a game rule.
        dispatcher.register(Commands.literal("mobevent")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("event", StringArgumentType.string())
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(BedrockCommands::mobEvent))));

        dispatcher.register(Commands.literal("event")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("entity")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .then(Commands.argument("event", StringArgumentType.greedyString())
                        .executes(BedrockCommands::entityEvent)))));
    }

    /**
     * Bedrock's own commands, expressed as the Java commands that do the same thing.
     *
     * Running Java's own implementation rather than reaching for the server method behind it keeps
     * this to one form across every version, and gives the pack the same feedback a player typing it
     * would get.
     */
    private static int run(CommandSourceStack source, String command) {
        source.getServer().getCommands().performPrefixedCommand(source.withSuppressedOutput(), command);
        return 1;
    }

    /** A track is a sound in the music category, which is the slider a player turns down. */
    private static int playMusic(CommandContext<CommandSourceStack> command, float volume) {
        String track = StringArgumentType.getString(command, "track");
        return run(command.getSource(), "playsound " + track + " music @a ~ ~ ~ " + volume);
    }

    private static int stopMusic(CommandSourceStack source) {
        return run(source, "stopsound @a music");
    }

    private static int toggleDownfall(CommandSourceStack source) {
        return run(source, source.getLevel().isRaining() ? "weather clear" : "weather rain");
    }

    /** The region between the two corners, kept under the name the pack gave it. */
    private static int saveStructure(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Identifier id = Identifier.tryParse(StringArgumentType.getString(command, "name"));
        if (id == null) {
            command.getSource().sendFailure(Component.literal("That is not a usable structure name."));
            return 0;
        }

        BlockPos from = BlockPosArgument.getLoadedBlockPos(command, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(command, "to");
        BlockPos corner = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
            Math.min(from.getZ(), to.getZ()));
        Vec3i size = new Vec3i(Math.abs(from.getX() - to.getX()) + 1,
            Math.abs(from.getY() - to.getY()) + 1, Math.abs(from.getZ() - to.getZ()) + 1);

        StructureTemplateManager manager = command.getSource().getServer().getStructureManager();
        Platform.fillFromWorld(manager.getOrCreate(id), command.getSource().getLevel(), corner, size);
        return manager.save(id) ? 1 : 0;
    }

    private static int loadStructure(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos at = BlockPosArgument.getLoadedBlockPos(command, "to");
        return run(command.getSource(), "place template "
            + StringArgumentType.getString(command, "name") + " " + at.getX() + " " + at.getY()
            + " " + at.getZ());
    }

    /**
     * Switches one kind of spawning on or off.
     *
     * Bedrock names the event and Java names the rule, and only the two that Java has a rule for can
     * be answered. The rest are reported rather than silently accepted.
     */
    private static int mobEvent(CommandContext<CommandSourceStack> command) {
        String rule = switch (StringArgumentType.getString(command, "event")) {
            case "minecraft:pillager_patrols_event" -> "doPatrolSpawning";
            case "minecraft:wandering_trader_event" -> "doTraderSpawning";
            default -> null;
        };
        if (rule == null) {
            command.getSource().sendFailure(
                Component.literal("Java has no rule for that mob event."));
            return 0;
        }
        return run(command.getSource(), "gamerule " + rule
            + " " + BoolArgumentType.getBool(command, "enabled"));
    }

    private static int playAnimation(CommandContext<CommandSourceStack> command, String stopExpression,
        float blendOut) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(command, "targets");
        String animation = StringArgumentType.getString(command, "animation");

        for (Entity target : targets) Animations.play(target, animation, stopExpression, blendOut);
        return targets.size();
    }

    private static int entityEvent(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(command, "targets");
        String event = StringArgumentType.getString(command, "event");

        int fired = 0;
        for (Entity target : targets) {
            if (EntityEvents.trigger(target, event, null, ServerHooks.state())) fired++;
        }
        if (fired == 0) {
            command.getSource().sendFailure(
                Component.literal("No target has an event named " + event + "."));
        }
        return fired;
    }
}
