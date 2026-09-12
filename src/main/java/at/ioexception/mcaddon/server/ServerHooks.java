package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.network.PackList;
import at.ioexception.mcaddon.script.CustomCommands;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.state.AddonState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
            // Bedrock separates the two: a chunk load raises one, coming into existence raises both.
            if (entity.tickCount == 0) {
                at.ioexception.mcaddon.script.ScriptBridge.remember(entity);
                raiseEntityEvent("entitySpawn", entity);
                WorldEvents.spawned(entity);
            }
        });
        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (state != null) EntityPropertySync.sendTo(player, entity, state);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> entityRemoved(entity));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("deadEntity", new ScriptHost.EntityHandle(entity.getStringUUID()));
            fields.put("damageSource", DamageSources.describe(source));
            if (scripts != null) scripts.raiseWorldEvent("entityDie", fields);
        });
        // Fabric gained an after-damage event in 1.21; before that a pack learns of damage only
        // through the death event.
        //? if >=1.21 {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("hurtEntity", new ScriptHost.EntityHandle(entity.getStringUUID()));
            fields.put("damage", (double) taken);
            fields.put("damageSource", DamageSources.describe(source));
            if (scripts != null) scripts.raiseWorldEvent("entityHurt", fields);
        });
        //?}
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> state = null);
        CommandRegistrationCallback.EVENT.register(ServerHooks::registerCommands);
        WorldEvents.register();
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            damageSensor(entity, source);
            return !hurtRefused(entity, source, amount);
        });
        Channels.receiveFormResponse((formId, answer) -> {
            if (scripts != null) scripts.formResponse(formId, answer);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            Channels.sendPacks(handler.player, PackList.of(McAddon.packs()).encode());
            playerJoined(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> playerLeft(handler.player));
        // A respawn is the other way into the world, and the flag is how a pack tells the two apart.
        ServerPlayerEvents.AFTER_RESPAWN.register((from, to, alive) -> playerSpawned(to, false));
    }

    /** A pack may refuse damage outright, which is what Bedrock's before event is for. */
    private static boolean hurtRefused(net.minecraft.world.entity.LivingEntity entity,
        net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (scripts == null) return false;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hurtEntity", new ScriptHost.EntityHandle(entity.getStringUUID()));
        fields.put("damage", (double) amount);
        fields.put("damageSource", DamageSources.describe(source));
        return scripts.raiseCancellable("entityHurt", fields);
    }

    private static void playerJoined(ServerPlayer player) {
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", new ScriptHost.EntityHandle(player.getStringUUID()));
        fields.put("playerId", player.getStringUUID());
        fields.put("playerName", player.getName().getString());
        scripts.raiseWorldEvent("playerJoin", fields);
        playerSpawned(player, true);
    }

    private static void playerSpawned(ServerPlayer player, boolean initial) {
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", new ScriptHost.EntityHandle(player.getStringUUID()));
        fields.put("initialSpawn", initial);
        scripts.raiseWorldEvent("playerSpawn", fields);
    }

    /** The after event names a player who is already gone, so it carries names and not a handle. */
    private static void playerLeft(ServerPlayer player) {
        if (scripts == null) return;

        Map<String, Object> leaving = new LinkedHashMap<>();
        leaving.put("player", new ScriptHost.EntityHandle(player.getStringUUID()));
        scripts.raiseCancellable("playerLeave", leaving);

        Map<String, Object> left = new LinkedHashMap<>();
        left.put("playerId", player.getStringUUID());
        left.put("playerName", player.getName().getString());
        scripts.raiseWorldEvent("playerLeave", left);
    }

    /**
     * `minecraft:damage_sensor` is how a pack reacts to its entity being hurt.
     *
     * The sensor's own filters run against the damage's source, so a pack can tell a fall from a
     * player's sword without the damage itself carrying that meaning.
     */
    private static void damageSensor(net.minecraft.world.entity.LivingEntity entity,
        net.minecraft.world.damagesource.DamageSource source) {
        if (state == null || !(entity instanceof at.ioexception.mcaddon.entity.AddonEntity addon)) return;
        if (addon.definition() == null) return;

        com.google.gson.JsonObject sensor = addon.definition().components().get("minecraft:damage_sensor");
        if (sensor == null) return;

        for (com.google.gson.JsonElement element
            : at.ioexception.mcaddon.util.Json.list(sensor, "triggers")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject trigger = element.getAsJsonObject();

            String cause = at.ioexception.mcaddon.util.Json.text(trigger, "cause", null);
            if (cause != null && !cause.equals("any") && !cause.equals(source.getMsgId())) continue;

            com.google.gson.JsonObject onDamage = trigger.getAsJsonObject("on_damage");
            String event = onDamage == null
                ? null : at.ioexception.mcaddon.util.Json.text(onDamage, "event", null);
            if (event == null) continue;

            at.ioexception.mcaddon.bedrock.behaviour.Filter filter =
                at.ioexception.mcaddon.bedrock.behaviour.Filter.parse(onDamage.get("filters"));
            if (!filter.matches(new at.ioexception.mcaddon.bedrock.behaviour.Filter.Context(
                entity, source.getEntity() == null ? entity : source.getEntity(), null, state))) {
                continue;
            }
            EntityEvents.trigger(entity, event, source.getEntity(), state);
        }
    }

    private static void start(MinecraftServer server) {
        state = AddonState.load(server.getWorldPath(LevelResource.ROOT).resolve("data/mcaddon-state.json"));
        scripts = ScriptHost.start(server, McAddon.packs().packs(), state);
        if (scripts != null) {
            CustomCommands.install(server, scripts);
            scripts.raiseWorldEvent("worldLoad", Map.of());
        }
    }

    private static void stop() {
        if (scripts != null) {
            scripts.raiseSystemBeforeEvent("shutdown", Map.of());
            scripts.stop();
            scripts = null;
        }
        WorldEvents.forget();
        if (state != null) state.save();
    }

    /** Saving on a schedule as well as on shutdown, so a crash costs at most a minute of pack state. */
    private static void tick(MinecraftServer server) {
        if (scripts != null) scripts.tick(server.getTickCount());
        WorldEvents.endTick(server);
        if (state != null && server.getTickCount() % 1200 == 0) state.save();
    }

    private static void raiseEntityEvent(String event, Entity entity) {
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", new ScriptHost.EntityHandle(entity.getStringUUID()));
        fields.put("cause", event.equals("entitySpawn") ? "Spawned" : "Loaded");
        scripts.raiseWorldEvent(event, fields);
    }

    /** The after event names an entity that no longer exists, so it carries its id and its type. */
    private static void entityRemoved(Entity entity) {
        if (scripts == null) return;

        Map<String, Object> leaving = new LinkedHashMap<>();
        leaving.put("removedEntity", new ScriptHost.EntityHandle(entity.getStringUUID()));
        scripts.raiseCancellable("entityRemove", leaving);

        Map<String, Object> removed = new LinkedHashMap<>();
        removed.put("removedEntityId", entity.getStringUUID());
        removed.put("typeId",
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        scripts.raiseWorldEvent("entityRemove", removed);
    }

    /**
     * `scriptevent` is how a behaviour pack's data half talks to its script half, fired from entity
     * events, animation timelines and functions. Without it, half of a pack's logic never runs.
     */
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext context, Commands.CommandSelection selection) {
        BedrockCommands.register(dispatcher);

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
