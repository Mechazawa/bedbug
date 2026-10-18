package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.convert.BedrockSoundNames;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Bedrock's `/music`, which plays a track for everyone in place of the game's own music.
 *
 * Each player's client plays the track as music of its own, which the game's background music waits
 * behind, fades it in and out over the seconds the line gives, loops it when asked and keeps the
 * queue, because only a client knows when a track has played to its end. The server keeps whether
 * the track playing loops, which is what refuses a queue behind it, and the track a player joining
 * later should hear.
 */
public final class Music {
    /** What a client is told, as the kind its instruction is sent under. */
    public static final String KIND = "music";

    private static final float LONGEST_FADE = 10f;

    /** The instruction a player joining now should hear, which is the last track still playing for good. */
    private static JsonObject looping;

    private Music() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("music")
            .requires(source -> Platform.allowedAt(source, 1))
            .then(track("play"))
            .then(track("queue"))
            .then(Commands.literal("stop")
                .executes(command -> stop(command, 0f))
                .then(Commands.argument("fadeSeconds", FloatArgumentType.floatArg(0, LONGEST_FADE))
                    .executes(command -> stop(command, FloatArgumentType.getFloat(command, "fadeSeconds")))))
            .then(Commands.literal("volume")
                .then(Commands.argument("volume", FloatArgumentType.floatArg(0, 1))
                    .executes(Music::volume))));
    }

    /** `<action> <track> [volume] [fadeSeconds] [play_once|loop]`, every part optional from the end. */
    private static LiteralArgumentBuilder<CommandSourceStack> track(String action) {
        return Commands.literal(action)
            .then(Commands.argument("trackName", BasicString.basicString())
                .executes(command -> start(command, action, false))
                .then(Commands.argument("volume", FloatArgumentType.floatArg(0, 1))
                    .executes(command -> start(command, action, false))
                    .then(Commands.argument("fadeSeconds", FloatArgumentType.floatArg(0, LONGEST_FADE))
                        .executes(command -> start(command, action, false))
                        .then(Commands.literal("play_once").executes(command -> start(command, action, false)))
                        .then(Commands.literal("loop").executes(command -> start(command, action, true))))));
    }

    private static int start(CommandContext<CommandSourceStack> command, String action, boolean loop) {
        String written = BasicString.get(command, "trackName");
        String track = soundEvent(written);
        if (track == null) return failed(command, "There is no music track called " + written + ".");
        if (action.equals("queue") && looping != null) {
            return failed(command, "The music playing loops, so nothing queued behind it would play.");
        }

        JsonObject instruction = new JsonObject();
        instruction.addProperty("action", action);
        instruction.addProperty("track", track);
        instruction.addProperty("volume", BedrockCommands.given(command, "volume")
            ? FloatArgumentType.getFloat(command, "volume") : 1f);
        instruction.addProperty("fade", BedrockCommands.given(command, "fadeSeconds")
            ? FloatArgumentType.getFloat(command, "fadeSeconds") : 0f);
        instruction.addProperty("loop", loop);
        if (action.equals("play")) looping = loop ? instruction : null;

        send(command.getSource().getServer(), instruction);
        command.getSource().sendSuccess(() -> Component.literal((action.equals("play") ? "Playing " : "Queued ")
            + written + "."), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> command, float fade) {
        JsonObject instruction = new JsonObject();
        instruction.addProperty("action", "stop");
        instruction.addProperty("fade", fade);
        looping = null;
        send(command.getSource().getServer(), instruction);
        return 1;
    }

    private static int volume(CommandContext<CommandSourceStack> command) {
        JsonObject instruction = new JsonObject();
        instruction.addProperty("action", "volume");
        instruction.addProperty("volume", FloatArgumentType.getFloat(command, "volume"));
        if (looping != null) looping.add("volume", instruction.get("volume"));
        send(command.getSource().getServer(), instruction);
        return 1;
    }

    /** Plays the track still playing for good to a player who arrives after it started. */
    public static void joined(ServerPlayer player) {
        if (looping != null) Channels.sendInstruction(player, KIND, looping.toString());
    }

    /** Forgets the track of a server that is going away. */
    public static void forget() {
        looping = null;
    }

    /**
     * The sound event a track name means: one of the packs' own or the game's under its full name,
     * or a Bedrock name, written whole or as the music disc or game track it is the last part of.
     */
    private static String soundEvent(String written) {
        Identifier named = written.contains(":") ? Identifier.tryParse(written) : null;
        if (named != null && BuiltInRegistries.SOUND_EVENT.containsKey(named)) return named.toString();

        for (String candidate : List.of(written, "record." + written, "music.game." + written)) {
            String java = BedrockSoundNames.java(candidate);
            if (java != null) return java;
        }
        return null;
    }

    private static void send(MinecraftServer server, JsonObject instruction) {
        String body = instruction.toString();
        server.getPlayerList().getPlayers().forEach(player -> Channels.sendInstruction(player, KIND, body));
    }

    private static int failed(CommandContext<CommandSourceStack> command, String why) {
        command.getSource().sendFailure(Component.literal(why));
        return 0;
    }
}
