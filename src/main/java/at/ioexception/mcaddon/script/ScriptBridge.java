package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private long tickDeadline;

    ScriptBridge(MinecraftServer server, AddonState state, ScriptCoverage coverage) {
        this.server = server;
        this.state = state;
        this.coverage = coverage;
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
        unsupported("World.getMoonPhase");
        return 0;
    }

    @HostAccess.Export
    public double[] defaultSpawnLocation() {
        BlockPos spawn = worldSpawn();
        return new double[] {spawn.getX(), spawn.getY(), spawn.getZ()};
    }

    @HostAccess.Export
    public String gameRule(String name) {
        unsupported("World.gameRules." + name);
        return null;
    }

    @HostAccess.Export
    public void setGameRule(String name, Value value) {
        unsupported("World.gameRules." + name + " (write)");
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

    @HostAccess.Export
    public boolean entityIsPlayer(String id) {
        return entity(id) instanceof ServerPlayer;
    }

    @HostAccess.Export
    public String entityTypeId(String id) {
        Entity entity = entity(id);
        return entity == null ? null : EntityType.getKey(entity.getType()).toString();
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

    @HostAccess.Export
    public void teleport(String id, double x, double y, double z, String dimension, Value pitch, Value yaw) {
        Entity entity = entity(id);
        if (entity == null) return;
        if (dimension != null && !dimension.equals(Platform.idOf(entity.level().dimension()).toString())) {
            unsupported("Entity.teleport across dimensions");
        }
        Platform.placeAt(entity, x, y, z,
            yaw.isNumber() ? (float) yaw.asDouble() : entity.getYRot(),
            pitch.isNumber() ? (float) pitch.asDouble() : entity.getXRot());
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
        return EntityFamilies.of(entityTypeId(id));
    }

    @HostAccess.Export
    public boolean entityMatches(String id, String queryJson) {
        Entity entity = entity(id);
        return entity != null && EntityQuery.parse(queryJson).matches(entity, this);
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
            case "minecraft:health", "minecraft:equippable" -> entity instanceof LivingEntity;
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
            "minecraft:rideable", "minecraft:type_family", "minecraft:is_baby", "minecraft:item")) {
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

    @HostAccess.Export
    public boolean applyDamage(String id, double amount, String cause, String sourceId) {
        Entity entity = entity(id);
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return false;
        Platform.hurt(entity, level, level.damageSources().generic(), (float) amount);
        return true;
    }

    @HostAccess.Export
    public boolean addEffect(String id, String effect, int duration, int amplifier, boolean particles) {
        Identifier parsed = Identifier.tryParse(effect);
        return parsed != null && entity(id) instanceof LivingEntity living
            && Platform.addEffect(living, parsed, duration, amplifier, particles);
    }

    @HostAccess.Export
    public boolean removeEffect(String id, String effect) {
        Identifier parsed = Identifier.tryParse(effect);
        return parsed != null && entity(id) instanceof LivingEntity living
            && Platform.removeEffect(living, parsed);
    }

    @HostAccess.Export
    public String equipment(String id, String slot) {
        if (!(entity(id) instanceof LivingEntity living)) return null;
        ItemStack held = living.getItemBySlot(equipmentSlot(slot));
        if (held.isEmpty()) return null;
        JsonObject item = new JsonObject();
        item.addProperty("typeId", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        item.addProperty("amount", held.getCount());
        return item.toString();
    }

    @HostAccess.Export
    public boolean setEquipment(String id, String slot, String itemId, int amount) {
        if (!(entity(id) instanceof LivingEntity living)) return false;
        living.setItemSlot(equipmentSlot(slot), itemId == null ? ItemStack.EMPTY : stack(itemId, amount));
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

    @HostAccess.Export
    public String playerGameMode(String id) {
        return entity(id) instanceof ServerPlayer player
            ? Platform.gameMode(player).getName()
            : GameType.SURVIVAL.getName();
    }

    @HostAccess.Export
    public void setPlayerGameMode(String id, String mode) {
        if (!(entity(id) instanceof ServerPlayer player)) return;
        for (GameType type : GameType.values()) {
            if (type.getName().equals(mode)) {
                player.setGameMode(type);
                return;
            }
        }
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
        unsupported("Player.onScreenDisplay.setTitle");
    }

    @HostAccess.Export
    public void setSubtitle(String id, String message) {
        unsupported("Player.onScreenDisplay.updateSubtitle");
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

        EntityQuery query = EntityQuery.parse(queryJson);
        List<String> found = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (query.matches(entity, this)) found.add(entity.getStringUUID());
        }
        return query.limit(found, level).toArray(String[]::new);
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
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock()).toString();
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
        Identifier parsed = type == null ? null : Identifier.tryParse(type);
        if (level == null || parsed == null) return;
        BuiltInRegistries.BLOCK.getOptional(parsed)
            .ifPresent(block -> level.setBlockAndUpdate(new BlockPos(x, y, z), block.defaultBlockState()));
    }

    @HostAccess.Export
    public String spawnEntity(String dimension, String typeId, double x, double y, double z) {
        ServerLevel level = level(dimension);
        Identifier parsed = Identifier.tryParse(typeId);
        if (level == null || parsed == null) return null;

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(parsed).orElse(null);
        if (type == null) return null;

        Entity spawned = Platform.createMob(type, level);
        if (spawned == null) return null;
        Platform.placeAt(spawned, x, y, z, 0, 0);
        return level.addFreshEntity(spawned) ? spawned.getStringUUID() : null;
    }

    @HostAccess.Export
    public String spawnItem(String dimension, String itemId, int amount, double x, double y, double z) {
        ServerLevel level = level(dimension);
        if (level == null) return null;

        ItemEntity dropped = new ItemEntity(level, x, y, z, stack(itemId, amount));
        return level.addFreshEntity(dropped) ? dropped.getStringUUID() : null;
    }

    @HostAccess.Export
    public void playSoundAt(String id, String sound, double volume, double pitch) {
        Entity entity = entity(id);
        if (entity == null) return;
        playSoundAtLocation(Platform.idOf(entity.level().dimension()).toString(), sound,
            entity.getX(), entity.getY(), entity.getZ(), volume, pitch);
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
        return run(Platform.commandSourceOf(entity, level), command);
    }

    @HostAccess.Export
    public int runCommandIn(String dimension, String command) {
        ServerLevel level = level(dimension);
        return level == null ? 0 : run(server.createCommandSourceStack().withLevel(level), command);
    }

    @HostAccess.Export
    public String[] itemTags(String itemId) {
        return new String[0];
    }

    // Helpers, not exported

    Entity entity(String id) {
        UUID uuid = uuid(id);
        if (uuid == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = Platform.entityByUuid(level, uuid);
            if (found != null) return found;
        }
        return null;
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

    /**
     * Brigadier's dispatcher rather than `performPrefixedCommand`, whose return type changed twice
     * across the supported versions while the dispatcher's has not.
     */
    private int run(CommandSourceStack source, String command) {
        String trimmed = command.startsWith("/") ? command.substring(1) : command;
        try {
            return server.getCommands().getDispatcher().execute(trimmed, source.withSuppressedOutput());
        } catch (CommandSyntaxException error) {
            coverage.failedCommand(trimmed, error.getMessage());
            return 0;
        }
    }

    private ItemStack stack(String itemId, int amount) {
        Identifier parsed = itemId == null ? null : Identifier.tryParse(itemId);
        Item item = parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, Math.max(1, amount));
    }

    private static <T extends Comparable<T>> String stateValue(BlockState block, Property<T> property) {
        return property.getName(block.getValue(property));
    }

    private static EquipmentSlot equipmentSlot(String slot) {
        return switch (slot.toLowerCase(Locale.ROOT)) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            case "offhand" -> EquipmentSlot.OFFHAND;
            default -> EquipmentSlot.MAINHAND;
        };
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

    /** Bedrock type families come from the behaviour pack; nothing declares them for vanilla types. */
    private static final class EntityFamilies {
        private static final Map<String, String[]> VANILLA = Map.of(
            "minecraft:player", new String[] {"player", "mob"},
            "minecraft:villager", new String[] {"villager", "mob"},
            "minecraft:zombie", new String[] {"zombie", "undead", "monster", "mob"},
            "minecraft:skeleton", new String[] {"skeleton", "undead", "monster", "mob"},
            "minecraft:creeper", new String[] {"creeper", "monster", "mob"},
            "minecraft:sheep", new String[] {"sheep", "animal", "mob"},
            "minecraft:cow", new String[] {"cow", "animal", "mob"},
            "minecraft:wandering_trader", new String[] {"wandering_trader", "mob"},
            "minecraft:iron_golem", new String[] {"irongolem", "mob"});

        private EntityFamilies() {
        }

        static String[] of(String typeId) {
            return VANILLA.getOrDefault(typeId, new String[] {"mob"});
        }
    }

    /** `EntityQueryOptions`, as far as the keys real packs actually use. */
    private record EntityQuery(String type, Set<String> excludeTypes, Set<String> tags, Set<String> families,
        String name, double[] location, Double maxDistance, Double minDistance, boolean closest, int count) {

        static EntityQuery parse(String json) {
            JsonObject options = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            return new EntityQuery(
                text(options, "type"),
                strings(options, "excludeTypes"),
                strings(options, "tags"),
                strings(options, "families"),
                text(options, "name"),
                point(options.getAsJsonObject("location")),
                number(options, "maxDistance"),
                number(options, "minDistance"),
                options.has("closest"),
                options.has("closest") ? options.get("closest").getAsInt() : 0);
        }

        boolean matches(Entity entity, ScriptBridge bridge) {
            String typeId = EntityType.getKey(entity.getType()).toString();
            if (type != null && !typeId.equals(qualify(type))) return false;
            if (excludeTypes.stream().anyMatch(excluded -> typeId.equals(qualify(excluded)))) return false;
            if (name != null && !name.equals(entity.getCustomName() == null
                ? "" : entity.getCustomName().getString())) {
                return false;
            }
            if (!tags.isEmpty() && !Platform.tagsOf(entity).containsAll(tags)) return false;
            if (!families.isEmpty()) {
                Set<String> present = Set.of(EntityFamilies.of(typeId));
                if (families.stream().noneMatch(present::contains)) return false;
            }
            if (location != null) {
                double distance = entity.position().distanceTo(new Vec3(location[0], location[1], location[2]));
                if (maxDistance != null && distance > maxDistance) return false;
                if (minDistance != null && distance < minDistance) return false;
            }
            return true;
        }

        /** `closest: n` keeps the n nearest to the query location, which is how packs find neighbours. */
        List<String> limit(List<String> found, ServerLevel level) {
            if (!closest || location == null || found.size() <= count) return found;
            Vec3 origin = new Vec3(location[0], location[1], location[2]);
            return found.stream()
                .sorted(java.util.Comparator.comparingDouble(id -> {
                    Entity entity = Platform.entityByUuid(level, UUID.fromString(id));
                    return entity == null ? Double.MAX_VALUE : entity.position().distanceToSqr(origin);
                }))
                .limit(count)
                .toList();
        }

        private static String qualify(String type) {
            return type.contains(":") ? type : "minecraft:" + type;
        }

        private static String text(JsonObject options, String key) {
            return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsString() : null;
        }

        private static Double number(JsonObject options, String key) {
            return options.has(key) && options.get(key).isJsonPrimitive() ? options.get(key).getAsDouble() : null;
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
    }
}
