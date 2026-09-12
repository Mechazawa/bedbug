package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
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
