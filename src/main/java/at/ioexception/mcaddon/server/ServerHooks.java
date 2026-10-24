package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.DamageCause;
import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.SwingSounds;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.script.CustomCommands;
import at.ioexception.mcaddon.script.ScriptDebugger;
import at.ioexception.mcaddon.script.ScriptDiagnostics;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.script.ScriptProfiler;
import at.ioexception.mcaddon.state.AddonState;
import com.mojang.brigadier.CommandDispatcher;
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
        ServerTickEvents.START_SERVER_TICK.register(server -> ScriptDiagnostics.beginTick());
        ServerTickEvents.END_SERVER_TICK.register(ServerHooks::tick);
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (state != null) {
                EntityPropertySync.seed(entity, state);
                // Which component groups are on is state a pack changed rather than state it
                // declared, so it is read back before anything asks the entity what it carries.
                if (entity instanceof at.ioexception.mcaddon.entity.AddonEntity addon) {
                    addon.restoreGroups();
                }
                announce(entity);
            }
            at.ioexception.mcaddon.registry.AddonItems.keepDropped(entity);
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
            if (entity instanceof at.ioexception.mcaddon.entity.AddonEntity mount) mount.sendSeatTurns(player);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> entityRemoved(entity));
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register(
            (level, player, broken, was, blockEntity) -> {
                at.ioexception.mcaddon.entity.AddonEntity.noticeBlockBroken(level, broken, was);
                forgetBlockStore(level, broken, was);
            });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            at.ioexception.mcaddon.entity.AddonEntity.noticeHuntSucceeded(entity);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("deadEntity", ScriptHost.EntityHandle.of(entity));
            fields.put("damageSource", DamageSources.describe(source));
            if (scripts != null) scripts.raiseWorldEvent("entityDie", fields);
        });
        // Fabric gained an after-damage event in 1.21; before that a pack learns of damage only
        // through the death event.
        //? if >=1.21 {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("hurtEntity", ScriptHost.EntityHandle.of(entity));
            fields.put("damage", (double) taken);
            fields.put("damageSource", DamageSources.describe(source));
            if (scripts != null) scripts.raiseWorldEvent("entityHurt", fields);
        });
        //?}
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> state = null);
        CommandRegistrationCallback.EVENT.register(ServerHooks::registerCommands);
        Visitors.register();
        WorldEvents.register();
        WebSockets.register();
        Channels.receiveRequests((player, kind, body) -> {
            if (WorldPackSettings.KIND.equals(kind)) {
                WorldPackSettings.request(player, body);
            } else {
                WebSockets.request(player, kind, body);
            }
        });
        // Both sides are asked whatever the other answers: a pack's script watches for the attempt
        // and not only for the damage that lands.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            boolean lands = damageSensor(entity, source, amount);
            if (at.ioexception.mcaddon.registry.AddonItems.absorbed(entity, source, amount)) {
                return false;
            }
            return !hurtRefused(entity, source, amount) && lands;
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register(
            (level, player, pos, broken, blockEntity) -> Sensors.blockBroken(level, player, pos, broken));
        Dialogues.register();
        // One channel and one screen serve both, so the id's sign is what says which is waiting.
        Channels.receiveFormResponse((player, formId, answer) -> {
            if (Dialogues.answers(formId)) {
                Dialogues.respond(player, formId, answer);
            } else if (scripts != null) {
                scripts.formResponse(player, formId, answer);
            }
        });
        Channels.receiveSwingMissed(player -> {
            SwingSounds swings = AddonItems.swingSoundsOf(player.getMainHandItem().getItem());
            if (swings != null) swings.play(player, SwingSounds.Moment.MISS);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            startState(handler.player);
            Music.joined(handler.player);
            BedrockRules.joined(handler.player);
            WorldPackSettings.tell(handler.player);
            ServerIdentity.joined(handler.player);
            playerJoined(handler.player);
        });
        // A connection is dropped on the thread reading it, and a script and the fog stacks belong
        // to the server thread, so the event is handed over rather than handled where the drop was
        // noticed.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer left = handler.player;
            server.execute(() -> {
                PlayerCameras.forget(left);
                PlayerControlSchemes.forget(left);
                PlayerFogs.forget(left);
                PlayerHuds.forget(left);
                PlayerInputs.forget(left);
                PlayerAimAssists.forget(left);
                PropertyOverrides.forget(left);
                WebSockets.forget(left);
                playerLeft(left);
            });
        });
        // A respawn is the other way into the world, and the flag is how a pack tells the two apart.
        ServerPlayerEvents.AFTER_RESPAWN.register((from, to, alive) -> {
            PlayerDefinition player = PlayerDefinition.declared();
            if (player != null) player.applyTo(to);
            playerSpawned(to, false);
        });
    }

    /**
     * The food and experience a pack's own `entities/player.json` says a player starts with.
     *
     * Bedrock builds these into the player the moment it makes one, so they belong to a first join
     * and to a respawn rather than to every login: a second login has to read back what the last one
     * left. Nothing in Java writes down that a player has been here before, so the state store this
     * mod already keeps per player is what remembers it.
     */
    private static void startState(ServerPlayer player) {
        PlayerDefinition declared = PlayerDefinition.declared();
        if (declared == null || state == null) return;
        if (state.property(player.getUUID(), "player_start") != null) return;

        state.setProperty(player.getUUID(), "player_start", true);
        declared.applyTo(player);
    }

    /** A block a pack gave a store of its own takes that store with it when it is broken. */
    private static void forgetBlockStore(net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos position, net.minecraft.world.level.block.state.BlockState was) {
        if (state == null) return;
        if (!(was.getBlock() instanceof at.ioexception.mcaddon.block.AddonBlock block)) return;
        if (!block.holdsState()) return;

        state.forgetBlock(at.ioexception.mcaddon.state.AddonState.blockKey(
            at.ioexception.mcaddon.platform.Platform.idOf(level.dimension()).toString(),
            position.getX(), position.getY(), position.getZ()));
    }

    /** A pack may refuse damage outright, which is what Bedrock's before event is for. */
    private static boolean hurtRefused(net.minecraft.world.entity.LivingEntity entity,
        net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (scripts == null) return false;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hurtEntity", ScriptHost.EntityHandle.of(entity));
        fields.put("damage", (double) amount);
        fields.put("damageSource", DamageSources.describe(source));
        return scripts.raiseCancellable("entityHurt", fields);
    }

    /**
     * Fabric raises the join inside `placeNewPlayer`, before the player list and the level hold the
     * player, so the script is handed the player through `remember` until they do.
     */
    private static void playerJoined(ServerPlayer player) {
        if (scripts == null) return;

        at.ioexception.mcaddon.script.ScriptBridge.remember(player);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("playerId", player.getStringUUID());
        fields.put("playerName", player.getName().getString());
        scripts.raiseWorldEvent("playerJoin", fields);
        playerSpawned(player, true);
    }

    private static void playerSpawned(ServerPlayer player, boolean initial) {
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("initialSpawn", initial);
        scripts.raiseWorldEvent("playerSpawn", fields);
    }

    /** The after event names a player who is already gone, so it carries names and not a handle. */
    private static void playerLeft(ServerPlayer player) {
        WorldEvents.forget(player);
        Dialogues.forget(player.getUUID());
        if (scripts == null) return;

        Map<String, Object> leaving = new LinkedHashMap<>();
        leaving.put("player", ScriptHost.EntityHandle.of(player));
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
     * player's sword without the damage itself carrying that meaning. False when a matching trigger
     * says the damage does not land, which is how a pack makes its entity immune to one kind of
     * harm without making it immune to everything.
     */
    private static boolean damageSensor(net.minecraft.world.entity.LivingEntity entity,
        net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (state == null || !(entity instanceof at.ioexception.mcaddon.entity.AddonEntity addon)) return true;
        if (addon.definition() == null) return true;

        com.google.gson.JsonObject sensor = addon.liveComponent("minecraft:damage_sensor");
        if (sensor == null) return true;

        boolean lands = true;
        for (com.google.gson.JsonElement element
            : at.ioexception.mcaddon.util.Json.list(sensor, "triggers")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject trigger = element.getAsJsonObject();

            DamageCause cause = DamageCause.named(
                at.ioexception.mcaddon.util.Json.text(trigger, "cause", "all"));
            if (cause == null || !cause.matches(source)) continue;

            com.google.gson.JsonObject onDamage = trigger.getAsJsonObject("on_damage");
            at.ioexception.mcaddon.bedrock.behaviour.Filter filter =
                at.ioexception.mcaddon.bedrock.behaviour.Filter.parse(
                    onDamage == null ? null : onDamage.get("filters"));
            if (!filter.matches(new at.ioexception.mcaddon.bedrock.behaviour.Filter.Context(
                entity, source.getEntity() == null ? entity : source.getEntity(), null, state, source, amount))) {
                continue;
            }

            if (trigger.has("deals_damage") && !dealsDamage(trigger.get("deals_damage"))) lands = false;

            String event = onDamage == null
                ? null : at.ioexception.mcaddon.util.Json.text(onDamage, "event", null);
            if (event != null) EntityEvents.trigger(entity, event, source.getEntity(), state);
        }
        return lands;
    }

    /** Bedrock writes this as a boolean, or as one of `yes`, `no` and `no_but_side_effects`. */
    private static boolean dealsDamage(com.google.gson.JsonElement value) {
        if (!value.isJsonPrimitive()) return true;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        return !value.getAsString().startsWith("no");
    }

    private static void start(MinecraftServer server) {
        state = AddonState.load(server.getWorldPath(LevelResource.ROOT).resolve("data/mcaddon-state.json"));
        TickingAreas.load(state);
        AreaSchedules.load(state);
        MobEvents.load(state, server);
        BedrockRules.load(state);
        WorldPackSettings.load(state);
        Cheats.start(server);
        ServerIdentity.start();
        Visitors.load(state);
        scripts = ScriptHost.start(server, McAddon.packs().packs(), state);
        if (scripts != null) {
            CustomCommands.install(server, scripts);
            scripts.raiseWorldEvent("worldLoad", Map.of());
        }
    }

    private static void stop() {
        ScriptDebugger.closeCurrent();
        ScriptProfiler.stop();
        ScriptDiagnostics.stopCapture();
        if (scripts != null) {
            scripts.raiseSystemBeforeEvent("shutdown", Map.of());
            // A test area started and never cleared is holding its chunks, and a chunk held by a
            // server that is going away is held by nobody.
            scripts.bridge().gametests.clearAll();
            scripts.stop();
            scripts = null;
        }
        WorldEvents.forget();
        at.ioexception.mcaddon.structure.StructurePlacement.forget();
        Music.forget();
        WorldPackSettings.forget();
        if (state != null) state.save();
    }

    /** Saving on a schedule as well as on shutdown, so a crash costs at most a minute of pack state. */
    private static void tick(MinecraftServer server) {
        if (scripts != null) scripts.tick(server.getTickCount());
        WorldEvents.endTick(server);
        at.ioexception.mcaddon.structure.StructurePlacement.tick();
        AreaSchedules.tick(server);
        SelectorTags.finishAll();
        if (state != null && server.getTickCount() % 1200 == 0) state.save();
        ScriptDiagnostics.endTick(server, scripts);
    }

    /**
     * Held until the next tick rather than raised in place, the way Bedrock delivers it. A pack's
     * `spawnEntity` returns before its listeners hear about the entity, so a pack that spawns one and
     * then marks it as its own has done both by the time a listener asks whether it is marked.
     */
    private static boolean born(Entity entity) {
        return entity instanceof at.ioexception.mcaddon.entity.AddonEntity addon && addon.born();
    }

    /**
     * Bedrock raises this once, as an entity comes into the world, and a pack's starting state is
     * usually set from it. A chunk loading the entity again does not raise it. A bred one is born
     * rather than spawned.
     */
    public static void announce(Entity entity) {
        if (state == null || !state.firstAnnouncement(entity.getUUID())) return;

        EntityEvents.trigger(entity, born(entity) ? "minecraft:entity_born" : "minecraft:entity_spawned", null, state);
    }

    private static void raiseEntityEvent(String event, Entity entity) {
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ScriptHost.EntityHandle.of(entity));
        fields.put("cause", !event.equals("entitySpawn") ? "Loaded" : born(entity) ? "Born" : "Spawned");
        scripts.queueWorldEvent(event, fields);
    }

    /** The after event names an entity that no longer exists, so it carries its id and its type. */
    private static void entityRemoved(Entity entity) {
        if (scripts == null) return;

        Map<String, Object> leaving = new LinkedHashMap<>();
        leaving.put("removedEntity", ScriptHost.EntityHandle.of(entity));
        scripts.raiseCancellable("entityRemove", leaving);

        Map<String, Object> removed = new LinkedHashMap<>();
        removed.put("removedEntityId", entity.getStringUUID());
        removed.put("typeId", AddonEntities.identifierOf(entity.getType()));
        scripts.raiseWorldEvent("entityRemove", removed);
    }

    /**
     * Java's tree gains `/bedrock`, which every Bedrock command is reached through, and `/mcaddon`,
     * which reports what the packs asked for.
     */
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext context, Commands.CommandSelection selection) {
        BedrockTree.register(dispatcher, context);

        dispatcher.register(Commands.literal("mcaddon")
            .then(Commands.literal("packs").executes(command -> {
                McAddon.packs().report().forEach(line ->
                    command.getSource().sendSuccess(() -> Component.literal(line), false));
                return 1;
            }))
            .then(Commands.literal("entities").executes(command -> {
                entitiesReport().forEach(line ->
                    command.getSource().sendSuccess(() -> Component.literal(line), false));
                return 1;
            }))
            .then(Commands.literal("scripts").executes(command -> {
                scriptsReport().forEach(line ->
                    command.getSource().sendSuccess(() -> Component.literal(line), false));
                return scripts == null ? 0 : 1;
            })));
    }

    /** What `/mcaddon entities` prints: the gaps in what the packs' data asked for, then what was declined. */
    public static List<String> entitiesReport() {
        List<String> report = new java.util.ArrayList<>(AddonEntities.report());
        if (report.isEmpty()) report.add("Every entity component the packs used is implemented.");
        report.addAll(AddonEntities.answers());
        return report;
    }

    /** What `/mcaddon scripts` prints, for the scripts running in this process. */
    public static List<String> scriptsReport() {
        return scripts == null ? List.of("No pack scripts are running.") : scripts.coverage().report();
    }

    /**
     * `scriptevent` is how a behaviour pack's data half talks to its script half, fired from entity
     * events, animation timelines and functions. Without it, half of a pack's logic never runs.
     */
    static int scriptEvent(CommandSourceStack source, String id, String message) {
        if (scripts == null) return 0;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("message", message);
        fields.put("sourceType", source.getEntity() == null ? "Server" : "Entity");
        if (source.getEntity() != null) {
            fields.put("sourceEntity", ScriptHost.EntityHandle.of(source.getEntity()));
        }
        scripts.raiseSystemEvent("scriptEventReceive", fields);
        return 1;
    }
}
