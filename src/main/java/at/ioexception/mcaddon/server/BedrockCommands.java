package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.camera.CameraRequest;
import at.ioexception.mcaddon.bedrock.camera.CameraShake;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.bedrock.camera.CameraSpline;
import at.ioexception.mcaddon.bedrock.camera.Easing;
import at.ioexception.mcaddon.bedrock.hud.HudElement;
import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonCameras;
import at.ioexception.mcaddon.registry.AddonFogs;
import at.ioexception.mcaddon.registry.AddonParticles;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The Bedrock commands a pack expects to exist and Java does not ship, in the tree `/bedrock` reads
 * a line against rather than in Java's own.
 *
 * Most are things this mod already does from script, so a `.mcfunction` or a command block gets the
 * same behaviour the pack's script half gets. What Bedrock's own form takes and this does not is
 * `/playanimation`'s `next_state` and `controller`, which name a scratch animation controller; a
 * stop expression covers what packs actually use them for.
 *
 * `/camera`, `/camerashake` and `/hud` act on one player's own view, which only that player's client
 * can draw, so each is decided here and sent to each named player alone.
 */
public final class BedrockCommands {
    /** Bedrock's two shakes: one moves the camera about, the other turns it. Positional is default. */
    private static final List<String> SHAKE_KINDS = List.of("positional", "rotational");

    /** Bedrock holds both offsets of an orbit camera within this many blocks either way. */
    private static final float OFFSET_REACH = 64;

    /** Bedrock limits each of a fade's three times to ten seconds. */
    private static final float LONGEST_FADE = 10f;

    /** The field of view a player may set in either edition's own options. */
    private static final float MIN_FOV = 30f;
    private static final float MAX_FOV = 110f;

    private BedrockCommands() {
    }

