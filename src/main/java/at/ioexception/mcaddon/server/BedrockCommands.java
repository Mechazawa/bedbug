package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.camera.CameraShake;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.bedrock.camera.Easing;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonParticles;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;
import java.util.List;

/**
 * The Bedrock commands a pack expects to exist and Java does not ship.
 *
 * Most are things this mod already does from script, so a `.mcfunction` or a command block gets the
 * same behaviour the pack's script half gets. What Bedrock's own form takes and this does not is
 * `/playanimation`'s `next_state` and `controller`, which name a scratch animation controller; a
 * stop expression covers what packs actually use them for.
 *
 * `/camera` and `/camerashake` act on one player's own view, which only that player's client can
 * draw, so both are decided here and sent to each named player alone.
 */
public final class BedrockCommands {
    /** Bedrock's two shakes: one moves the camera about, the other turns it. Positional is default. */
    private static final List<String> SHAKE_KINDS = List.of("positional", "rotational");

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

        // Java's own `/particle` knows nothing of a pack's effects, which are emitters rather than
        // single particles, so the mod answers for those and hands the rest to the game.
        dispatcher.register(Commands.literal("particle")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("effect", StringArgumentType.string())
                .executes(command -> particle(command, null))
                .then(Commands.argument("position", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
                    .executes(command -> particle(command,
                        net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(command,
                            "position"))))));

