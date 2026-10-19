package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.aim.AimAssistSettings;
import at.ioexception.mcaddon.bedrock.aim.AimAssistSettings.TargetMode;
import at.ioexception.mcaddon.bedrock.aim.AimAssistTargeting;
import at.ioexception.mcaddon.bedrock.camera.CameraPreset;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonAimAssist;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The aim assist `/aimassist` and a script have set for each player.
 *
 * Only the player's own client knows what they face and hold, so the target is picked there; the
 * server keeps the settings, resolves the preset and its categories, and sends that player alone
 * what they need. A camera preset stating an aim assist of its own overrides these settings while
 * the player is on it. The settings are held for as long as the player is connected.
 */
public final class PlayerAimAssists {
    private static final Map<UUID, AimAssistSettings> SET = new HashMap<>();

    /** The cameras Bedrock draws aim assist under. */
    private static final Set<CameraView.Preset> SUPPORTED = Set.of(CameraView.Preset.THIRD_PERSON,
        CameraView.Preset.FIXED_BOOM, CameraView.Preset.FOLLOW_ORBIT, CameraView.Preset.FREE);

    private PlayerAimAssists() {
    }

    /**
     * `/aimassist <players> set [x angle] [y angle] [max distance] [angle|distance] [preset id]` and
     * `/aimassist <players> clear`, every part of `set` optional from the end.
     */
    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aimassist")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(Commands.argument("players", EntityArgument.players())
                .then(Commands.literal("clear").executes(command -> {
                    Collection<ServerPlayer> players = EntityArgument.getPlayers(command, "players");
                    players.forEach(PlayerAimAssists::clear);
                    return players.size();
                }))
                .then(Commands.literal("set").executes(command -> run(command, TargetMode.ANGLE))
                    .then(Commands.argument("x angle", FloatArgumentType.floatArg())
                        .executes(command -> run(command, TargetMode.ANGLE))
                        .then(Commands.argument("y angle", FloatArgumentType.floatArg())
                            .executes(command -> run(command, TargetMode.ANGLE))
                            .then(Commands.argument("max distance", FloatArgumentType.floatArg())
                                .executes(command -> run(command, TargetMode.ANGLE))
                                .then(targetMode(TargetMode.ANGLE))
                                .then(targetMode(TargetMode.DISTANCE))))))));
    }

    /** A preset id holds a colon, which Brigadier's word stops at, and nothing follows it. */
    private static ArgumentBuilder<CommandSourceStack, ?> targetMode(TargetMode mode) {
        return Commands.literal(mode.commandName()).executes(command -> run(command, mode))
            .then(Commands.argument("preset id", StringArgumentType.greedyString())
                .executes(command -> run(command, mode)));
    }

    private static int run(CommandContext<CommandSourceStack> command, TargetMode mode) throws CommandSyntaxException {
        String written = BedrockCommands.given(command, "preset id")
            ? StringArgumentType.getString(command, "preset id").trim() : AimAssistSettings.DEFAULT_PRESET;
        if (Identifier.tryParse(AimAssistSettings.namespaced(written)) == null) {
            return failed(command, "Invalid aim assist preset name. Preset name should be in the format"
                + " \"namespace:name\" or \"name\" (defaults to \"minecraft\" namespace)");
        }
        AimAssistSettings settings = new AimAssistSettings(AimAssistSettings.namespaced(written), mode,
            number(command, "x angle", AimAssistSettings.DEFAULT_ANGLE),
            number(command, "y angle", AimAssistSettings.DEFAULT_ANGLE),
            number(command, "max distance", AimAssistSettings.DEFAULT_DISTANCE));

        Collection<ServerPlayer> players = EntityArgument.getPlayers(command, "players");
        String refused = players.stream().map(player -> set(player, settings)).filter(answer -> answer != null)
            .findFirst().orElse(null);
        if (refused != null) return failed(command, refused);

        command.getSource().sendSuccess(() -> Component.literal("Applied aim assist settings to the following players: "
            + players.stream().map(player -> player.getName().getString()).collect(Collectors.joining(", "))), true);
        return players.size();
    }

    private static float number(CommandContext<CommandSourceStack> command, String name, float fallback) {
        return BedrockCommands.given(command, name) ? FloatArgumentType.getFloat(command, name) : fallback;
    }

    private static int failed(CommandContext<CommandSourceStack> command, String why) {
        command.getSource().sendFailure(Component.literal(why));
        return 0;
    }

    /** Why Bedrock refuses these settings for this player, in its own words, or null once they are set. */
    public static String set(ServerPlayer player, AimAssistSettings settings) {
        String refused = settings.refusal();
        if (refused != null) return refused;
        if (AddonAimAssist.preset(settings.presetId()) == null) return "Unknown aim assist preset name";

        CameraPreset camera = PlayerCameras.presetOf(player);
        if (camera != null && !SUPPORTED.contains(camera.camera())) {
            return "Current camera type does not support aim assist. The following types are supported:"
                + " third_person, fixed_boom, follow_orbit, free";
        }
        SET.put(player.getUUID(), settings);
        send(player);
        return null;
    }

    public static void clear(ServerPlayer player) {
        SET.remove(player.getUUID());
        send(player);
    }

    /** What `/aimassist` or a script set, which a camera preset's own aim assist leaves in place. */
    public static AimAssistSettings of(ServerPlayer player) {
        return SET.get(player.getUUID());
    }

    /**
     * Sends the player the aim assist in force: their camera preset's where it states one, and what
     * was set otherwise. Called again whenever either changes.
     */
    public static void send(ServerPlayer player) {
        CameraPreset camera = PlayerCameras.presetOf(player);
        AimAssistSettings fromCamera = camera == null ? null : AddonAimAssist.ofCamera(camera.identifier());
        AimAssistSettings settings = fromCamera != null ? fromCamera : SET.get(player.getUUID());
        AimAssistTargeting targeting = settings == null ? null : AddonAimAssist.targeting(settings);
        Channels.sendInstruction(player, AimAssistTargeting.KIND, targeting == null ? "{}" : targeting.encode());
    }

    public static void forget(ServerPlayer player) {
        SET.remove(player.getUUID());
    }
}