    /** Into the tree a Bedrock line is read against, which is not Java's own. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        Dialogues.registerCommand(dispatcher);

        // A script event id carries the namespace of the pack that declared it, and Brigadier reads
        // a colon only inside quotes, which is how the translated form of the command writes it.
        dispatcher.register(Commands.literal("scriptevent")
            .then(Commands.argument("id", StringArgumentType.string())
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(command -> ServerHooks.scriptEvent(command.getSource(),
                        StringArgumentType.getString(command, "id"),
                        StringArgumentType.getString(command, "message"))))
                .executes(command -> ServerHooks.scriptEvent(command.getSource(),
                    StringArgumentType.getString(command, "id"), ""))));

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

        Music.registerCommand(dispatcher);

        // Bedrock toggles between clear and raining, which is the pair Java's own command names.
        dispatcher.register(Commands.literal("toggledownfall")
            .requires(source -> Platform.allowedAt(source, 1))
            .executes(command -> toggleDownfall(command.getSource())));

        // Bedrock sends a player on to another server and Java carries the same instruction as a
        // packet rather than as a command. The port a line leaves out is the edition's own default,
        // which is Java's rather than Bedrock's: a Java client sent to 19132 reaches nothing.
        dispatcher.register(Commands.literal("transfer")
            .requires(source -> Platform.allowedAt(source, 3))
            .then(Commands.argument("targets", EntityArgument.players())
                .then(Commands.argument("server", StringArgumentType.string())
                    .executes(command -> transfer(command, DEFAULT_JAVA_PORT))
                    .then(Commands.argument("port", IntegerArgumentType.integer(1, 65535))
                        .executes(command ->
                            transfer(command, IntegerArgumentType.getInteger(command, "port")))))));

        // Java's `/spawnpoint` gives a player a respawn point and has no form taking one away.
        dispatcher.register(Commands.literal("clearspawnpoint")
            .requires(source -> Platform.allowedAt(source, 1))
            .executes(command ->
                clearSpawnPoint(List.of(command.getSource().getPlayerOrException())))
            .then(Commands.argument("player", EntityArgument.players())
                .executes(command ->
                    clearSpawnPoint(EntityArgument.getPlayers(command, "player")))));

        StructureCommand.registerCommand(dispatcher);
        TickingAreas.registerCommand(dispatcher);
        AreaSchedules.registerCommand(dispatcher);
        FeatureRules.registerCommand(dispatcher);
        Jigsaws.registerCommand(dispatcher);
        Structures.registerCommand(dispatcher);
        ServerAdmin.registerCommand(dispatcher);
        ServerIdentity.registerCommand(dispatcher);
        BedrockRules.registerCommand(dispatcher);

        MobEvents.registerCommand(dispatcher);

        // A pack's effects are emitters rather than single particles, which Java's own `/particle`
        // knows nothing of, so this answers for those and hands any other particle to Java's.
        dispatcher.register(Commands.literal("particle")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("effect", StringArgumentType.string())
                .executes(command -> particle(command, null))
                .then(Commands.argument("position", net.minecraft.commands.arguments.coordinates.Vec3Argument.vec3())
                    .executes(command -> particle(command,
                        net.minecraft.commands.arguments.coordinates.Vec3Argument.getVec3(command,
                            "position"))))));

        // An event carries the namespace of the pack that declared it, and Brigadier's unquoted
        // string stops at the colon. Greedy is what lets `vn:become_angry` through unquoted, which
        // is how Bedrock writes it, and nothing follows the event for greed to swallow.
        dispatcher.register(Commands.literal("event")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("entity")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .then(Commands.argument("event", StringArgumentType.greedyString())
                        .executes(BedrockCommands::entityEvent)))));

        Summons.registerCommand(dispatcher);
        Rides.registerCommand(dispatcher);
        registerCamera(dispatcher);
        registerCameraShake(dispatcher);
        registerFog(dispatcher);
        registerHud(dispatcher);
        registerInputPermission(dispatcher);
        GameTestCommand.registerCommand(dispatcher);
    }

    /**
     * `/fog` layers a pack's own fog settings over the one the game would draw.
     *
     * Each layer carries a name the pack chose, which is what a later `pop` or `remove` finds it by,
     * so the stack belongs to the player and is kept on the server. Pushing a fog no pack describes
     * is reported rather than quietly leaving the stack as it was.
     */
    private static void registerFog(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("fog")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("targets", EntityArgument.players())
                .then(Commands.literal("push")
                    .then(Commands.argument("fogId", IdentifierArgument.id())
                        .suggests((command, builder) -> SharedSuggestionProvider.suggest(
                            AddonFogs.all().keySet(), builder))
                        .then(Commands.argument("userId", StringArgumentType.string())
                            .executes(BedrockCommands::pushFog))))
                .then(Commands.literal("pop")
                    .then(Commands.argument("userId", StringArgumentType.string())
                        .executes(command -> takeFog(command, false))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("userId", StringArgumentType.string())
                        .executes(command -> takeFog(command, true))))));
    }

    /**
     * `/hud <targets> <hide|reset> [element]`, where no element and `all` both name every piece.
     *
     * Only the pieces a Java client draws are words here. The translation reports and drops a line
     * naming one of the others, so none of those ever reaches this tree.
     */
    private static void registerHud(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hud")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("targets", EntityArgument.players())
                .then(hudVisibility("hide", PlayerHuds::hide))
                .then(hudVisibility("reset", PlayerHuds::reset))));
    }

    /**
     * `/inputpermission set <targets> <permission> <enabled|disabled>` and `/inputpermission query
     * <targets> <permission> [enabled|disabled]`.
     *
     * A query tells whoever asked whether the category itself is on for each target, and returns how
     * many targets are in the state it names, or how many have the category on when it names none.
     */
    private static void registerInputPermission(CommandDispatcher<CommandSourceStack> dispatcher) {
        RequiredArgumentBuilder<CommandSourceStack, ?> set = Commands.argument("targets", EntityArgument.players());
        RequiredArgumentBuilder<CommandSourceStack, ?> query = Commands.argument("targets", EntityArgument.players());
        for (InputPermission permission : InputPermission.values()) {
            set.then(Commands.literal(permission.bedrockName())
                .then(Commands.literal("enabled").executes(command -> setInput(command, permission, true)))
                .then(Commands.literal("disabled").executes(command -> setInput(command, permission, false))));
            query.then(Commands.literal(permission.bedrockName())
                .executes(command -> queryInput(command, permission, true))
                .then(Commands.literal("enabled").executes(command -> queryInput(command, permission, true)))
                .then(Commands.literal("disabled").executes(command -> queryInput(command, permission, false))));
        }
        dispatcher.register(Commands.literal("inputpermission")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.literal("set").then(set))
            .then(Commands.literal("query").then(query)));
    }

    private static int setInput(CommandContext<CommandSourceStack> command, InputPermission permission,
        boolean enabled) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(command, "targets");
        targets.forEach(player -> PlayerInputs.set(player, permission, enabled));
        return targets.size();
    }

    private static int queryInput(CommandContext<CommandSourceStack> command, InputPermission permission,
        boolean state) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int matching = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(command, "targets")) {
            boolean enabled = PlayerInputs.enabled(player, permission);
            command.getSource().sendSuccess(() -> Component.literal(player.getName().getString() + " has "
                + permission.bedrockName() + (enabled ? " enabled." : " disabled.")), false);
            if (enabled == state) matching++;
        }
        return matching;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> hudVisibility(String visibility,
        BiConsumer<ServerPlayer, Collection<HudElement>> change) {
        List<HudElement> every = List.of(HudElement.values());
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(visibility)
            .executes(command -> changeHud(command, change, every))
            .then(Commands.literal("all").executes(command -> changeHud(command, change, every)));
        for (HudElement element : HudElement.values()) {
            if (!element.drawnByJava()) continue;
            node.then(Commands.literal(element.bedrockName())
                .executes(command -> changeHud(command, change, List.of(element))));
        }
        return node;
    }

    private static int changeHud(CommandContext<CommandSourceStack> command,
        BiConsumer<ServerPlayer, Collection<HudElement>> change, Collection<HudElement> elements)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(command, "targets");
        targets.forEach(player -> change.accept(player, elements));
        return targets.size();
    }

    /**
     * `/camera` sets what one player sees, so every part of it is decided here and sent to them.
     *
     * Bedrock lets `ease`, `pos` and `rot` be written in that order and each of them left out, which
     * is why the same tails hang off both the bare preset and the end of an ease. `facing` stands
     * where `rot` would, and `default` stands for the preset's own placement with nothing over it.
     * `view_offset` and `entity_offset` close a line for an orbit camera, after a `rot` or in place
     * of one. One executor reads whichever of them the player typed.
     */
    private static void registerCamera(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("camera")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("targets", EntityArgument.players())
                .then(Commands.literal("clear").executes(command -> toPlayers(command, player -> {
                    PlayerCameras.clear(player);
                    return null;
                })))
                .then(Commands.literal("fade")
                    .executes(BedrockCommands::fade)
                    .then(Commands.literal("time")
                        .then(Commands.argument("fadeInSeconds", FloatArgumentType.floatArg(0, LONGEST_FADE))
                            .then(Commands.argument("holdSeconds", FloatArgumentType.floatArg(0, LONGEST_FADE))
                                .then(Commands.argument("fadeOutSeconds",
                                        FloatArgumentType.floatArg(0, LONGEST_FADE))
                                    .executes(BedrockCommands::fade)
                                    .then(fadeColour())))))
                    .then(fadeColour()))
                .then(Commands.literal("fov_set")
                    .then(fovEase(Commands.argument("fov_value", FloatArgumentType.floatArg(MIN_FOV, MAX_FOV)))))
                .then(fovEase(Commands.literal("fov_clear")))
                .then(Commands.literal("set")
                    // A preset carries a namespace, which Brigadier's own word argument stops at.
                    .then(Commands.argument("preset", IdentifierArgument.id())
                        .suggests((command, builder) -> SharedSuggestionProvider.suggest(
                            AddonCameras.names(), builder))
                        .executes(BedrockCommands::camera)
                        .then(cameraPosition())
                        .then(cameraRotation())
                        .then(cameraFacing())
                        .then(viewOffset())
                        .then(entityOffset())
                        .then(Commands.literal("default").executes(BedrockCommands::camera))
                        .then(Commands.literal("ease")
                            .then(Commands.argument("seconds", FloatArgumentType.floatArg(0))
                                .then(Commands.argument("easeType", StringArgumentType.word())
                                    .suggests((command, builder) -> SharedSuggestionProvider.suggest(
                                        Easing.names(), builder))
                                    .executes(BedrockCommands::camera)
                                    .then(cameraPosition())
                                    .then(cameraRotation())
                                    .then(cameraFacing())
                                    .then(viewOffset())
                                    .then(entityOffset())
                                    .then(Commands.literal("default")
                                        .executes(BedrockCommands::camera)))))))
                .then(Commands.literal("attach_to_entity")
                    .then(Commands.argument("entity", EntityArgument.entity())
                        .executes(command -> {
                            Entity entity = EntityArgument.getEntity(command, "entity");
                            return toPlayers(command, player -> PlayerCameras.attach(player, entity, null));
                        })))
                .then(Commands.literal("detach_from_entity").executes(command -> toPlayers(command, player -> {
                    PlayerCameras.detach(player);
                    return null;
                })))
                .then(Commands.literal("target_entity")
                    .then(Commands.argument("entity", EntityArgument.entity())
                        .executes(BedrockCommands::targetCamera)
                        .then(Commands.literal("target_center_offset")
                            .then(Commands.argument("xTargetCenterOffset", FloatArgumentType.floatArg())
                                .then(Commands.argument("yTargetCenterOffset", FloatArgumentType.floatArg())
                                    .then(Commands.argument("zTargetCenterOffset", FloatArgumentType.floatArg())
                                        .executes(BedrockCommands::targetCamera)))))))
                .then(Commands.literal("remove_target").executes(command -> toPlayers(command, player -> {
                    PlayerCameras.removeTarget(player);
                    return null;
                })))
                .then(Commands.literal("play_spline")
                    .then(Commands.argument("name", IdentifierArgument.id())
                        .suggests((command, builder) -> SharedSuggestionProvider.suggest(
                            AddonCameras.splineNames(), builder))
                        .executes(BedrockCommands::playSpline)))));
    }

    /** A colour for the fade, in whole numbers of red, green and blue each up to 255. */
    private static LiteralArgumentBuilder<CommandSourceStack> fadeColour() {
        return Commands.literal("color")
            .then(Commands.argument("red", IntegerArgumentType.integer(0, 255))
                .then(Commands.argument("green", IntegerArgumentType.integer(0, 255))
                    .then(Commands.argument("blue", IntegerArgumentType.integer(0, 255))
                        .executes(BedrockCommands::fade))));
    }

    /** What may follow a field of view or `fov_clear`: the seconds it eases over, then the ease. */
    private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> T fovEase(
        T node) {
        return node.executes(BedrockCommands::fov)
            .then(Commands.argument("fovEaseTime", FloatArgumentType.floatArg(0))
                .executes(BedrockCommands::fov)
                .then(Commands.argument("fovEaseType", StringArgumentType.word())
                    .suggests((command, builder) -> SharedSuggestionProvider.suggest(Easing.names(), builder))
                    .executes(BedrockCommands::fov)));
    }

    /**
     * A full-screen fade: in, held and out again over Bedrock's own one second, half a second and
     * one second unless the line names others, in black unless it names a colour.
     */
    private static int fade(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        JsonObject fade = new JsonObject();
        fade.addProperty("in", seconds(command, "fadeInSeconds", 1f));
        fade.addProperty("hold", seconds(command, "holdSeconds", 0.5f));
        fade.addProperty("out", seconds(command, "fadeOutSeconds", 1f));
        fade.addProperty("colour", given(command, "red") ? IntegerArgumentType.getInteger(command, "red") << 16
            | IntegerArgumentType.getInteger(command, "green") << 8 | IntegerArgumentType.getInteger(command, "blue")
            : 0);
        return instruct(command, CameraView.FADE, fade);
    }

    /** A field of view for `fov_set`, or the player's own for `fov_clear`, eased over the seconds given. */
    private static int fov(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        JsonObject fov = new JsonObject();
        if (given(command, "fov_value")) fov.addProperty("fov", FloatArgumentType.getFloat(command, "fov_value"));
        fov.addProperty("seconds", seconds(command, "fovEaseTime", 0f));
        if (given(command, "fovEaseType")) {
            String name = StringArgumentType.getString(command, "fovEaseType");
            if (Easing.tryFrom(name) == null) {
                command.getSource().sendFailure(Component.literal("There is no ease named " + name + "."));
                return 0;
            }
            fov.addProperty("easing", name);
        }
        return instruct(command, CameraView.FOV, fov);
    }

    private static float seconds(CommandContext<CommandSourceStack> command, String name, float otherwise) {
        return given(command, name) ? FloatArgumentType.getFloat(command, name) : otherwise;
    }

    private static int instruct(CommandContext<CommandSourceStack> command, String kind, JsonObject body)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(command, "targets");
        String encoded = body.toString();
        targets.forEach(player -> Channels.sendInstruction(player, kind, encoded));
        return targets.size();
    }

    /** `pos` may be followed by `rot` or by `facing`, which are the pairings Bedrock allows. */
    private static LiteralArgumentBuilder<CommandSourceStack> cameraPosition() {
        return Commands.literal("pos")
            .then(Commands.argument("position", Vec3Argument.vec3())
                .executes(BedrockCommands::camera)
                .then(cameraRotation())
                .then(cameraFacing()));
    }

    /**
     * An entity or a point to look at. Brigadier keeps whichever of the two reads the whole of what
     * was typed, so three numbers are a point and a selector is an entity.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> cameraFacing() {
        return Commands.literal("facing")
            .then(Commands.argument("lookAtPosition", Vec3Argument.vec3())
                .executes(BedrockCommands::camera))
            .then(Commands.argument("lookAtEntity", EntityArgument.entity())
                .executes(BedrockCommands::camera));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> cameraRotation() {
        return Commands.literal("rot")
            .then(Commands.argument("xRot", FloatArgumentType.floatArg(-90, 90))
                .then(Commands.argument("yRot", FloatArgumentType.floatArg())
                    .executes(BedrockCommands::camera)
                    .then(viewOffset())
                    .then(entityOffset())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> viewOffset() {
        return Commands.literal("view_offset")
            .then(Commands.argument("xViewOffset", FloatArgumentType.floatArg(-OFFSET_REACH, OFFSET_REACH))
                .then(Commands.argument("yViewOffset", FloatArgumentType.floatArg(-OFFSET_REACH, OFFSET_REACH))
                    .executes(BedrockCommands::camera)
                    .then(entityOffset())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> entityOffset() {
        return Commands.literal("entity_offset")
            .then(Commands.argument("xEntityOffset", FloatArgumentType.floatArg(-OFFSET_REACH, OFFSET_REACH))
                .then(Commands.argument("yEntityOffset", FloatArgumentType.floatArg(-OFFSET_REACH, OFFSET_REACH))
                    .then(Commands.argument("zEntityOffset", FloatArgumentType.floatArg(-OFFSET_REACH, OFFSET_REACH))
                        .executes(BedrockCommands::camera))));
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

    private static int toggleDownfall(CommandSourceStack source) {
        return run(source, source.getLevel().isRaining() ? "weather clear" : "weather rain");
    }

    private static int clearSpawnPoint(Collection<ServerPlayer> players) {
        players.forEach(player -> Platform.respawnAt(player, null, null));
        return players.size();
    }

    /** The port a Java server listens on, which is what a line naming none means here. */
    private static final int DEFAULT_JAVA_PORT = 25565;

    /**
     * Hands each named player over to another server.
     *
     * The packet carrying this arrived in 1.20.5, so the oldest supported version cannot send it and
     * says so rather than reporting a move that never happened.
     */
    private static int transfer(CommandContext<CommandSourceStack> command, int port)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String host = StringArgumentType.getString(command, "server");
        Collection<net.minecraft.server.level.ServerPlayer> players =
            EntityArgument.getPlayers(command, "targets");

        long moved = players.stream().filter(player -> Platform.transfer(player, host, port)).count();
        if (moved == 0) {
            command.getSource().sendFailure(
                Component.literal("This version cannot send a player to another server."));
            return 0;
        }
        command.getSource().sendSuccess(
            () -> Component.literal("Sent " + moved + " player(s) to " + host + ":" + port), true);
        return (int) moved;
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
     * A preset or an ease this mod cannot draw is reported rather than guessed at, because a camera
     * pointing somewhere nobody asked for is worse than a line that says which word was wrong. What
     * Bedrock itself refuses is refused in its words.
     *
     * An entity to face is faced at the middle of its box, which is the point Bedrock documents.
     */
    private static int camera(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String written = IdentifierArgument.getId(command, "preset").toString();

        Easing easing = null;
        if (given(command, "easeType")) {
            String name = StringArgumentType.getString(command, "easeType");
            easing = Easing.tryFrom(name);
            if (easing == null) {
                command.getSource().sendFailure(Component.literal("There is no ease named " + name + "."));
                return 0;
            }
        }

        Vec3 facing = given(command, "lookAtPosition") ? Vec3Argument.getVec3(command, "lookAtPosition")
            : given(command, "lookAtEntity")
                ? EntityArgument.getEntity(command, "lookAtEntity").getBoundingBox().getCenter() : null;
        CameraRequest request = new CameraRequest(
            given(command, "position") ? Vec3Argument.getVec3(command, "position") : null,
            given(command, "xRot") ? FloatArgumentType.getFloat(command, "xRot") : null,
            given(command, "yRot") ? FloatArgumentType.getFloat(command, "yRot") : null,
            facing,
            given(command, "seconds") ? FloatArgumentType.getFloat(command, "seconds") : 0f,
            easing,
            given(command, "xViewOffset") ? FloatArgumentType.getFloat(command, "xViewOffset") : null,
            given(command, "yViewOffset") ? FloatArgumentType.getFloat(command, "yViewOffset") : null,
            given(command, "xEntityOffset") ? new Vec3(FloatArgumentType.getFloat(command, "xEntityOffset"),
                FloatArgumentType.getFloat(command, "yEntityOffset"),
                FloatArgumentType.getFloat(command, "zEntityOffset")) : null);
        return toPlayers(command, player -> PlayerCameras.set(player, written, request));
    }

    private static int targetCamera(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Entity target = EntityArgument.getEntity(command, "entity");
        Vec3 offset = given(command, "xTargetCenterOffset")
            ? new Vec3(FloatArgumentType.getFloat(command, "xTargetCenterOffset"),
                FloatArgumentType.getFloat(command, "yTargetCenterOffset"),
                FloatArgumentType.getFloat(command, "zTargetCenterOffset"))
            : Vec3.ZERO;
        return toPlayers(command, player -> PlayerCameras.target(player, target, offset));
    }

    private static int playSpline(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String name = IdentifierArgument.getId(command, "name").toString();
        CameraSpline spline = AddonCameras.splineOf(name);
        if (spline == null) {
            command.getSource().sendFailure(
                Component.literal("No add-on describes a camera spline called " + name + "."));
            return 0;
        }
        return toPlayers(command, player -> PlayerCameras.play(player, spline));
    }

    /**
     * Gives an order to each named player and says why it was refused where it was.
     *
     * Each order answers null when the player took it, and the command answers how many did.
     */
    private static int toPlayers(CommandContext<CommandSourceStack> command, Function<ServerPlayer, String> order)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int taken = 0;
        String refused = null;
        for (ServerPlayer player : EntityArgument.getPlayers(command, "targets")) {
            String answer = order.apply(player);
            if (answer == null) {
                taken++;
            } else {
                refused = answer;
            }
        }
        if (refused != null) command.getSource().sendFailure(Component.literal(refused));
        return taken;
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

    private static int pushFog(CommandContext<CommandSourceStack> command)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String fogId = IdentifierArgument.getId(command, "fogId").toString();
        if (AddonFogs.definitionOf(fogId) == null) {
            command.getSource().sendFailure(
                Component.literal("No add-on describes a fog called " + fogId + "."));
            return 0;
        }

        String userId = StringArgumentType.getString(command, "userId");
        int pushed = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(command, "targets")) {
            if (PlayerFogs.push(player, fogId, userId) >= 0) pushed++;
        }
        return pushed;
    }

    /** `remove` takes off every layer pushed under the name; `pop` takes off only the last one. */
    private static int takeFog(CommandContext<CommandSourceStack> command, boolean all)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String userId = StringArgumentType.getString(command, "userId");

        int taken = 0;
        for (ServerPlayer player : EntityArgument.getPlayers(command, "targets")) {
            boolean changed = all ? PlayerFogs.remove(player, userId)
                : PlayerFogs.pop(player, userId) != null;
            if (changed) taken++;
        }
        if (taken == 0) {
            command.getSource().sendFailure(
                Component.literal("No target has a fog pushed under " + userId + "."));
        }
        return taken;
    }

    /** The parsed nodes are the words that were typed, and every part of `/camera` is optional. */
    static boolean given(CommandContext<CommandSourceStack> command, String name) {
        for (ParsedCommandNode<CommandSourceStack> node : command.getNodes()) {
            if (node.getNode().getName().equals(name)) return true;
        }
        return false;
    }
}
