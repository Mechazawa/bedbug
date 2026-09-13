package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.mixin.JumpingAccessor;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptHost;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a player does, told the way a pack's script asks about it.
 *
 * Each event carries the fields Bedrock's event of the same name carries, so a pack's handler finds
 * what it expects. Where Bedrock has a before event, it is raised first and a listener that sets
 * `cancel` stops the game from carrying the action out.
 *
 * Only the main hand raises anything. Bedrock has no off hand, so a pack that saw both would count
 * one click twice.
 */
public final class WorldEvents {
    /** What the player held when the break started, which the tool may not survive. */
    private static final Map<UUID, ItemStack> BREAKING_WITH = new HashMap<>();

    /** Clicks that may have placed a block. The block only exists once the tick has run. */
    private static final List<Placement> PLACEMENTS = new ArrayList<>();

    private static final Map<UUID, PlayerWatch> WATCHED = new HashMap<>();

    /** The weather each dimension carried at the end of the last tick. */
    private static final Map<ResourceKey<Level>, String> WEATHER = new HashMap<>();

    /** How long Bedrock says a weather change lasts when nothing asked for a length. */
    private static final int WEATHER_TICKS = 6000;

    private WorldEvents() {
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, blockEntity) ->
            !breakRefused(level, player, position));
        PlayerBlockBreakEvents.AFTER.register((level, player, position, state, blockEntity) ->
            broke(level, player, position, state));
        UseBlockCallback.EVENT.register(WorldEvents::usedBlock);
        UseEntityCallback.EVENT.register(WorldEvents::usedEntity);
        AttackEntityCallback.EVENT.register(WorldEvents::attacked);
        AttackBlockCallback.EVENT.register(WorldEvents::attackedBlock);
        // Fabric's use-item callback answers with the stack it leaves behind; from 26 on it answers
        // with the outcome alone.
        //? if >=26 {
        UseItemCallback.EVENT.register((player, level, hand) ->
            usedItem(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS);
        //?} else {
        /*UseItemCallback.EVENT.register((player, level, hand) -> usedItem(player, hand)
            ? net.minecraft.world.InteractionResultHolder.fail(player.getItemInHand(hand))
            : net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand)));
        *///?}
    }

    /** Run once the tick's work is done, which is when a block a click placed is in the world. */
    public static void endTick(MinecraftServer server) {
        confirmPlacements(server);
        pollPlayers(server);
        pollWeather(server);
        BlockEvents.endTick(server);
    }

    /**
     * The weather turning, found by comparing each dimension against the tick before.
     *
     * Java raises nothing when the weather turns, and it turns for four reasons: the cycle running
     * out, a command, a sleep and a trident. Reading the state itself catches every one of them.
     *
     * The before event is raised on the tick the change is already in, and a listener refusing it is
     * honoured by putting the old weather back, so what a player sees is weather that did not change.
     */
    private static void pollWeather(MinecraftServer server) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) {
            WEATHER.clear();
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            String now = weatherOf(level);
            String before = WEATHER.put(level.dimension(), now);
            if (before == null || before.equals(now)) continue;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("dimension", Platform.idOf(level.dimension()).toString());
            fields.put("previousWeather", before);
            fields.put("newWeather", now);
            fields.put("duration", WEATHER_TICKS);

            if (scripts.raiseCancellable("weatherChange", fields)) {
                Platform.setWeather(level, !"Clear".equals(before), "Thunder".equals(before),
                    WEATHER_TICKS);
                WEATHER.put(level.dimension(), before);
                continue;
            }
            scripts.raiseWorldEvent("weatherChange", fields);
        }
    }

    private static String weatherOf(ServerLevel level) {
        if (level.isThundering()) return "Thunder";
        return level.isRaining() ? "Rain" : "Clear";
    }

    /** An item entity coming into the world with an owner is what somebody threw. */
    public static void spawned(Entity entity) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(entity instanceof ItemEntity item) || item.getOwner() == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ScriptHost.EntityHandle.of(item.getOwner()));
        fields.put("item", ScriptHost.EntityHandle.of(item));
        scripts.raiseWorldEvent("entityItemDrop", fields);
    }

    /**
     * An effect being applied, which a pack may refuse before it lands.
     *
     * Fabric raises this before the effect is added, so the before event is the one with something to
     * say and the after event follows once it is on.
     */
    public static boolean effectRefused(net.minecraft.world.entity.LivingEntity entity,
        net.minecraft.world.effect.MobEffectInstance effect) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return false;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ScriptHost.EntityHandle.of(entity));
        fields.put("effect", describe(effect));
        return scripts.raiseCancellable("effectAdd", fields);
    }

    public static void effectAdded(net.minecraft.world.entity.LivingEntity entity,
        net.minecraft.world.effect.MobEffectInstance effect) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ScriptHost.EntityHandle.of(entity));
        fields.put("effect", describe(effect));
        scripts.raiseWorldEvent("effectAdd", fields);
    }

    private static Map<String, Object> describe(net.minecraft.world.effect.MobEffectInstance effect) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("typeId", Platform.effectId(effect));
        fields.put("duration", effect.getDuration());
        fields.put("amplifier", effect.getAmplifier());
        return fields;
    }

    /**
     * Somebody walking over a dropped item and taking it, which a pack may refuse.
     *
     * Answering true leaves the item on the ground, so the before event runs first and the after
     * event only follows once the pickup is going to happen.
     */
    public static boolean pickupRefused(Player player, ItemEntity item) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(player instanceof ServerPlayer)) return false;

        if (scripts.raiseCancellable("entityItemPickup", pickup(player, item))) return true;

        scripts.raiseWorldEvent("entityItemPickup", pickup(player, item));
        return false;
    }

    private static Map<String, Object> pickup(Player player, ItemEntity item) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ScriptHost.EntityHandle.of(player));
        fields.put("itemStack", item(item.getItem()));
        return fields;
    }

    /**
     * An arrow or a thrown thing arriving somewhere.
     *
     * Bedrock hands the pack the projectile, whoever released it, and what it reached, as a block or
     * an entity depending on which event it is.
     */
    public static void projectileHit(Entity projectile, Entity shooter,
        net.minecraft.world.phys.HitResult hit) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("projectile", ScriptHost.EntityHandle.of(projectile));
        fields.put("dimension", Platform.idOf(projectile.level().dimension()).toString());
        if (shooter != null) fields.put("source", ScriptHost.EntityHandle.of(shooter));

        Map<String, Object> location = new LinkedHashMap<>();
        location.put("x", hit.getLocation().x);
        location.put("y", hit.getLocation().y);
        location.put("z", hit.getLocation().z);
        fields.put("location", location);

        if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
            fields.put("hitEntity",
                ScriptHost.EntityHandle.of(entityHit.getEntity()));
            scripts.raiseWorldEvent("projectileHitEntity", fields);
            return;
        }
        if (hit instanceof BlockHitResult blockHit) {
            fields.put("hitBlock", block(projectile.level(), blockHit.getBlockPos()));
            fields.put("blockFace", blockHit.getDirection().getName());
            scripts.raiseWorldEvent("projectileHitBlock", fields);
        }
    }

    /** A button going down, which Bedrock reports with whoever pushed it. */
    public static void buttonPushed(Level level, BlockPos position, Player player) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("block", block(level, position));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        if (player != null) fields.put("source", ScriptHost.EntityHandle.of(player));
        scripts.raiseWorldEvent("buttonPush", fields);
    }

    /** A lever going over, reported with the side it lands on and whoever put it there. */
    public static void leverPulled(Level level, BlockPos position, Player player) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("block", block(level, position));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("isPowered", !level.getBlockState(position).getValue(LeverBlock.POWERED));
        if (player != null) fields.put("player", ScriptHost.EntityHandle.of(player));
        scripts.raiseWorldEvent("leverAction", fields);
    }

    /** Somebody swinging at a block, which is the punch rather than the break that may follow. */
    private static InteractionResult attackedBlock(Player player, Level level, InteractionHand hand,
        BlockPos position, Direction face) {

        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(player instanceof ServerPlayer) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("damagingEntity", ScriptHost.EntityHandle.of(player));
        fields.put("hitBlock", block(level, position));
        fields.put("hitBlockPermutation", permutation(level.getBlockState(position)));
        fields.put("blockFace", capitalised(face.getName()));
        scripts.raiseWorldEvent("entityHitBlock", fields);
        return InteractionResult.PASS;
    }

    /**
     * Healing, which a pack may refuse before it lands.
     *
     * Java says how much and to whom, and nothing about why, so every heal carries Bedrock's plain
     * cause. Naming regeneration or a potion here would be a guess.
     */
    public static boolean healRefused(net.minecraft.world.entity.LivingEntity entity, float amount) {
        ScriptHost scripts = ServerHooks.scripts();
        return scripts != null && scripts.raiseCancellable("entityHeal", heal(entity, amount));
    }

    public static void healed(net.minecraft.world.entity.LivingEntity entity, float amount) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts != null) scripts.raiseWorldEvent("entityHeal", heal(entity, amount));
    }

    private static Map<String, Object> heal(net.minecraft.world.entity.LivingEntity entity, float amount) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("healedEntity", ScriptHost.EntityHandle.of(entity));
        fields.put("healing", amount);
        fields.put("healSource", Map.of("cause", "Heal"));
        return fields;
    }

    /** Health moving for any reason at all, which is what Bedrock reports here. */
    public static void healthChanged(net.minecraft.world.entity.LivingEntity entity, float before) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || before == entity.getHealth()) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ScriptHost.EntityHandle.of(entity));
        fields.put("oldValue", before);
        fields.put("newValue", entity.getHealth());
        scripts.raiseWorldEvent("entityHealthChanged", fields);
    }

    public static void forget() {
        BREAKING_WITH.clear();
        PLACEMENTS.clear();
        WATCHED.clear();
        WEATHER.clear();
        BlockEvents.forget();
    }

    private static boolean breakRefused(Level level, Player player, BlockPos position) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(player instanceof ServerPlayer)) return false;

        ItemStack held = player.getMainHandItem();
        BREAKING_WITH.put(player.getUUID(), held.copy());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("block", block(level, position));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("itemStack", item(held));
        return scripts.raiseCancellable("playerBreakBlock", fields);
    }

    private static void broke(Level level, Player player, BlockPos position, BlockState state) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(player instanceof ServerPlayer)) return;

        ItemStack before = BREAKING_WITH.remove(player.getUUID());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("block", block(level, position));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("brokenBlockPermutation", permutation(state));
        fields.put("itemStackBeforeBreak", before == null ? null : item(before));
        fields.put("itemStackAfterBreak", item(player.getMainHandItem()));
        scripts.raiseWorldEvent("playerBreakBlock", fields);
    }

    private static InteractionResult usedBlock(Player player, Level level, InteractionHand hand,
        BlockHitResult hit) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(player instanceof ServerPlayer) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("block", block(level, hit.getBlockPos()));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("blockFace", capitalised(hit.getDirection().getName()));
        fields.put("faceLocation", faceLocation(hit));
        fields.put("isFirstEvent", true);
        fields.put("itemStack", item(held));
        fields.put("beforeItemStack", item(held));
        if (scripts.raiseCancellable("playerInteractWithBlock", fields)) return InteractionResult.FAIL;
        scripts.raiseWorldEvent("playerInteractWithBlock", fields);

        if (!held.isEmpty()) {
            Map<String, Object> started = new LinkedHashMap<>();
            started.put("source", ScriptHost.EntityHandle.of(player));
            started.put("block", block(level, hit.getBlockPos()));
            started.put("blockFace", capitalised(hit.getDirection().getName()));
            started.put("itemStack", item(held));
            scripts.raiseWorldEvent("itemStartUseOn", started);
        }
        rememberPlacement(player, level, hit, held);
        return InteractionResult.PASS;
    }

    private static InteractionResult usedEntity(Player player, Level level, InteractionHand hand,
        Entity target, EntityHitResult hit) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(player instanceof ServerPlayer) || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("target", ScriptHost.EntityHandle.of(target));
        fields.put("itemStack", item(held));
        fields.put("beforeItemStack", item(held));
        if (scripts.raiseCancellable("playerInteractWithEntity", fields)) return InteractionResult.FAIL;
        scripts.raiseWorldEvent("playerInteractWithEntity", fields);
        return InteractionResult.PASS;
    }

    private static InteractionResult attacked(Player player, Level level, InteractionHand hand,
        Entity target, EntityHitResult hit) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(player instanceof ServerPlayer)) return InteractionResult.PASS;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("damagingEntity", ScriptHost.EntityHandle.of(player));
        fields.put("hitEntity", ScriptHost.EntityHandle.of(target));
        scripts.raiseWorldEvent("entityHitEntity", fields);
        return InteractionResult.PASS;
    }

    private static boolean usedItem(Player player, InteractionHand hand) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !(player instanceof ServerPlayer) || hand != InteractionHand.MAIN_HAND) {
            return false;
        }

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return false;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("source", ScriptHost.EntityHandle.of(player));
        fields.put("itemStack", item(held));
        if (scripts.raiseCancellable("itemUse", fields)) return true;

        scripts.raiseWorldEvent("itemUse", fields);
        return false;
    }

    /**
     * Remembers a click that may place a block.
     *
     * Fabric has no placement callback, so a placement is recognised by the block arriving in one of
     * the two spaces a click can fill. What each space already held is kept alongside, because
     * holding stone and clicking stone would otherwise read as a placement that never happened.
     */
    private static void rememberPlacement(Player player, Level level, BlockHitResult hit, ItemStack held) {
        if (!(held.getItem() instanceof BlockItem carried) || !(level instanceof ServerLevel server)) return;

        BlockPos clicked = hit.getBlockPos();
        BlockPos beyond = clicked.relative(hit.getDirection());
        PLACEMENTS.add(new Placement(player.getUUID(), server.dimension(), clicked, hit.getDirection(),
            carried.getBlock(), server.getBlockState(clicked).getBlock() == carried.getBlock(),
            server.getBlockState(beyond).getBlock() == carried.getBlock()));
    }

    private static void confirmPlacements(MinecraftServer server) {
        if (PLACEMENTS.isEmpty()) return;

        List<Placement> clicks = List.copyOf(PLACEMENTS);
        PLACEMENTS.clear();
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        for (Placement click : clicks) {
            ServerLevel level = server.getLevel(click.dimension());
            ServerPlayer player = server.getPlayerList().getPlayer(click.player());
            BlockPos at = level == null ? null : placedAt(level, click);
            if (at == null || player == null) continue;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("player", ScriptHost.EntityHandle.of(player));
            fields.put("block", block(level, at));
            fields.put("dimension", Platform.idOf(level.dimension()).toString());
            fields.put("blockFace", capitalised(click.face().getName()));
            scripts.raiseWorldEvent("playerPlaceBlock", fields);
        }
    }

    /** The space beyond the clicked face, or the clicked space itself when what was there gave way. */
    private static BlockPos placedAt(ServerLevel level, Placement click) {
        BlockPos beyond = click.clicked().relative(click.face());
        if (!click.beyondHeld() && level.getBlockState(beyond).getBlock() == click.placing()) return beyond;
        if (!click.clickedHeld() && level.getBlockState(click.clicked()).getBlock() == click.placing()) {
            return click.clicked();
        }
        return null;
    }

    private static void pollPlayers(MinecraftServer server) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) {
            WATCHED.clear();
            return;
        }

        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            PlayerWatch watch = WATCHED.get(player.getUUID());
            if (watch == null) {
                WATCHED.put(player.getUUID(), new PlayerWatch(player));
            } else {
                watch.compare(player, scripts);
            }
        }
        WATCHED.keySet().retainAll(online);
    }

    /** A block, as the object a pack reads a location and a dimension off. */
    private static Map<String, Object> block(Level level, BlockPos position) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("x", position.getX());
        fields.put("y", position.getY());
        fields.put("z", position.getZ());
        return fields;
    }

    /** An item stack, in the shape the script rebuilds one from. An empty hand carries nothing. */
    private static Map<String, Object> item(ItemStack stack) {
        if (stack.isEmpty()) return null;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("typeId", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        fields.put("amount", stack.getCount());
        fields.put("damage", stack.getDamageValue());
        fields.put("maxDurability", stack.getMaxDamage());
        return fields;
    }

    private static Map<String, Object> permutation(BlockState state) {
        Map<String, Object> states = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            states.put(property.getName(), stateValue(state, property));
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("typeId", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        fields.put("states", states);
        return fields;
    }

    private static <T extends Comparable<T>> String stateValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static Map<String, Object> vector(Vec3 at) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("x", at.x);
        fields.put("y", at.y);
        fields.put("z", at.z);
        return fields;
    }

    /** Bedrock measures a face location from the block's own corner. */
    private static Map<String, Object> faceLocation(BlockHitResult hit) {
        BlockPos position = hit.getBlockPos();
        return vector(hit.getLocation().subtract(position.getX(), position.getY(), position.getZ()));
    }

    /** Bedrock's names for a face and for a game mode are Java's under a capital. */
    private static String capitalised(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private record Placement(UUID player, ResourceKey<Level> dimension, BlockPos clicked, Direction face,
        Block placing, boolean clickedHeld, boolean beyondHeld) {
    }

    /**
     * What a player looked like at the end of the last tick.
     *
     * Fabric offers no callback for a game mode, a dimension, an inventory slot or a chargeable item
     * changing, so each is found by comparing the player against the tick before.
     */
    private static final class PlayerWatch {
        private final List<ItemStack> inventory = new ArrayList<>();
        private GameType gameMode;
        private ResourceKey<Level> dimension;
        private Vec3 location;
        private ItemStack using = ItemStack.EMPTY;
        private int useDuration;
        private int hotbarSlot;
        private boolean sneaking;
        private boolean jumping;

        private PlayerWatch(ServerPlayer player) {
            gameMode = Platform.gameMode(player);
            dimension = player.level().dimension();
            location = player.position();
            hotbarSlot = Platform.selectedSlot(player);
            sneaking = player.isShiftKeyDown();
            jumping = ((JumpingAccessor) player).mcaddon$jumping();
            snapshot(player);
        }

        private void compare(ServerPlayer player, ScriptHost scripts) {
            gameModeChanged(player, scripts);
            dimensionChanged(player, scripts);
            inventoryChanged(player, scripts);
            itemUseChanged(player, scripts);
            hotbarChanged(player, scripts);
            buttonsChanged(player, scripts);
            location = player.position();
        }

        private void hotbarChanged(ServerPlayer player, ScriptHost scripts) {
            int current = Platform.selectedSlot(player);
            if (current == hotbarSlot) return;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("player", ScriptHost.EntityHandle.of(player));
            fields.put("previousSlotSelected", hotbarSlot);
            fields.put("newSlotSelected", current);
            fields.put("itemStack", item(player.getInventory().getItem(current)));
            hotbarSlot = current;
            scripts.raiseWorldEvent("playerHotbarSelectedSlotChange", fields);
        }

        /**
         * The two buttons Bedrock reports, each raised on the tick it goes down or comes up.
         *
         * Bedrock reads the key itself and Java reads the state the key put the player in, so a jump
         * is reported for as long as the player is off the ground under their own power.
         */
        private void buttonsChanged(ServerPlayer player, ScriptHost scripts) {
            boolean held = ((JumpingAccessor) player).mcaddon$jumping();
            sneaking = buttonChanged(player, scripts, "Sneak", sneaking, player.isShiftKeyDown());
            jumping = buttonChanged(player, scripts, "Jump", jumping, held);
        }

        private boolean buttonChanged(ServerPlayer player, ScriptHost scripts, String button,
            boolean before, boolean now) {

            if (before == now) return now;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("player", ScriptHost.EntityHandle.of(player));
            fields.put("button", button);
            fields.put("newButtonState", now ? "Pressed" : "Released");
            scripts.raiseWorldEvent("playerButtonInput", fields);
            return now;
        }

        private void gameModeChanged(ServerPlayer player, ScriptHost scripts) {
            GameType current = Platform.gameMode(player);
            if (current == gameMode) return;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("player", ScriptHost.EntityHandle.of(player));
            fields.put("fromGameMode", capitalised(gameMode.getName()));
            fields.put("toGameMode", capitalised(current.getName()));
            gameMode = current;
            scripts.raiseWorldEvent("playerGameModeChange", fields);
        }

        private void dimensionChanged(ServerPlayer player, ScriptHost scripts) {
            ResourceKey<Level> current = player.level().dimension();
            if (current.equals(dimension)) return;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("player", ScriptHost.EntityHandle.of(player));
            fields.put("fromDimension", Platform.idOf(dimension).toString());
            fields.put("fromLocation", vector(location));
            fields.put("toDimension", Platform.idOf(current).toString());
            fields.put("toLocation", vector(player.position()));
            dimension = current;
            scripts.raiseWorldEvent("playerDimensionChange", fields);
        }

        private void inventoryChanged(ServerPlayer player, ScriptHost scripts) {
            Inventory carried = player.getInventory();
            for (int i = 0; i < carried.getContainerSize() && i < inventory.size(); i++) {
                ItemStack now = carried.getItem(i);
                ItemStack before = inventory.get(i);
                if (ItemStack.matches(before, now)) continue;

                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("player", ScriptHost.EntityHandle.of(player));
                fields.put("slot", i);
                fields.put("itemStack", item(now));
                fields.put("beforeItemStack", item(before));
                scripts.raiseWorldEvent("playerInventoryItemChange", fields);
            }
            snapshot(player);
        }

        /**
         * The four events a chargeable item raises.
         *
         * A charge that ran its course leaves no ticks on the clock and one the player let go of
         * does, which is how the complete and the release events are told apart. Bedrock raises the
         * stop event for both.
         */
        private void itemUseChanged(ServerPlayer player, ScriptHost scripts) {
            if (player.isUsingItem()) {
                boolean starting = using.isEmpty();
                if (starting) using = player.getUseItem().copy();
                useDuration = player.getUseItemRemainingTicks();
                if (starting) raiseItemUse("itemStartUse", player, scripts);
                return;
            }
            if (using.isEmpty()) return;

            raiseItemUse(useDuration <= 1 ? "itemCompleteUse" : "itemReleaseUse", player, scripts);
            raiseItemUse("itemStopUse", player, scripts);
            using = ItemStack.EMPTY;
        }

        private void raiseItemUse(String event, ServerPlayer player, ScriptHost scripts) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("source", ScriptHost.EntityHandle.of(player));
            fields.put("itemStack", item(using));
            fields.put("useDuration", useDuration);
            scripts.raiseWorldEvent(event, fields);
        }

        private void snapshot(ServerPlayer player) {
            Inventory carried = player.getInventory();
            inventory.clear();
            for (int i = 0; i < carried.getContainerSize(); i++) inventory.add(carried.getItem(i).copy());
        }
    }
}
