package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.state.AddonState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ties the add-ons to one running server.
 *
 * Everything with authority lives here: the state store, the script host, and the events that feed
 * it. A client in multiplayer never runs any of this; it is told what to draw.
 */
public final class ServerHooks {
    private static AddonState state;
    private static ScriptHost scripts;

    private ServerHooks() {
    }

    public static AddonState state() {
        return state;
    }

    public static ScriptHost scripts() {
        return scripts;
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(ServerHooks::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.END_SERVER_TICK.register(ServerHooks::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (state != null) {
                EntityPropertySync.seed(entity, state);
                // Bedrock raises this on every entity as it comes into the world, and a pack's
                // starting state is usually set from it.
                EntityEvents.trigger(entity, "minecraft:entity_spawned", null, state);
            }
            raiseEntityEvent("entityLoad", entity);
        });
        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (state != null) EntityPropertySync.sendTo(player, entity, state);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> raiseEntityEvent("entityRemove", entity));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> raiseEntityEvent("entityDie", entity));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> state = null);
        CommandRegistrationCallback.EVENT.register(ServerHooks::registerCommands);
    }

    private static void start(MinecraftServer server) {
        state = AddonState.load(server.getWorldPath(LevelResource.ROOT).resolve("data/mcaddon-state.json"));
        scripts = ScriptHost.start(server, McAddon.packs().packs(), state);
        if (scripts != null) scripts.raiseWorldEvent("worldLoad", Map.of());
    }

    private static void stop() {
        if (scripts != null) {
            scripts.stop();
            scripts = null;
        }
        if (state != null) state.save();
    }

    /** Saving on a schedule as well as on shutdown, so a crash costs at most a minute of pack state. */
    private static void tick(MinecraftServer server) {
        if (scripts != null) scripts.tick(server.getTickCount());
        if (state != null && server.getTickCount() % 1200 == 0) state.save();
    }

    private static void raiseEntityEvent(String event, Entity entity) {
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", new ScriptHost.EntityHandle(entity.getStringUUID()));
        scripts.raiseWorldEvent(event, fields);

        if (entity instanceof ServerPlayer player && event.equals("entityLoad")) {
            Map<String, Object> joined = new LinkedHashMap<>();
            joined.put("player", new ScriptHost.EntityHandle(player.getStringUUID()));
            joined.put("playerId", player.getStringUUID());
            joined.put("playerName", player.getName().getString());
            scripts.raiseWorldEvent("playerJoin", joined);
            scripts.raiseWorldEvent("playerSpawn", joined);
        }
    }

    /**
     * `scriptevent` is how a behaviour pack's data half talks to its script half, fired from entity
     * events, animation timelines and functions. Without it, half of a pack's logic never runs.
     */
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext context, Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("scriptevent")
            .then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(command -> scriptEvent(command.getSource(),
                        StringArgumentType.getString(command, "id"),
                        StringArgumentType.getString(command, "message"))))
                .executes(command -> scriptEvent(command.getSource(),
                    StringArgumentType.getString(command, "id"), ""))));

        dispatcher.register(Commands.literal("mcaddon")
            .then(Commands.literal("packs").executes(command -> {
                McAddon.packs().report().forEach(line ->
                    command.getSource().sendSuccess(() -> Component.literal(line), false));
                return 1;
            }))
            .then(Commands.literal("entities").executes(command -> {
                List<String> report = AddonEntities.report();
                if (report.isEmpty()) {
                    command.getSource().sendSuccess(
                        () -> Component.literal("Every entity component the packs used is implemented."), false);
                    return 1;
                }
                report.forEach(line -> command.getSource().sendSuccess(() -> Component.literal(line), false));
                return 1;
            }))
            .then(Commands.literal("scripts").executes(command -> {
                if (scripts == null) {
                    command.getSource().sendSuccess(() -> Component.literal("No pack scripts are running."), false);
                    return 0;
                }
                scripts.coverage().report().forEach(line ->
                    command.getSource().sendSuccess(() -> Component.literal(line), false));
                return 1;
            })));
    }

    private static int scriptEvent(CommandSourceStack source, String id, String message) {
        if (scripts == null) return 0;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("message", message);
        fields.put("sourceType", source.getEntity() == null ? "Server" : "Entity");
        if (source.getEntity() != null) {
            fields.put("sourceEntity", new ScriptHost.EntityHandle(source.getEntity().getStringUUID()));
        }
        scripts.raiseSystemEvent("scriptEventReceive", fields);
        return 1;
    }
}