        dispatcher.register(Commands.literal("event")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("entity")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .then(Commands.argument("event", StringArgumentType.greedyString())
                        .executes(BedrockCommands::entityEvent)))));

        registerCamera(dispatcher);
        registerCameraShake(dispatcher);
    }

    /**
     * `/camera` sets what one player sees, so every part of it is decided here and sent to them.
     *
     * Bedrock lets `ease`, `pos` and `rot` be written in that order and each of them left out, which
     * is why the same two tails hang off both the bare preset and the end of an ease. One executor
     * reads whichever of them the player typed.
     */
    private static void registerCamera(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("camera")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("targets", EntityArgument.players())
                .then(Commands.literal("clear").executes(BedrockCommands::clearCamera))
                .then(Commands.literal("set")
                    // A preset carries a namespace, which Brigadier's own word argument stops at.
                    .then(Commands.argument("preset", IdentifierArgument.id())
                        .suggests((command, builder) -> SharedSuggestionProvider.suggest(
                            CameraView.presetNames(), builder))
                        .executes(BedrockCommands::camera)
                        .then(cameraPosition())
                        .then(cameraRotation())
                        .then(Commands.literal("ease")
                            .then(Commands.argument("seconds", FloatArgumentType.floatArg(0))
                                .then(Commands.argument("easeType", StringArgumentType.word())
                                    .suggests((command, builder) -> SharedSuggestionProvider.suggest(
                                        Easing.names(), builder))
                                    .executes(BedrockCommands::camera)
                                    .then(cameraPosition())
                                    .then(cameraRotation()))))))));
    }

    /** `pos` may be followed by `rot`, which is the only pairing Bedrock allows in that order. */
    private static LiteralArgumentBuilder<CommandSourceStack> cameraPosition() {
        return Commands.literal("pos")
            .then(Commands.argument("position", Vec3Argument.vec3())
                .executes(BedrockCommands::camera)
                .then(cameraRotation()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cameraRotation() {
        return Commands.literal("rot")
            .then(Commands.argument("xRot", FloatArgumentType.floatArg(-90, 90))
                .then(Commands.argument("yRot", FloatArgumentType.floatArg())
                    .executes(BedrockCommands::camera)));
    }

    private static void registerCameraShake(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("camerashake")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("add")
                .then(Commands.argument("targets", EntityArgument.players())
                    .executes(BedrockCommands::cameraShake)
                    .then(Commands.argument("intensity", FloatArgumentType.floatArg(0, 4))
                        .executes(BedrockCommands::cameraShake)
                        .then(Commands.argument("seconds", FloatArgumentType.floatArg(0))
                            .executes(BedrockCommands::cameraShake)
                            .then(Commands.argument("shakeType", StringArgumentType.word())
                                .suggests((command, builder) -> SharedSuggestionProvider.suggest(
                                    SHAKE_KINDS, builder))
                                .executes(BedrockCommands::cameraShake))))))
            .then(Commands.literal("stop")
                .then(Commands.argument("targets", EntityArgument.players())
                    .executes(BedrockCommands::stopCameraShake))));
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

    /** A pack's own effect starts an emitter; anything else is Java's own particle command. */
    private static int particle(CommandContext<CommandSourceStack> command,
        net.minecraft.world.phys.Vec3 position) {
        String effect = StringArgumentType.getString(command, "effect");
        net.minecraft.world.phys.Vec3 where = position == null
            ? command.getSource().getPosition() : position;

        if (AddonParticles.definitionOf(effect) == null) {
            return run(command.getSource(), "particle " + effect
                + " " + where.x + " " + where.y + " " + where.z);
        }

        Particles.start(command.getSource().getLevel(), effect, where.x, where.y, where.z);
        return 1;
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

    /**
     * Sets what each named player sees.
     *
     * A preset or an ease Bedrock has not got is reported rather than guessed at, because a camera
     * pointing somewhere nobody asked for is worse than a line that says which word was wrong.
     */
    private static int camera(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String written = IdentifierArgument.getId(command, "preset").toString();
        CameraView.Preset preset = CameraView.Preset.tryFrom(written);
        if (preset == null) {
            command.getSource().sendFailure(
                Component.literal("There is no camera preset named " + written + "."));
            return 0;
        }

        Easing easing = null;
        if (given(command, "easeType")) {
            String name = StringArgumentType.getString(command, "easeType");
            easing = Easing.tryFrom(name);
            if (easing == null) {
                command.getSource().sendFailure(Component.literal("There is no ease named " + name + "."));
                return 0;
            }
        }

        return send(command, new CameraView(preset,
            given(command, "position") ? Vec3Argument.getVec3(command, "position") : null,
            given(command, "xRot") ? FloatArgumentType.getFloat(command, "xRot") : null,
            given(command, "yRot") ? FloatArgumentType.getFloat(command, "yRot") : null,
            given(command, "seconds") ? FloatArgumentType.getFloat(command, "seconds") : 0f,
            easing).encode());
    }

    private static int clearCamera(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return send(command, CameraView.CLEARED.encode());
    }

    private static int send(CommandContext<CommandSourceStack> command, String view)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(command, "targets");
        targets.forEach(player -> Channels.sendCameraView(player, view));
        return targets.size();
    }

    /** Bedrock's own defaults: one unit of shake, for one second, moving rather than turning. */
    private static int cameraShake(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String kind = given(command, "shakeType")
            ? StringArgumentType.getString(command, "shakeType") : SHAKE_KINDS.get(0);
        if (!SHAKE_KINDS.contains(kind)) {
            command.getSource().sendFailure(Component.literal("There is no shake named " + kind + "."));
            return 0;
        }

        return shake(command, new CameraShake(
            given(command, "intensity") ? FloatArgumentType.getFloat(command, "intensity") : 1f,
            given(command, "seconds") ? FloatArgumentType.getFloat(command, "seconds") : 1f,
            kind.equals("rotational")));
    }

    private static int stopCameraShake(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return shake(command, CameraShake.STOP);
    }

    private static int shake(CommandContext<CommandSourceStack> command, CameraShake shake)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(command, "targets");
        String encoded = shake.encode();
        targets.forEach(player -> Channels.sendCameraShake(player, encoded));
        return targets.size();
    }

    /** The parsed nodes are the words that were typed, and every part of `/camera` is optional. */
    private static boolean given(CommandContext<CommandSourceStack> command, String name) {
        for (ParsedCommandNode<CommandSourceStack> node : command.getNodes()) {
            if (node.getNode().getName().equals(name)) return true;
        }
        return false;
    }
}
