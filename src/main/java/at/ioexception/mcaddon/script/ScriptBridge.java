package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.structure.McStructure;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.script.surface.Blocks;
import at.ioexception.mcaddon.script.surface.Containers;
import at.ioexception.mcaddon.script.surface.EntityMembers;
import at.ioexception.mcaddon.script.surface.GameTests;
import at.ioexception.mcaddon.script.surface.Items;
import at.ioexception.mcaddon.script.surface.Players;
import at.ioexception.mcaddon.script.surface.Registries;
import at.ioexception.mcaddon.script.surface.TickingAreas;
import at.ioexception.mcaddon.script.surface.Worlds;
import at.ioexception.mcaddon.server.DamageSources;
import at.ioexception.mcaddon.server.PackCommand;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * The Java side of `@minecraft/server`.
 *
 * Every method here is reachable from a pack's script, so each one takes handles and primitives and
 * nothing else: an entity is its UUID string, a dimension is its id. Anything a script asks for that
 * this mod does not implement goes through {@link #unsupported} and is reported rather than thrown, so
 * one missing member costs a pack that feature and not its whole script.
 *
 * String lists are returned as arrays and wrapped on the JavaScript side; maps are returned as JSON
 * text, because a Java map reaches the guest as a host object with no JavaScript methods on it.
 */
public final class ScriptBridge {
    private final MinecraftServer server;
    private final AddonState state;
    private final ScriptCoverage coverage;

    /**
     * The namespace of the add-on whose script runs here, or null when several add-ons ship one.
     *
     * Every pack's script shares one bridge and calls it through one module, so a command a script
     * runs carries nothing saying which of several packs wrote it.
     */
    private final String namespace;
    private long tickDeadline;
    private final Map<Integer, String> openForms = new java.util.LinkedHashMap<>();

    @HostAccess.Export
    public final Blocks blocks;

    @HostAccess.Export
    public final Containers containers;

    @HostAccess.Export
    public final Players players;

    @HostAccess.Export
    public final Items items;

    @HostAccess.Export
    public final Registries registries;

    @HostAccess.Export
    public final EntityMembers entityMembers;

    @HostAccess.Export
    public final Worlds worlds;

    @HostAccess.Export
    public final GameTests gametests;

    @HostAccess.Export
    public final TickingAreas tickingAreas;

    ScriptBridge(MinecraftServer server, AddonState state, ScriptCoverage coverage, String namespace) {
        this.server = server;
        this.state = state;
        this.coverage = coverage;
        this.namespace = namespace;
        this.blocks = new Blocks(server, coverage);
        this.containers = new Containers(server, coverage);
        this.players = new Players(server, coverage);
        this.items = new Items(server, coverage);
        this.registries = new Registries(server, coverage);
        this.entityMembers = new EntityMembers(server, coverage, state);
        this.worlds = new Worlds(server, coverage);
        this.gametests = new GameTests(server, coverage);
        this.tickingAreas = new TickingAreas(server, coverage);
    }

    void beginTick(long budgetNanos) {
        tickDeadline = System.nanoTime() + budgetNanos;
    }

    // Diagnostics

    @HostAccess.Export
    public void unsupported(String member) {
        coverage.unsupported(member);
    }

    @HostAccess.Export
    public void reportError(String message) {
        McAddon.LOGGER.error("Script error: {}", message);
    }

    @HostAccess.Export
    public boolean outOfBudget() {
        return System.nanoTime() > tickDeadline;
    }

    @HostAccess.Export
    public int currentTick() {
        return server.getTickCount();
    }

    @HostAccess.Export
    public String translate(String key) {
        return Component.translatable(key).getString();
    }

    /**
     * The `MemoryTier` band this server sits in, measured from the heap the JVM was given.
     *
     * Bedrock reads the device's own memory; the nearest thing a Java server knows about itself is
     * how much it is allowed to allocate, so the bands are drawn over that.
     */
    @HostAccess.Export
    public int memoryTier() {
        long megabytes = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        if (megabytes < 1536) return 0;
        if (megabytes < 3072) return 1;
        if (megabytes < 6144) return 2;
        return megabytes < 12288 ? 3 : 4;
    }

    // World

    @HostAccess.Export
    public void broadcastMessage(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    @HostAccess.Export
    public boolean isHardcore() {
        return server.isHardcore();
    }

    @HostAccess.Export
    public long timeOfDay() {
        return Platform.dayTime(server.overworld()) % 24000L;
    }

    @HostAccess.Export
    public void setTimeOfDay(int time) {
        // Through the command rather than the level: the setter was renamed and the command was not.
        run(server.createCommandSourceStack(), "time set " + time);
    }

    @HostAccess.Export
    public int day() {
        return (int) (Platform.dayTime(server.overworld()) / 24000L);
    }

    @HostAccess.Export
    public long absoluteTime() {
        return server.overworld().getGameTime();
    }

    @HostAccess.Export
    public int moonPhase() {
        return (int) (Platform.dayTime(server.overworld()) / 24000L % 8L);
    }

    @HostAccess.Export
    public double[] defaultSpawnLocation() {
        BlockPos spawn = worldSpawn();
        return new double[] {spawn.getX(), spawn.getY(), spawn.getZ()};
    }

    /** A game rule both editions have. Bedrock's own rules have nowhere to read from. */
    @HostAccess.Export
    public String gameRule(String name) {
        String value = Platform.gameRule(server, name);
        if (value == null) unsupported("GameRules." + name);
        return value;
    }

    @HostAccess.Export
    public void setGameRule(String name, Value value) {
        if (!Platform.setGameRule(server, name, String.valueOf(value))) {
            unsupported("GameRules." + name + " (write)");
        }
    }

    @HostAccess.Export
    public String[] dimensionIds() {
        List<String> ids = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) ids.add(Platform.idOf(level.dimension()).toString());
        return ids.toArray(String[]::new);
    }

    @HostAccess.Export
    public String[] players() {
        return server.getPlayerList().getPlayers().stream()
            .map(ServerPlayer::getStringUUID)
            .toArray(String[]::new);
    }

    /** The online players the query keeps, in whichever dimension each of them is in. */
    @HostAccess.Export
    public String[] matchingPlayers(String queryJson) {
        EntityQuery query = EntityQuery.parse(queryJson, this);
        List<String> found = server.getPlayerList().getPlayers().stream()
            .filter(query::matches)
            .map(ServerPlayer::getStringUUID)
            .toList();
        return query.limit(found).toArray(String[]::new);
    }

    @HostAccess.Export
    public Object worldDynamicProperty(String name) {
        return state.worldProperty(name);
    }

    @HostAccess.Export
    public void setWorldDynamicProperty(String name, Value value) {
        state.setWorldProperty(name, plain(value));
    }

    @HostAccess.Export
    public String[] worldDynamicPropertyIds() {
        return state.worldPropertyNames().toArray(String[]::new);
    }

    @HostAccess.Export
    public int worldDynamicPropertyBytes() {
        return state.worldPropertyBytes();
    }

    // Entities

    @HostAccess.Export
    public boolean entityIsValid(String id) {
        Entity entity = entity(id);
        return entity != null && entity.isAlive();
    }

    /**
     * Whether an id names somebody playing, which decides the class a script is handed.
     *
     * The player list is asked first and answers for anybody connected, however far through joining
     * they are. Searching the levels alone would say no for the moment between a player connecting
     * and the level indexing them, and a script told `Entity` then has no player members at all.
     */
    @HostAccess.Export
    public boolean entityIsPlayer(String id) {
        UUID uuid = uuid(id);
        if (uuid != null && server.getPlayerList().getPlayer(uuid) != null) return true;
        return entity(id) instanceof ServerPlayer;
    }

    @HostAccess.Export
    public String entityTypeId(String id) {
        Entity entity = entity(id);
        return entity == null ? null : AddonEntities.identifierOf(entity.getType());
    }

    @HostAccess.Export
    public String entityDimension(String id) {
        Entity entity = entity(id);
        return entity == null ? null : Platform.idOf(entity.level().dimension()).toString();
    }

    @HostAccess.Export
    public double[] entityLocation(String id) {
        Entity entity = entity(id);
        return entity == null ? new double[] {0, 0, 0}
            : new double[] {entity.getX(), entity.getY(), entity.getZ()};
    }

    @HostAccess.Export
    public double[] entityHeadLocation(String id) {
        Entity entity = entity(id);
        if (entity == null) return new double[] {0, 0, 0};
        Vec3 eyes = entity.getEyePosition();
        return new double[] {eyes.x, eyes.y, eyes.z};
    }

    @HostAccess.Export
    public double[] entityRotation(String id) {
        Entity entity = entity(id);
        return entity == null ? new double[] {0, 0} : new double[] {entity.getXRot(), entity.getYRot()};
    }

    @HostAccess.Export
    public void setEntityRotation(String id, double pitch, double yaw) {
        Entity entity = entity(id);
        if (entity == null) return;
        entity.setXRot((float) pitch);
        entity.setYRot((float) yaw);
    }

    @HostAccess.Export
    public double[] entityViewDirection(String id) {
        Entity entity = entity(id);
        if (entity == null) return new double[] {0, 0, 1};
        Vec3 view = entity.getViewVector(1);
        return new double[] {view.x, view.y, view.z};
    }

    /**
     * The first solid block along the entity's line of sight, and which face was hit.
     *
     * The face comes back as the index of the direction so the guest can name it; null means the ray
     * reached its limit without hitting anything.
     */
    @HostAccess.Export
    public int[] blockFromViewDirection(String id, double maxDistance) {
        Entity entity = entity(id);
        if (entity == null) return null;

        Vec3 from = entity.getEyePosition();
        Vec3 to = from.add(entity.getViewVector(1).scale(maxDistance));
        net.minecraft.world.level.ClipContext context = new net.minecraft.world.level.ClipContext(
            from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE, entity);

        net.minecraft.world.phys.BlockHitResult hit = entity.level().clip(context);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return null;

        net.minecraft.core.BlockPos position = hit.getBlockPos();
        return new int[] {position.getX(), position.getY(), position.getZ(), hit.getDirection().ordinal()};
    }

    @HostAccess.Export
    public double[] entityVelocity(String id) {
        Entity entity = entity(id);
        if (entity == null) return new double[] {0, 0, 0};
        Vec3 movement = entity.getDeltaMovement();
        return new double[] {movement.x, movement.y, movement.z};
    }

    @HostAccess.Export
    public void applyImpulse(String id, double x, double y, double z) {
        Entity entity = entity(id);
        if (entity == null) return;
        entity.setDeltaMovement(x == 0 && y == 0 && z == 0
            ? Vec3.ZERO
            : entity.getDeltaMovement().add(x, y, z));
        entity.hurtMarked = true;
    }

    /**
     * Moves an entity, into another dimension when the options name one.
     *
     * False when `checkForBlocks` is set and the entity would not fit where it is being sent, which
     * is the answer `tryTeleport` reports. Velocity is dropped unless `keepVelocity` asks to keep
     * it, the way Bedrock drops it.
     */
    @HostAccess.Export
    public boolean teleport(String id, double x, double y, double z, String optionsJson) {
        Entity entity = entity(id);
        if (entity == null) return false;

        JsonObject options = com.google.gson.JsonParser.parseString(optionsJson).getAsJsonObject();
        String named = text(options, "dimension");
        ServerLevel target = named != null ? level(named)
            : entity.level() instanceof ServerLevel current ? current : null;
        if (target == null) return false;

        if (flag(options, "checkForBlocks") && !target.noCollision(entity,
            entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ()))) {
            return false;
        }

        float[] rotation = facing(options, entity, x, y, z);
        Vec3 velocity = entity.getDeltaMovement();
        if (!teleportTo(entity, target, x, y, z, rotation[0], rotation[1])) return false;

        // Crossing a dimension rebuilds the entity, so whatever answers to the id afterwards is the
        // one whose movement has to be set.
        Entity placed = entity(id);
        if (placed == null) return true;
        placed.setDeltaMovement(flag(options, "keepVelocity") ? velocity : Vec3.ZERO);
        placed.hurtMarked = true;
        return true;
    }

    /** The yaw and pitch to land on: a facing point beats a rotation, which beats standing still. */
    private static float[] facing(JsonObject options, Entity entity, double x, double y, double z) {
        JsonObject point = object(options, "facingLocation");
        if (point != null) {
            double towardsX = point.get("x").getAsDouble() - x;
            double towardsY = point.get("y").getAsDouble() - (y + entity.getEyeHeight());
            double towardsZ = point.get("z").getAsDouble() - z;
            double flat = Math.sqrt(towardsX * towardsX + towardsZ * towardsZ);
            return new float[] {(float) (Math.toDegrees(Math.atan2(towardsZ, towardsX)) - 90),
                (float) -Math.toDegrees(Math.atan2(towardsY, flat))};
        }

        JsonObject rotation = object(options, "rotation");
        return rotation == null ? new float[] {entity.getYRot(), entity.getXRot()}
            : new float[] {(float) rotation.get("y").getAsDouble(), (float) rotation.get("x").getAsDouble()};
    }

    /** 26 added a flag for whether a player's camera snaps back to them, which it always does. */
    private static boolean teleportTo(Entity entity, ServerLevel target, double x, double y, double z,
        float yaw, float pitch) {
        //? if >=1.21.2 {
        return entity.teleportTo(target, x, y, z, Set.of(), yaw, pitch, true);
        //?} else {
        /*return entity.teleportTo(target, x, y, z, Set.of(), yaw, pitch);
        *///?}
    }

    @HostAccess.Export
    public String entityNameTag(String id) {
        Entity entity = entity(id);
        Component name = entity == null ? null : entity.getCustomName();
        return name == null ? "" : name.getString();
    }

    @HostAccess.Export
    public void setEntityNameTag(String id, String name) {
        Entity entity = entity(id);
        if (entity != null) entity.setCustomName(name.isEmpty() ? null : Component.literal(name));
    }

    @HostAccess.Export
    public boolean entityFlag(String id, String flag) {
        Entity entity = entity(id);
        if (entity == null) return false;
        return switch (flag) {
            case "sneaking" -> entity.isShiftKeyDown();
            case "onGround" -> entity.onGround();
            case "inWater" -> entity.isInWater();
            case "swimming" -> entity.isSwimming();
            case "climbing" -> entity instanceof LivingEntity living && living.onClimbable();
            case "falling" -> entity.getDeltaMovement().y < -0.08 && !entity.onGround();
            case "sleeping" -> entity instanceof LivingEntity living && living.isSleeping();
            default -> {
                unsupported("Entity." + flag);
                yield false;
            }
        };
    }

    @HostAccess.Export
    public void removeEntity(String id) {
        Entity entity = entity(id);
        if (entity == null) return;
        entity.discard();
        state.forget(entity.getUUID());
    }

    @HostAccess.Export
    public boolean killEntity(String id) {
        Entity entity = entity(id);
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return false;
        Platform.kill(entity, level);
        return true;
    }

    @HostAccess.Export
    public String[] entityTags(String id) {
        Entity entity = entity(id);
        return entity == null ? new String[0] : Platform.tagsOf(entity).toArray(String[]::new);
    }

    @HostAccess.Export
    public boolean addEntityTag(String id, String tag) {
        Entity entity = entity(id);
        return entity != null && entity.addTag(tag);
    }

    @HostAccess.Export
    public boolean removeEntityTag(String id, String tag) {
        Entity entity = entity(id);
        return entity != null && entity.removeTag(tag);
    }

    @HostAccess.Export
    public String[] entityFamilies(String id) {
        Entity entity = entity(id);
        return entity == null ? new String[0] : EntityFamilies.of(entity);
    }

    @HostAccess.Export
    public boolean entityMatches(String id, String queryJson) {
        Entity entity = entity(id);
        return entity != null && EntityQuery.parse(queryJson, this).matches(entity);
    }

    @HostAccess.Export
    public Object entityProperty(String id, String name) {
        Entity entity = entity(id);
        return entity == null ? null : state.property(entity.getUUID(), name);
    }

    @HostAccess.Export
    public void setEntityProperty(String id, String name, Value value) {
        Entity entity = entity(id);
        if (entity == null) return;
        state.setProperty(entity.getUUID(), name, plain(value));
        // Clients draw from these, so a change is pushed rather than waiting for the next full send.
        at.ioexception.mcaddon.server.EntityPropertySync.broadcast(entity, state);
    }

    @HostAccess.Export
    public Object entityDynamicProperty(String id, String name) {
        Entity entity = entity(id);
        return entity == null ? null : state.dynamicProperty(entity.getUUID(), name);
    }

    @HostAccess.Export
    public void setEntityDynamicProperty(String id, String name, Value value) {
        Entity entity = entity(id);
        if (entity != null) state.setDynamicProperty(entity.getUUID(), name, plain(value));
    }

    @HostAccess.Export
    public String[] entityDynamicPropertyIds(String id) {
        Entity entity = entity(id);
        return entity == null ? new String[0] : state.dynamicPropertyNames(entity.getUUID()).toArray(String[]::new);
    }

    /**
     * The store one block keeps of its own, or null where the block was given none.
     *
     * `minecraft:block_entity` is what asks for the store, so a pack reading from a block without
     * that component is answered the way every other unimplemented member is.
     */
    private String blockStore(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        return level.getBlockState(new BlockPos(x, y, z)).getBlock()
            instanceof at.ioexception.mcaddon.block.AddonBlock addon && addon.holdsState()
            ? AddonState.blockKey(dimension, x, y, z) : null;
    }

    @HostAccess.Export
    public Object blockDynamicProperty(String dimension, int x, int y, int z, String name) {
        String store = blockStore(dimension, x, y, z);
        if (store == null) {
            coverage.unsupported("Block.getDynamicProperty");
            return null;
        }
        return state.blockProperty(store, name);
    }

    @HostAccess.Export
    public void setBlockDynamicProperty(String dimension, int x, int y, int z, String name, Value value) {
        String store = blockStore(dimension, x, y, z);
        if (store == null) {
            coverage.unsupported("Block.setDynamicProperty");
            return;
        }
        state.setBlockProperty(store, name, plain(value));
    }

    @HostAccess.Export
    public String[] blockDynamicPropertyIds(String dimension, int x, int y, int z) {
        String store = blockStore(dimension, x, y, z);
        return store == null ? new String[0]
            : state.blockPropertyNames(store).toArray(String[]::new);
    }

    /** Measured the way the world's own count is, so the two numbers a pack compares mean the same. */
    @HostAccess.Export
    public int blockDynamicPropertyBytes(String dimension, int x, int y, int z) {
        String store = blockStore(dimension, x, y, z);
        if (store == null) return 0;

        return state.blockPropertyNames(store).stream()
            .mapToInt(name -> name.length() + String.valueOf(state.blockProperty(store, name)).length())
            .sum();
    }

    /**
     * Starts one of the pack's animations on an entity, for everyone watching it.
     *
     * Animations run on the client, so this is a message rather than a change to anything the server
     * holds; a player who was not watching when it started does not see it, which is what Bedrock
     * does too.
     */
    @HostAccess.Export
    public void playAnimation(String id, String animation) {
        Entity entity = entity(id);
        if (entity != null) at.ioexception.mcaddon.server.Animations.play(entity, animation, "", 0f);
    }

    @HostAccess.Export
    public void triggerEntityEvent(String id, String event) {
        Entity entity = entity(id);
        if (entity != null) at.ioexception.mcaddon.server.EntityEvents.trigger(entity, event, null, state);
    }

    @HostAccess.Export
    public boolean entityHasComponent(String id, String component) {
        Entity entity = entity(id);
        if (entity == null) return false;
        return switch (component) {
            case "minecraft:health", "minecraft:equippable", "minecraft:movement" ->
                entity instanceof LivingEntity;
            case "minecraft:player.hunger", "minecraft:player.saturation" ->
                entity instanceof Player;
            case "minecraft:onfire" -> entity.isOnFire();
            case "minecraft:riding" -> entity.isPassenger();
            case "minecraft:rideable" -> !entity.getPassengers().isEmpty();
            case "minecraft:type_family" -> true;
            case "minecraft:is_baby" -> entity instanceof LivingEntity living && living.isBaby();
            case "minecraft:item" -> entity instanceof ItemEntity;
            default -> false;
        };
    }

    @HostAccess.Export
    public String[] entityComponents(String id) {
        List<String> present = new ArrayList<>();
        for (String component : List.of("minecraft:health", "minecraft:equippable", "minecraft:riding",
            "minecraft:rideable", "minecraft:type_family", "minecraft:is_baby", "minecraft:item",
            "minecraft:movement", "minecraft:onfire", "minecraft:player.hunger",
            "minecraft:player.saturation")) {
            if (entityHasComponent(id, component)) present.add(component);
        }
        return present.toArray(String[]::new);
    }

    @HostAccess.Export
    public double entityHealth(String id) {
        return entity(id) instanceof LivingEntity living ? living.getHealth() : 0;
    }

    @HostAccess.Export
    public double entityMaxHealth(String id) {
        return entity(id) instanceof LivingEntity living ? living.getMaxHealth() : 0;
    }

    @HostAccess.Export
    public void setEntityHealth(String id, double health) {
        if (entity(id) instanceof LivingEntity living) living.setHealth((float) health);
    }

    @HostAccess.Export
    public String entityVehicle(String id) {
        Entity entity = entity(id);
        Entity vehicle = entity == null ? null : entity.getVehicle();
        return vehicle == null ? null : vehicle.getStringUUID();
    }

    @HostAccess.Export
    public String[] entityRiders(String id) {
        Entity entity = entity(id);
        if (entity == null) return new String[0];
        return entity.getPassengers().stream().map(Entity::getStringUUID).toArray(String[]::new);
    }

    @HostAccess.Export
    public boolean addRider(String id, String riderId) {
        Entity vehicle = entity(id);
        Entity rider = entity(riderId);
        return vehicle != null && rider != null && rider.startRiding(vehicle);
    }

    @HostAccess.Export
    public void ejectRider(String id, String riderId) {
        Entity rider = entity(riderId);
        if (rider != null && rider.getVehicle() == entity(id)) rider.stopRiding();
    }

    /** False when the damage did not land: the entity resisted it, or is already gone. */
    @HostAccess.Export
    public boolean applyDamage(String id, double amount, String cause, String sourceId) {
        Entity entity = entity(id);
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return false;
        return hurt(entity, level, DamageSources.forCause(level, cause, entity(sourceId)), (float) amount);
    }

    /**
     * Whether the damage landed.
     *
     * The shared {@link Platform#hurt} returns nothing, where a pack's `applyDamage` answers with
     * whether the entity took the hit.
     */
    private static boolean hurt(Entity entity, ServerLevel level, DamageSource source, float amount) {
        //? if >=1.21.2 {
        return entity.hurtServer(level, source, amount);
        //?} else {
        /*return entity.hurt(source, amount);
        *///?}
    }

    @HostAccess.Export
    public boolean addEffect(String id, String effect, int duration, int amplifier, boolean particles) {
        Identifier parsed = Identifier.tryParse(effect);
        return parsed != null && entity(id) instanceof LivingEntity living
            && Platform.addEffect(living, parsed, duration, amplifier, particles);
    }

    /** Every effect the entity is under, as the JSON a script's `Effect` objects are built from. */
    @HostAccess.Export
    public String entityEffects(String id) {
        JsonArray effects = new JsonArray();
        if (!(entity(id) instanceof LivingEntity living)) return effects.toString();

        for (MobEffectInstance active : living.getActiveEffects()) {
            JsonObject effect = new JsonObject();
            effect.addProperty("typeId", Platform.effectId(active));
            effect.addProperty("duration", active.getDuration());
            effect.addProperty("amplifier", active.getAmplifier());
            effect.addProperty("displayName", Component.translatable(active.getDescriptionId()).getString());
            effects.add(effect);
        }
        return effects.toString();
    }

    @HostAccess.Export
    public boolean removeEffect(String id, String effect) {
        Identifier parsed = Identifier.tryParse(effect);
        return parsed != null && entity(id) instanceof LivingEntity living
            && Platform.removeEffect(living, parsed);
    }

    @HostAccess.Export
    public String equipment(String id, String slot) {
        EquipmentSlot which = equipmentSlot(slot);
        if (which == null || !(entity(id) instanceof LivingEntity living)) return null;
        ItemStack held = living.getItemBySlot(which);
        if (held.isEmpty()) return null;
        JsonObject item = new JsonObject();
        item.addProperty("typeId", AddonItems.identifierOf(held.getItem()));
        item.addProperty("amount", held.getCount());
        item.addProperty("damage", held.getDamageValue());
        item.addProperty("maxDurability", held.getMaxDamage());
        return item.toString();
    }

    @HostAccess.Export
    public boolean setEquipment(String id, String slot, String itemId, int amount) {
        EquipmentSlot which = equipmentSlot(slot);
        if (which == null || !(entity(id) instanceof LivingEntity living)) return false;
        living.setItemSlot(which, itemId == null ? ItemStack.EMPTY : stack(itemId, amount));
        return true;
    }

    // Players

    @HostAccess.Export
    public String playerName(String id) {
        return entity(id) instanceof ServerPlayer player ? player.getName().getString() : "";
    }

    @HostAccess.Export
    public int playerSelectedSlot(String id) {
        return entity(id) instanceof ServerPlayer player ? Platform.selectedSlot(player) : 0;
    }

    /** Bedrock capitalises its game mode names where Java does not, so `survival` reads `Survival`. */
    @HostAccess.Export
    public String playerGameMode(String id) {
        String name = entity(id) instanceof ServerPlayer player
            ? Platform.gameMode(player).getName()
            : GameType.SURVIVAL.getName();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @HostAccess.Export
    public void setPlayerGameMode(String id, String mode) {
        if (!(entity(id) instanceof ServerPlayer player) || mode == null) return;
        for (GameType type : GameType.values()) {
            if (type.getName().equalsIgnoreCase(mode)) {
                player.setGameMode(type);
                return;
            }
        }
        unsupported("Player.setGameMode(" + mode + ")");
    }

    @HostAccess.Export
    public void sendMessageTo(String id, String message) {
        if (entity(id) instanceof ServerPlayer player) player.sendSystemMessage(Component.literal(message));
    }

    @HostAccess.Export
    public void setActionBar(String id, String message) {
        if (entity(id) instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(message), true);
        }
    }

    @HostAccess.Export
    public void setTitle(String id, String message) {
        if (entity(id) instanceof ServerPlayer player) {
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(message)));
        }
    }

    @HostAccess.Export
    public void setSubtitle(String id, String message) {
        if (entity(id) instanceof ServerPlayer player) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(message)));
        }
    }

    // Dimensions and blocks

    @HostAccess.Export
    public int[] dimensionHeightRange(String dimension) {
        ServerLevel level = level(dimension);
        return level == null ? new int[] {0, 0} : Platform.heightRange(level);
    }

    @HostAccess.Export
    public String[] dimensionEntities(String dimension, String queryJson) {
        ServerLevel level = level(dimension);
        if (level == null) return new String[0];

        EntityQuery query = EntityQuery.parse(queryJson, this);
        List<String> found = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (query.matches(entity)) found.add(entity.getStringUUID());
        }
        return query.limit(found).toArray(String[]::new);
    }

    @HostAccess.Export
    public boolean isChunkLoaded(String dimension, double x, double z) {
        ServerLevel level = level(dimension);
        return level != null && level.hasChunkAt(BlockPos.containing(x, 0, z));
    }

    @HostAccess.Export
    public Integer topmostBlockY(String dimension, int x, int z) {
        ServerLevel level = level(dimension);
        return level == null ? null : level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    }

    @HostAccess.Export
    public String blockTypeId(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        if (level == null) return "minecraft:air";
        return AddonBlocks.identifierOf(level.getBlockState(new BlockPos(x, y, z)));
    }

    @HostAccess.Export
    public boolean blockIsLiquid(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        return level != null && !level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty();
    }

    @HostAccess.Export
    public String blockStates(String dimension, int x, int y, int z) {
        ServerLevel level = level(dimension);
        JsonObject states = new JsonObject();
        if (level == null) return states.toString();

        BlockState block = level.getBlockState(new BlockPos(x, y, z));
        for (Property<?> property : block.getProperties()) {
            states.addProperty(property.getName(), stateValue(block, property));
        }
        return states.toString();
    }

    @HostAccess.Export
    public void setBlockType(String dimension, int x, int y, int z, String type) {
        ServerLevel level = level(dimension);
        BlockState state = type == null ? null : AddonBlocks.stateOf(type, Map.of(), ignored -> { });
        if (level == null || state == null) return;
        level.setBlockAndUpdate(new BlockPos(x, y, z), state);
    }

    @HostAccess.Export
    public String spawnEntity(String dimension, String typeId, double x, double y, double z) {
        ServerLevel level = level(dimension);
        EntityType<?> type = typeId == null ? null : AddonEntities.typeOf(typeId);
        if (level == null || type == null) return null;

        Entity spawned = Platform.createMob(type, level);
        if (spawned == null) return null;
        Platform.placeAt(spawned, x, y, z, 0, 0);
        if (!level.addFreshEntity(spawned)) return null;

        remember(spawned);
        return spawned.getStringUUID();
    }

    @HostAccess.Export
    public String spawnItem(String dimension, String itemId, int amount, double x, double y, double z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        ItemEntity dropped = new ItemEntity(level, x, y, z, stack(itemId, amount));
        if (!level.addFreshEntity(dropped)) return null;

        remember(dropped);
        return dropped.getStringUUID();
    }

    @HostAccess.Export
    public void playSoundAt(String id, String sound, double volume, double pitch) {
        Entity entity = entity(id);
        if (entity == null) return;
        playSoundAtLocation(Platform.idOf(entity.level().dimension()).toString(), sound,
            entity.getX(), entity.getY(), entity.getZ(), volume, pitch);
    }

    /**
     * A sound only one player hears, played where that player is standing.
     *
     * Sent down that player's own connection rather than through the level, because a level plays a
     * sound to everybody in range of it.
     */
    @HostAccess.Export
    public void playSoundTo(String id, String sound, double volume, double pitch) {
        Identifier parsed = sound == null ? null : Identifier.tryParse(sound);
        if (parsed == null || !(entity(id) instanceof ServerPlayer player)) return;

        BuiltInRegistries.SOUND_EVENT.getOptional(parsed).ifPresentOrElse(
            event -> player.connection.send(new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event), SoundSource.NEUTRAL,
                player.getX(), player.getY(), player.getZ(), (float) volume, (float) pitch,
                player.getRandom().nextLong())),
            () -> coverage.missingSound(parsed.toString()));
    }

    @HostAccess.Export
    public void playSoundAtLocation(String dimension, String sound, double x, double y, double z,
        double volume, double pitch) {
        ServerLevel level = level(dimension);
        Identifier parsed = sound == null ? null : Identifier.tryParse(sound);
        if (level == null || parsed == null) return;

        BuiltInRegistries.SOUND_EVENT.getOptional(parsed).ifPresentOrElse(
            event -> level.playSound(null, x, y, z, event, SoundSource.NEUTRAL, (float) volume, (float) pitch),
            () -> coverage.missingSound(parsed.toString()));
    }

    @HostAccess.Export
    public int runCommandAs(String id, String command) {
        Entity entity = entity(id);
        if (entity == null) return 0;
        if (!(entity.level() instanceof ServerLevel level)) return 0;
        return runPacks(Platform.commandSourceOf(entity, level), command);
    }

    @HostAccess.Export
    public int runCommandIn(String dimension, String command) {
        ServerLevel level = level(dimension);
        return level == null ? 0 : runPacks(server.createCommandSourceStack().withLevel(level), command);
    }

    /** A line the pack's script wrote, which is in Bedrock's dialect. */
    private int runPacks(CommandSourceStack source, String command) {
        PackCommand line = PackCommand.tryFrom(namespace, command);
        return line == null ? 0 : line.run(source, coverage);
    }

    // Scoreboard. Reads go through the game's own objects; writes go through the command, whose
    // spelling has not changed across the supported versions where the score API has.

    @HostAccess.Export
    public String[] scoreboardObjectives() {
        return server.getScoreboard().getObjectiveNames().toArray(String[]::new);
    }

    @HostAccess.Export
    public boolean scoreboardHasObjective(String objective) {
        return server.getScoreboard().getObjective(objective) != null;
    }

    @HostAccess.Export
    public String scoreboardDisplayName(String objective) {
        Objective found = server.getScoreboard().getObjective(objective);
        return found == null ? null : found.getDisplayName().getString();
    }

    @HostAccess.Export
    public void addObjective(String objective, String displayName) {
        run(server.createCommandSourceStack(), "scoreboard objectives add " + objective + " dummy "
            + (displayName == null || displayName.isBlank() ? objective : displayName));
    }

    @HostAccess.Export
    public void removeObjective(String objective) {
        run(server.createCommandSourceStack(), "scoreboard objectives remove " + objective);
    }

    @HostAccess.Export
    public Integer score(String objective, String participant) {
        Objective found = server.getScoreboard().getObjective(objective);
        return found == null ? null : Platform.score(server.getScoreboard(), found, participant);
    }

    @HostAccess.Export
    public void setScore(String objective, String participant, int value) {
        run(server.createCommandSourceStack(),
            "scoreboard players set " + participant + " " + objective + " " + value);
    }

    @HostAccess.Export
    public void removeParticipant(String objective, String participant) {
        run(server.createCommandSourceStack(),
            "scoreboard players reset " + participant + " " + objective);
    }

    @HostAccess.Export
    public String[] scoreboardParticipants() {
        return Platform.scoreboardParticipants(server.getScoreboard());
    }

    /**
     * Places a saved structure, through the command rather than the template manager.
     *
     * `/place template` takes the same arguments on every supported version, where the manager's own
     * API does not, and a pack placing a structure is rare enough that parsing costs nothing.
     */
    @HostAccess.Export
    public boolean placeStructure(String dimension, String structure, int x, int y, int z,
        String rotation, String mirror) {
        ServerLevel level = level(dimension);
        if (level == null) return false;

        String placed = "place template " + McStructure.javaName(structure) + " " + x + " " + y + " " + z
            + " " + javaRotation(rotation) + " " + javaMirror(mirror);
        return run(server.createCommandSourceStack().withLevel(level), placed) > 0;
    }

    /**
     * Saves a region of the world as a structure a script can place again.
     *
     * The size is measured from the corner the pack gave, the way Bedrock measures it, and entities
     * are left out because Bedrock's own default leaves them out.
     */
    @HostAccess.Export
    public boolean saveStructure(String dimension, String structure, int x, int y, int z,
        int width, int height, int depth) {
        ServerLevel level = level(dimension);
        Identifier id = structureId(structure);
        if (level == null || id == null) return false;

        StructureTemplate template = server.getStructureManager().getOrCreate(id);
        Platform.fillFromWorld(template, level, new BlockPos(x, y, z),
            new net.minecraft.core.Vec3i(width, height, depth));
        return server.getStructureManager().save(id);
    }

    /** The id a structure name a script passes means, read the way Bedrock reads it. */
    private static Identifier structureId(String structure) {
        return structure == null ? null : Identifier.tryParse(McStructure.javaName(structure));
    }

    @HostAccess.Export
    public int[] structureSize(String structure) {
        Identifier id = structureId(structure);
        if (id == null) return new int[] {0, 0, 0};

        return server.getStructureManager().get(id)
            .map(template -> new int[] {template.getSize().getX(), template.getSize().getY(),
                template.getSize().getZ()})
            .orElse(new int[] {0, 0, 0});
    }

    /** Every structure the world holds, whether a data pack shipped it or a script saved it. */
    @HostAccess.Export
    public String[] structureIds() {
        return server.getStructureManager().listTemplates().map(Identifier::toString).toArray(String[]::new);
    }

    @HostAccess.Export
    public boolean structureExists(String structure) {
        Identifier id = structureId(structure);
        return id != null && server.getStructureManager().get(id).isPresent();
    }

    /** Writes a structure the world was holding in memory out to disk, under the id it has. */
    @HostAccess.Export
    public boolean saveStructureToWorld(String structure) {
        Identifier id = structureId(structure);
        return id != null && server.getStructureManager().save(id);
    }

    /** False when the id named nothing, which is the answer Bedrock gives for a delete that missed. */
    @HostAccess.Export
    public boolean deleteStructure(String structure) {
        if (!structureExists(structure)) return false;
        server.getStructureManager().remove(structureId(structure));
        return true;
    }

    /**
     * Where a scoreboard objective is shown: the sidebar, the player list, or under a name.
     *
     * Bedrock capitalised these names in its 2.0 break, so both spellings are read.
     */
    @HostAccess.Export
    public void setDisplaySlot(String slot, String objective) {
        String java = javaDisplaySlot(slot);
        if (java == null) {
            unsupported("Scoreboard display slot " + slot);
            return;
        }
        run(server.createCommandSourceStack(), objective == null || objective.isBlank()
            ? "scoreboard objectives setdisplay " + java
            : "scoreboard objectives setdisplay " + java + " " + objective);
    }

    /** The objective a slot currently shows, or null when the slot names nothing or is empty. */
    @HostAccess.Export
    public String objectiveAtDisplaySlot(String slot) {
        String java = javaDisplaySlot(slot);
        if (java == null) {
            unsupported("Scoreboard display slot " + slot);
            return null;
        }
        Objective shown = displayedObjective(java);
        return shown == null ? null : shown.getName();
    }

    /** A display slot became an enum of its own in 1.21; before that it was an index. */
    private Objective displayedObjective(String java) {
        //? if >=1.21 {
        net.minecraft.world.scores.DisplaySlot slot =
            net.minecraft.world.scores.DisplaySlot.CODEC.byName(java);
        return slot == null ? null : server.getScoreboard().getDisplayObjective(slot);
        //?} else {
        /*int slot = net.minecraft.world.scores.Scoreboard.getDisplaySlotByName(java);
        return slot < 0 ? null : server.getScoreboard().getDisplayObjective(slot);
        *///?}
    }

    private static String javaDisplaySlot(String slot) {
        return switch (slot == null ? "" : slot.toLowerCase(Locale.ROOT)) {
            case "list" -> "list";
            case "belowname" -> "below_name";
            case "sidebar" -> "sidebar";
            default -> null;
        };
    }

    private static String javaRotation(String bedrock) {
        return switch (bedrock == null ? "None" : bedrock) {
            case "Rotate90" -> "clockwise_90";
            case "Rotate180" -> "180";
            case "Rotate270" -> "counterclockwise_90";
            default -> "none";
        };
    }

    private static String javaMirror(String bedrock) {
        return switch (bedrock == null ? "None" : bedrock) {
            case "X" -> "front_back";
            case "Z" -> "left_right";
            default -> "none";
        };
    }

    /** How much wear an item of this kind takes before it breaks, or zero when it never does. */
    @HostAccess.Export
    public int itemMaxDurability(String itemId) {
        Item item = itemId == null ? null : AddonItems.named(itemId);
        return item == null ? 0 : new ItemStack(item).getMaxDamage();
    }

    /** The tags the item is in, under Java's names for them: a pack reading these sees `swords`. */
    @HostAccess.Export
    public String[] itemTags(String itemId) {
        Item item = itemId == null ? null : AddonItems.named(itemId);
        return item == null ? new String[0] : item.builtInRegistryHolder().tags()
            .map(tag -> tag.location().toString())
            .toArray(String[]::new);
    }

    /**
     * Asks one player's client to show a form.
     *
     * False when there is nobody to show it to, which is the same answer Bedrock gives when a player
     * is busy: the pack's promise resolves as cancelled rather than waiting forever.
     */
    @HostAccess.Export
    public boolean showForm(String playerId, int formId, String description) {
        if (!(entity(playerId) instanceof ServerPlayer player)) return false;
        openForms.put(formId, player.getStringUUID());
        Channels.sendForm(player, formId, description);
        return true;
    }

    @HostAccess.Export
    public void closeForm(String playerId) {
        if (entity(playerId) instanceof ServerPlayer player) Channels.sendForm(player, 0, "");
    }

    /** Whether a form is waiting on this player, who is the only one its answer may come from. */
    boolean shownTo(int formId, ServerPlayer player) {
        return player.getStringUUID().equals(openForms.get(formId));
    }

    /** Called once a form's promise has settled, after which nobody's answer to it is wanted. */
    @HostAccess.Export
    public void formSettled(int formId) {
        openForms.remove(formId);
    }

    // Helpers, not exported

    /**
     * The entity an id names, whether or not the level has indexed it yet.
     *
     * A level indexes an entity by uuid a tick after it is added, so an entity a script is told
     * about the moment it spawns cannot be found by uuid alone. The weak map covers that gap and
     * lets go of the entity as soon as the world does.
     */
    Entity entity(String id) {
        return entity(server, id);
    }

    /** The same lookup for the surface classes, which reach entities without going through here. */
    public static Entity entity(MinecraftServer server, String id) {
        UUID uuid = uuid(id);
        if (uuid == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = Platform.entityByUuid(level, uuid);
            if (found != null) return found;
        }

        java.lang.ref.WeakReference<Entity> pending = PENDING.get(uuid);
        Entity fresh = pending == null ? null : pending.get();
        return fresh == null || fresh.isRemoved() ? null : fresh;
    }

    private static final Map<UUID, java.lang.ref.WeakReference<Entity>> PENDING = new WeakHashMap<>();

    /** Remembers an entity a script is about to be told about, before the level has indexed it. */
    public static void remember(Entity entity) {
        PENDING.put(entity.getUUID(), new java.lang.ref.WeakReference<>(entity));
    }

    BlockPos worldSpawn() {
        //? if >=26 {
        return server.overworld().getRespawnData().globalPos().pos();
        //?} else if >=1.21 {
        /*return server.overworld().getLevelData().getSpawnPos();
        *///?} else {
        /*return new BlockPos(server.overworld().getLevelData().getXSpawn(),
            server.overworld().getLevelData().getYSpawn(),
            server.overworld().getLevelData().getZSpawn());
        *///?}
    }

    private ServerLevel level(String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (Platform.idOf(level.dimension()).toString().equals(dimension)) return level;
        }
        return null;
    }

    /** A line this bridge built in Java's dialect. */
    private int run(CommandSourceStack source, String command) {
        return PackCommand.java(command).run(source, coverage);
    }

    private ItemStack stack(String itemId, int amount) {
        Item item = itemId == null ? null : AddonItems.named(itemId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, Math.max(1, amount));
    }

    private static <T extends Comparable<T>> String stateValue(BlockState block, Property<T> property) {
        return property.getName(block.getValue(property));
    }

    /** Null for a slot this mod has no Java slot for, which is reported rather than guessed at. */
    private EquipmentSlot equipmentSlot(String slot) {
        EquipmentSlot found = switch (slot == null ? "" : slot.toLowerCase(Locale.ROOT)) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "mainhand" -> EquipmentSlot.MAINHAND;
            default -> null;
        };
        if (found == null) unsupported("EquipmentSlot." + slot);
        return found;
    }

    private static UUID uuid(String id) {
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Turns a guest value into the boolean, double or string a dynamic property is allowed to be. */
    private static Object plain(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asDouble();
        if (value.isString()) return value.asString();
        return value.toString();
    }

    // Reading an option bag a script passed as JSON. A key the pack left out is absent, not null.

    private static String text(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsString() : null;
    }

    private static Double number(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsDouble() : null;
    }

    private static Integer integer(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsInt() : null;
    }

    private static boolean flag(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() && options.get(key).getAsBoolean();
    }

    private static JsonObject object(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonObject() ? options.getAsJsonObject(key) : null;
    }

    private static List<JsonObject> objects(JsonObject options, String key) {
        if (!options.has(key) || !options.get(key).isJsonArray()) return List.of();
        return options.getAsJsonArray(key).asList().stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .toList();
    }

    private static Set<String> strings(JsonObject options, String key) {
        if (!options.has(key) || !options.get(key).isJsonArray()) return Set.of();
        Set<String> values = new java.util.LinkedHashSet<>();
        options.getAsJsonArray(key).forEach(element -> values.add(element.getAsString()));
        return values;
    }

    private static double[] point(JsonObject location) {
        return location == null ? null : new double[] {
            location.get("x").getAsDouble(), location.get("y").getAsDouble(), location.get("z").getAsDouble()};
    }

    /**
     * Bedrock type families come from the behaviour pack, so an add-on's own type answers with what
     * its definition declares. Nothing declares them for the game's types, which are keyed here by
     * the id Bedrock gives them.
     */
    private static final class EntityFamilies {
        private static final Map<String, String[]> VANILLA = Map.ofEntries(
            Map.entry("minecraft:player", new String[] {"player", "mob"}),
            Map.entry("minecraft:villager_v2", new String[] {"villager", "mob"}),
            Map.entry("minecraft:zombie_villager_v2",
                new String[] {"zombie_villager", "zombie", "undead", "monster", "mob"}),
            Map.entry("minecraft:zombie", new String[] {"zombie", "undead", "monster", "mob"}),
            Map.entry("minecraft:skeleton", new String[] {"skeleton", "undead", "monster", "mob"}),
            Map.entry("minecraft:creeper", new String[] {"creeper", "monster", "mob"}),
            Map.entry("minecraft:sheep", new String[] {"sheep", "animal", "mob"}),
            Map.entry("minecraft:cow", new String[] {"cow", "animal", "mob"}),
            Map.entry("minecraft:wandering_trader", new String[] {"wandering_trader", "mob"}),
            Map.entry("minecraft:iron_golem", new String[] {"irongolem", "mob"}),
            Map.entry("minecraft:boat", new String[] {"boat", "inanimate"}),
            Map.entry("minecraft:chest_boat", new String[] {"boat", "inanimate"}));

        private EntityFamilies() {
        }

        static String[] of(Entity entity) {
            ServerEntityDefinition own = AddonEntities.definitionOf(entity.getType());
            if (own != null) return own.families().toArray(String[]::new);
            return VANILLA.getOrDefault(AddonEntities.identifierOf(entity.getType()), new String[] {"mob"});
        }
    }

    /**
     * `EntityQueryOptions`, as one test per option the query carries.
     *
     * Every option the pack set becomes a test when the query is parsed, so an entity is measured
     * against what was asked and against nothing else.
     */
    private static final class EntityQuery {
        private final List<Predicate<Entity>> tests = new ArrayList<>();
        private final ScriptBridge bridge;
        private final double[] location;
        private final double[] volume;
        private final Integer closest;
        private final Integer farthest;

        static EntityQuery parse(String json, ScriptBridge bridge) {
            return new EntityQuery(com.google.gson.JsonParser.parseString(json).getAsJsonObject(), bridge);
        }

        private EntityQuery(JsonObject options, ScriptBridge bridge) {
            this.bridge = bridge;
            location = point(object(options, "location"));
            volume = point(object(options, "volume"));
            closest = integer(options, "closest");
            farthest = integer(options, "farthest");

            kinds(options);
            names(options);
            players(options);
            rotations(options);
            place(options);
            scores(options);
            properties(options);
        }

        boolean matches(Entity entity) {
            return tests.stream().allMatch(test -> test.test(entity));
        }

        /** What the entity is: its registered type, and the Bedrock families that type belongs to. */
        private void kinds(JsonObject options) {
            String type = text(options, "type");
            if (type != null) {
                String wanted = identifier(type);
                tests.add(entity -> typeId(entity).equals(wanted));
            }

            Set<String> excludeTypes = strings(options, "excludeTypes");
            if (!excludeTypes.isEmpty()) {
                Set<String> unwanted = new java.util.HashSet<>();
                excludeTypes.forEach(one -> unwanted.add(identifier(one)));
                tests.add(entity -> !unwanted.contains(typeId(entity)));
            }

            Set<String> families = strings(options, "families");
            if (!families.isEmpty()) {
                tests.add(entity -> Set.of(EntityFamilies.of(entity)).containsAll(families));
            }

            Set<String> excludeFamilies = strings(options, "excludeFamilies");
            if (!excludeFamilies.isEmpty()) {
                tests.add(entity -> java.util.Collections.disjoint(
                    Set.of(EntityFamilies.of(entity)), excludeFamilies));
            }
        }

        /** The name tag an entity shows, and the tags a pack put on it. */
        private void names(JsonObject options) {
            String name = text(options, "name");
            if (name != null) tests.add(entity -> name.equals(nameOf(entity)));

            Set<String> excludeNames = strings(options, "excludeNames");
            if (!excludeNames.isEmpty()) tests.add(entity -> !excludeNames.contains(nameOf(entity)));

            Set<String> tags = strings(options, "tags");
            if (!tags.isEmpty()) tests.add(entity -> tags.stream().allMatch(tag -> tagged(entity, tag)));

            Set<String> excludeTags = strings(options, "excludeTags");
            if (!excludeTags.isEmpty()) {
                tests.add(entity -> excludeTags.stream().noneMatch(tag -> tagged(entity, tag)));
            }
        }

        /** An empty tag asks whether the entity carries any tag at all, the way a selector does. */
        private static boolean tagged(Entity entity, String tag) {
            Set<String> present = Platform.tagsOf(entity);
            return tag.isEmpty() ? !present.isEmpty() : present.contains(tag);
        }

        /** Options only a player can answer, so anything else is out once one of them is set. */
        private void players(JsonObject options) {
            String gameMode = text(options, "gameMode");
            if (gameMode != null) tests.add(entity -> gameMode.equalsIgnoreCase(modeOf(entity)));

            Set<String> excludeGameModes = strings(options, "excludeGameModes");
            if (!excludeGameModes.isEmpty()) {
                tests.add(entity -> excludeGameModes.stream().noneMatch(mode -> mode.equalsIgnoreCase(modeOf(entity))));
            }

            Integer minLevel = integer(options, "minLevel");
            if (minLevel != null) tests.add(entity -> experience(entity) != null && experience(entity) >= minLevel);

            Integer maxLevel = integer(options, "maxLevel");
            if (maxLevel != null) tests.add(entity -> experience(entity) != null && experience(entity) <= maxLevel);
        }

        private static String modeOf(Entity entity) {
            return entity instanceof ServerPlayer player ? Platform.gameMode(player).getName() : null;
        }

        private static Integer experience(Entity entity) {
            return entity instanceof ServerPlayer player ? player.experienceLevel : null;
        }

        /** Where the entity is looking. Bedrock measures the horizontal one from -180 to 180. */
        private void rotations(JsonObject options) {
            Double minVertical = number(options, "minVerticalRotation");
            if (minVertical != null) tests.add(entity -> entity.getXRot() >= minVertical);

            Double maxVertical = number(options, "maxVerticalRotation");
            if (maxVertical != null) tests.add(entity -> entity.getXRot() <= maxVertical);

            Double minHorizontal = number(options, "minHorizontalRotation");
            if (minHorizontal != null) tests.add(entity -> Mth.wrapDegrees(entity.getYRot()) >= minHorizontal);

            Double maxHorizontal = number(options, "maxHorizontalRotation");
            if (maxHorizontal != null) tests.add(entity -> Mth.wrapDegrees(entity.getYRot()) <= maxHorizontal);
        }

        /** Distance from the query's own point, and the cuboid it may name instead. */
        private void place(JsonObject options) {
            if (location == null) return;
            Vec3 origin = new Vec3(location[0], location[1], location[2]);

            Double maxDistance = number(options, "maxDistance");
            if (maxDistance != null) tests.add(entity -> entity.position().distanceTo(origin) <= maxDistance);

            Double minDistance = number(options, "minDistance");
            if (minDistance != null) tests.add(entity -> entity.position().distanceTo(origin) >= minDistance);

            if (volume == null) return;
            AABB box = new AABB(origin.x, origin.y, origin.z,
                origin.x + volume[0], origin.y + volume[1], origin.z + volume[2]);
            tests.add(entity -> box.contains(entity.position()));
        }

        /** A scoreboard range. An entity with no score on the objective is outside every range. */
        private void scores(JsonObject options) {
            for (JsonObject option : objects(options, "scoreOptions")) {
                String objective = text(option, "objective");
                if (objective == null) continue;

                Double minScore = number(option, "minScore");
                Double maxScore = number(option, "maxScore");
                boolean exclude = flag(option, "exclude");
                tests.add(entity -> exclude != inRange(bridge.score(objective, entity.getScoreboardName()),
                    minScore, maxScore));
            }
        }

        private static boolean inRange(Integer score, Double minimum, Double maximum) {
            return score != null && (minimum == null || score >= minimum)
                && (maximum == null || score <= maximum);
        }

        /** An entity property a pack declared, read straight from the store the script writes to. */
        private void properties(JsonObject options) {
            for (JsonObject option : objects(options, "propertyOptions")) {
                String property = text(option, "propertyId");
                if (property == null) continue;

                JsonElement wanted = option.get("value");
                boolean exclude = flag(option, "exclude");
                tests.add(entity -> exclude
                    != compare(bridge.state.property(entity.getUUID(), property), wanted));
            }
        }

        /** Bedrock compares a property either against a bare value or through a named operator. */
        private static boolean compare(Object actual, JsonElement wanted) {
            if (wanted == null || wanted.isJsonNull()) return actual != null;
            if (wanted.isJsonPrimitive()) return equalTo(actual, wanted.getAsJsonPrimitive());

            JsonObject operator = wanted.getAsJsonObject();
            if (operator.has("equals")) return equalTo(actual, operator.getAsJsonPrimitive("equals"));
            if (operator.has("notEquals")) return !equalTo(actual, operator.getAsJsonPrimitive("notEquals"));
            if (!(actual instanceof Number number)) return false;

            double value = number.doubleValue();
            if (operator.has("greaterThan")) return value > operator.get("greaterThan").getAsDouble();
            if (operator.has("greaterThanOrEquals")) {
                return value >= operator.get("greaterThanOrEquals").getAsDouble();
            }
            if (operator.has("lessThan")) return value < operator.get("lessThan").getAsDouble();
            if (operator.has("lessThanOrEquals")) return value <= operator.get("lessThanOrEquals").getAsDouble();
            return operator.has("lowerBound") && operator.has("upperBound")
                && value >= operator.get("lowerBound").getAsDouble()
                && value <= operator.get("upperBound").getAsDouble();
        }

        private static boolean equalTo(Object actual, JsonPrimitive wanted) {
            if (actual == null) return false;
            if (wanted.isBoolean()) return Boolean.valueOf(wanted.getAsBoolean()).equals(actual);
            if (wanted.isNumber() && actual instanceof Number number) {
                return number.doubleValue() == wanted.getAsDouble();
            }
            return actual.toString().equals(wanted.getAsString());
        }

        /** `closest: n` and `farthest: n` keep the n nearest or furthest from the query's own point. */
        List<String> limit(List<String> found) {
            Integer count = closest != null ? closest : farthest;
            if (count == null || location == null || found.size() <= count) return found;

            Vec3 origin = new Vec3(location[0], location[1], location[2]);
            Comparator<String> byDistance = Comparator.comparingDouble(id -> {
                Entity entity = bridge.entity(id);
                return entity == null ? Double.MAX_VALUE : entity.position().distanceToSqr(origin);
            });
            return found.stream()
                .sorted(closest != null ? byDistance : byDistance.reversed())
                .limit(count)
                .toList();
        }

        private static String typeId(Entity entity) {
            return AddonEntities.identifierOf(entity.getType());
        }

        private static String identifier(String type) {
            return AddonEntities.identifierOf(qualify(type));
        }

        private static String nameOf(Entity entity) {
            return entity.getCustomName() == null ? "" : entity.getCustomName().getString();
        }

        private static String qualify(String type) {
            return type.contains(":") ? type : "minecraft:" + type;
        }
    }
}
