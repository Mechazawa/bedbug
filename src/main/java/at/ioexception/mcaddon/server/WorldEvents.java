package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.convert.Functions;
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
import net.minecraft.world.MenuProvider;
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
import net.minecraft.world.level.block.entity.BlockEntity;
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
 * What happens in a world, told the way a pack's script asks about it.
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

    /** Each game rule as it read at the end of the last tick, under the name Bedrock gives it. */
    private static final Map<String, String> RULES = new HashMap<>();

    /** The block each player is part way through breaking, and when they started on it. */
    private static final Map<UUID, Breaking> BREAKING = new HashMap<>();

    /** The container each player has open, ready for the event its closing raises. */
    private static final Map<UUID, OpenContainer> OPENED = new HashMap<>();

    /** The block each player last reached for, and the tick they did it on. */
    private static final Map<UUID, Reached> REACHED = new HashMap<>();

    /** The click each player is holding on a block, which ends the tick after the last one. */
    private static final Map<UUID, Map<String, Object>> USING_ON = new HashMap<>();

    private static final Set<UUID> USED_ON_THIS_TICK = new HashSet<>();

    /** How long Bedrock says a weather change lasts when nothing asked for a length. */
    private static final int WEATHER_TICKS = 6000;

    private WorldEvents() {
    }

    /**
     * Whether a script is running that any of this could be told to.
     *
     * Every event here asks it as well, so a caller only needs it where gathering what the event
     * carries costs something. A pressure plate and a tripwire each count what is standing on them,
     * which is an entity query the game is already paying for once.
     */
    public static boolean listening() {
        return ServerHooks.scripts() != null;
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
        pollGameRules(server);
        endUseOn();
        BlockEvents.endTick(server);
    }

    /**
     * A game rule moving, found by comparing each rule against the tick before.
     *
     * Java raises nothing when a rule is set, and it is set from the command, from a pack's own
     * script and from the world being loaded. Reading the rules back catches every one of those, and
     * a rule this version of Java has no form of answers with nothing and is skipped.
     */
    private static void pollGameRules(MinecraftServer server) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) {
            RULES.clear();
            return;
        }

        for (String name : Functions.gameRuleNames()) {
            String now = Platform.gameRule(server, name);
            if (now == null) continue;

            String before = RULES.put(name, now);
            if (before == null || before.equals(now)) continue;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("rule", name);
            fields.put("value", ruleValue(now));
            scripts.raiseWorldEvent("gameRuleChange", fields);
        }
    }

    /** Bedrock hands a rule's value over as a flag or a count, which is what its text spells. */
    private static Object ruleValue(String written) {
        if ("true".equals(written) || "false".equals(written)) return Boolean.valueOf(written);

        boolean counted = !written.isEmpty() && written.chars().allMatch(Character::isDigit);
        return counted ? Integer.valueOf(written) : written;
    }

    /**
     * The click a player was holding on a block, ended once a tick passes with no click in it.
     *
     * The client sends a use-on packet for as long as the button is down, so a run of them is one
     * use and the first tick without one is where it stopped.
     */
    private static void endUseOn() {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) {
            USING_ON.clear();
            USED_ON_THIS_TICK.clear();
            return;
        }

        USING_ON.entrySet().removeIf(held -> {
            if (USED_ON_THIS_TICK.contains(held.getKey())) return false;

            scripts.raiseWorldEvent("itemStopUseOn", held.getValue());
            return true;
        });
        USED_ON_THIS_TICK.clear();
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

    /**
     * An explosion, asked about before it lands and reported once a listener has let it through.
     *
     * The blocks are the ones the game has already worked out it will take, and they are still
     * standing at this point, so each one can be reported with the permutation it is about to lose.
     * Gathering those is worth a check first: every explosion in the world would otherwise pay for
     * a list nobody asked for.
     */
    public static boolean explosionRefused(Level level, Entity source, List<BlockPos> impacted) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || !listensToExplosion(scripts)) return false;

        if (scripts.raiseCancellable("explosion", explosion(level, source, impacted))) return true;

        scripts.raiseWorldEvent("explosion", explosion(level, source, impacted));
        for (BlockPos position : impacted) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("block", block(level, position));
            fields.put("dimension", Platform.idOf(level.dimension()).toString());
            fields.put("explodedBlockPermutation", permutation(level.getBlockState(position)));
            if (source != null) fields.put("source", ScriptHost.EntityHandle.of(source));
            scripts.raiseWorldEvent("blockExplode", fields);
        }
        return false;
    }

    private static boolean listensToExplosion(ScriptHost scripts) {
        return scripts.listens("worldBefore", "explosion")
            || scripts.listens("worldAfter", "explosion")
            || scripts.listens("worldAfter", "blockExplode");
    }

    private static Map<String, Object> explosion(Level level, Entity source, List<BlockPos> impacted) {
        List<Object> blocks = new ArrayList<>();
        for (BlockPos position : impacted) blocks.add(block(level, position));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        if (source != null) fields.put("source", ScriptHost.EntityHandle.of(source));
        fields.put("impactedBlocks", blocks);
        return fields;
    }

    /** A piston starting to move, which Bedrock reports with the way it is going. */
    public static void pistonMoved(Level level, BlockPos position, boolean expanding) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("block", block(level, position));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("isExpanding", expanding);
        scripts.raiseWorldEvent("pistonActivate", fields);
    }

    /**
     * A pressure plate whose signal moved, which Bedrock splits into a push and a pop.
     *
     * A weighted plate answers with a strength rather than a flag, so any rise is a push and any
     * fall is a pop. Only a push carries whoever caused it: a plate pops on its own clock, with
     * nobody standing on it to name.
     */
    public static void plateSignalChanged(Level level, BlockPos position, Entity source, int before,
        int now) {

        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || before == now) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("block", block(level, position));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("previousRedstonePower", before);
        fields.put("redstonePower", now);
        if (now < before) {
            scripts.raiseWorldEvent("pressurePlatePop", fields);
            return;
        }

        if (source != null) fields.put("source", ScriptHost.EntityHandle.of(source));
        scripts.raiseWorldEvent("pressurePlatePush", fields);
    }

    /** A tripwire whose line was crossed or cleared, with everything standing in it. */
    public static void tripWireTripped(Level level, BlockPos position, boolean powered,
        List<Entity> sources) {

        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        List<Object> standing = new ArrayList<>();
        for (Entity entity : sources) standing.add(ScriptHost.EntityHandle.of(entity));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("block", block(level, position));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("isPowered", powered);
        fields.put("sources", standing);
        scripts.raiseWorldEvent("tripWireTrip", fields);
    }

    /**
     * A target block struck by something, with the strength the hit was worth.
     *
     * Bedrock's hit vector is the way the projectile was going as it arrived, which is what Java
     * carries as the thing's own motion.
     */
    public static void targetBlockHit(Level level, BlockPos position, Entity source, int before,
        int now) {

        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("block", block(level, position));
        fields.put("dimension", Platform.idOf(level.dimension()).toString());
        fields.put("hitVector", vector(source == null ? Vec3.ZERO : source.getDeltaMovement().normalize()));
        fields.put("previousRedstonePower", before);
        fields.put("redstonePower", now);
        if (source != null) fields.put("source", ScriptHost.EntityHandle.of(source));
        scripts.raiseWorldEvent("targetBlockHit", fields);
    }

    /** Somebody starting to break a block, which is the hold rather than the break that may follow. */
    public static void startedBreaking(ServerPlayer player, BlockPos position, Direction face) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        BREAKING.put(player.getUUID(), new Breaking(position, face, player.tickCount));
        scripts.raiseWorldEvent("playerStartBreakingBlock", breaking(player, position, face));
    }

    /** Somebody letting go part way through, reported with how far the block had got. */
    public static void cancelledBreaking(ServerPlayer player) {
        Breaking started = BREAKING.remove(player.getUUID());
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null || started == null) return;

        Map<String, Object> fields = breaking(player, started.position(), started.face());
        fields.put("breakProgress", brokenSoFar(player, started));
        scripts.raiseWorldEvent("playerCancelBreakingBlock", fields);
    }

    /** The break ran its course, so there is nothing left to cancel. */
    public static void finishedBreaking(ServerPlayer player) {
        BREAKING.remove(player.getUUID());
    }

    private static Map<String, Object> breaking(ServerPlayer player, BlockPos position, Direction face) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("block", block(player.level(), position));
        fields.put("dimension", Platform.idOf(player.level().dimension()).toString());
        fields.put("blockPermutation", permutation(player.level().getBlockState(position)));
        fields.put("face", capitalised(face.getName()));
        fields.put("heldItemStack", item(player.getMainHandItem()));
        return fields;
    }

    /** How far the break had got, worked out the way the game does: the block's own speed per tick. */
    private static float brokenSoFar(ServerPlayer player, Breaking started) {
        BlockState state = player.level().getBlockState(started.position());
        float perTick = state.getDestroyProgress(player, player.level(), started.position());
        return Math.min(1.0F, perTick * (player.tickCount - started.startedAt() + 1));
    }

    /**
     * The arm going out, which Java reports without saying why.
     *
     * The one reason the server already holds is a break in progress, so a swing during one is a
     * mining swing and every other swing is reported with no source named.
     */
    public static void swung(ServerPlayer player) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("heldItemStack", item(player.getMainHandItem()));
        fields.put("swingSource", BREAKING.containsKey(player.getUUID()) ? "Mine" : "None");
        scripts.raiseWorldEvent("playerSwingStart", fields);
    }

    /**
     * A game mode about to change, which a pack may refuse.
     *
     * The mode a player already has is set again as they join and as they come back from dying, so
     * only a mode that differs from the one they carry is offered as a change.
     */
    public static boolean gameModeRefused(ServerPlayer player, GameType wanted) {
        ScriptHost scripts = ServerHooks.scripts();
        GameType current = Platform.gameMode(player);
        if (scripts == null || current == wanted) return false;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("player", ScriptHost.EntityHandle.of(player));
        fields.put("fromGameMode", capitalised(current.getName()));
        fields.put("toGameMode", capitalised(wanted.getName()));
        return scripts.raiseCancellable("playerGameModeChange", fields);
    }

    /** A screen opening over a container, which is a block's when a block entity holds it. */
    public static void menuOpened(ServerPlayer player, MenuProvider provider) {
        if (provider instanceof BlockEntity held) {
            openedBlock(player, held.getBlockPos());
            return;
        }
        if (provider instanceof Entity held) {
            openedEntity(player, held);
            return;
        }

        // A double chest hands over a provider that joins its two halves and is neither the block
        // entity nor an entity, so the block the player reached for on this tick is what opened.
        Reached reached = REACHED.get(player.getUUID());
        if (reached != null && reached.tick() == player.tickCount) {
            openedBlock(player, reached.position());
        }
    }

    /** A mount's own inventory, which Java opens without going through a menu provider. */
    public static void mountOpened(ServerPlayer player, Entity mount) {
        openedEntity(player, mount);
    }

    public static void containerClosed(ServerPlayer player) {
        OpenContainer open = OPENED.remove(player.getUUID());
        if (open != null) raiseContainer(open.closing(), "closeSource", player, open.fields());
    }

    private static void openedBlock(ServerPlayer player, BlockPos position) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("block", block(player.level(), position));
        fields.put("dimension", Platform.idOf(player.level().dimension()).toString());
        OPENED.put(player.getUUID(), new OpenContainer("blockContainerClosed", fields));
        raiseContainer("blockContainerOpened", "openSource", player, fields);
    }

    private static void openedEntity(ServerPlayer player, Entity holder) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ScriptHost.EntityHandle.of(holder));
        OPENED.put(player.getUUID(), new OpenContainer("entityContainerClosed", fields));
        raiseContainer("entityContainerOpened", "openSource", player, fields);
    }

    /** Bedrock names whoever reached the container in a shape of its own, holding that one entity. */
    private static void raiseContainer(String event, String sourceField, ServerPlayer player,
        Map<String, Object> fields) {

        ScriptHost scripts = ServerHooks.scripts();
        if (scripts == null) return;

        Map<String, Object> carried = new LinkedHashMap<>(fields);
        carried.put(sourceField, Map.of("entity", ScriptHost.EntityHandle.of(player)));
        scripts.raiseWorldEvent(event, carried);
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
     * An entity about to be tamed, which a pack may call off: true when one did.
     *
     * Java tames a wolf, a cat or a parrot through `TamableAnimal.tame` and a horse through
     * `AbstractHorse.tameWithName`, and a pack's own entity through its `minecraft:tameable` or
     * `minecraft:tamemount`, so all three ask here first and say so afterwards.
     */
    public static boolean tamingRefused(net.minecraft.world.entity.Entity tamed,
        net.minecraft.world.entity.player.Player by) {
        ScriptHost scripts = ServerHooks.scripts();
        return scripts != null && !tamed.level().isClientSide()
            && scripts.raiseCancellable("entityTamed", taming(tamed, by));
    }

    public static void tamed(net.minecraft.world.entity.Entity tamed,
        net.minecraft.world.entity.player.Player by) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts != null && !tamed.level().isClientSide()) {
            scripts.raiseWorldEvent("entityTamed", taming(tamed, by));
        }
    }

    private static Map<String, Object> taming(net.minecraft.world.entity.Entity tamed,
        net.minecraft.world.entity.player.Player by) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", ScriptHost.EntityHandle.of(tamed));
        fields.put("tamingEntity", ScriptHost.EntityHandle.of(by));
        return fields;
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

    /** What one player was part way through, which nothing finishes once they are gone. */
    public static void forget(ServerPlayer player) {
        UUID id = player.getUUID();
        BREAKING_WITH.remove(id);
        BREAKING.remove(id);
        OPENED.remove(id);
        REACHED.remove(id);
        USING_ON.remove(id);
        USED_ON_THIS_TICK.remove(id);
    }

    public static void forget() {
        BREAKING_WITH.clear();
        PLACEMENTS.clear();
        WATCHED.clear();
        WEATHER.clear();
        RULES.clear();
        BREAKING.clear();
        OPENED.clear();
        REACHED.clear();
        USING_ON.clear();
        USED_ON_THIS_TICK.clear();
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

        // A creative break and one a tool finishes inside a tick destroy the block from the packet
        // that started the hold and send no second one, so the block going is what ends a hold too.
        BREAKING.remove(player.getUUID());

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

        REACHED.put(player.getUUID(), new Reached(hit.getBlockPos(), player.tickCount));

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
            // A held button sends a packet a tick, and the run of them is one use, so the start
            // belongs to the packet that opens a run and every later one only keeps it alive.
            if (!USING_ON.containsKey(player.getUUID())) {
                Map<String, Object> started = new LinkedHashMap<>();
                started.put("source", ScriptHost.EntityHandle.of(player));
                started.put("block", block(level, hit.getBlockPos()));
                started.put("blockFace", capitalised(hit.getDirection().getName()));
                started.put("itemStack", item(held));
                scripts.raiseWorldEvent("itemStartUseOn", started);
            }

            Map<String, Object> stopping = new LinkedHashMap<>();
            stopping.put("source", ScriptHost.EntityHandle.of(player));
            stopping.put("block", block(level, hit.getBlockPos()));
            stopping.put("itemStack", item(held));
            USING_ON.put(player.getUUID(), stopping);
            USED_ON_THIS_TICK.add(player.getUUID());
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

    private record Breaking(BlockPos position, Direction face, int startedAt) {
    }

    private record Reached(BlockPos position, int tick) {
    }

    /** What a player has open: the event its closing raises, and what that event says was closed. */
    private record OpenContainer(String closing, Map<String, Object> fields) {
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
            boolean wasSneaking = sneaking;
            sneaking = buttonChanged(player, scripts, "Sneak", sneaking, player.isShiftKeyDown());
            jumping = buttonChanged(player, scripts, "Jump", jumping, held);
            if (sneaking != wasSneaking) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("entity", ScriptHost.EntityHandle.of(player));
                scripts.raiseWorldEvent(sneaking ? "entityStartSneaking" : "entityStopSneaking", fields);
            }
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
