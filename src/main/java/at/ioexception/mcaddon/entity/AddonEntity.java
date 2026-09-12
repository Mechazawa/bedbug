package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.TradeTable;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.Sensors;
import at.ioexception.mcaddon.server.ServerHooks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;

/**
 * A mob a behaviour pack described.
 *
 * One class serves every add-on entity: what differs between them is data, and the data lives in the
 * definition the entity's type was registered from. Bedrock's behaviour components become Java goals
 * where an equivalent exists, and the rest are reported rather than silently dropped.
 */
public class AddonEntity extends PathfinderMob implements Merchant {
    private final ServerEntityDefinition definition;
    private final java.util.Set<String> activeGroups = new java.util.LinkedHashSet<>();
    private int timerDueTick = -1;
    private Player tradingPlayer;
    private MerchantOffers offers;
    private int tradeExperience;

    public AddonEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.definition = AddonEntities.definitionOf(type);
        if (definition != null) {
            addGoals();
            applyComponents();
        }
    }

    public ServerEntityDefinition definition() {
        return definition;
    }

    /**
     * The component groups a pack's events have switched on.
     *
     * Tracked whole even where the components inside have no Java meaning, because a pack's own
     * filters and later events read the set back.
     */
    public java.util.Set<String> activeGroups() {
        return activeGroups;
    }

    /** When `minecraft:timer` next fires, in ticks of this entity's own age. Negative until set. */
    public int timerDueTick() {
        return timerDueTick;
    }

    public void setTimerDueTick(int tick) {
        timerDueTick = tick;
    }

    /**
     * The attributes a pack's components describe, which Java fixes when the type is registered.
     *
     * Bedrock states these as components on the entity and Java as attributes on the type, so the
     * numbers are read out of the components here rather than applied to an entity later.
     */
    public static AttributeSupplier.Builder attributes(ServerEntityDefinition definition) {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, definition.maxHealth())
            .add(Attributes.MOVEMENT_SPEED, definition.movementSpeed())
            .add(Attributes.FOLLOW_RANGE, number(definition, "minecraft:follow_range", "value", 16))
            .add(Attributes.KNOCKBACK_RESISTANCE,
                number(definition, "minecraft:knockback_resistance", "value", 0));
    }

    /** A number a component carries, or the fallback when the pack declared neither. */
    private static double number(ServerEntityDefinition definition, String component, String field,
        double fallback) {
        com.google.gson.JsonObject body = definition.components().get(component);
        return body == null || !body.has(field) ? fallback : body.get(field).getAsDouble();
    }

    /**
     * The components that change the entity itself rather than how it decides what to do.
     *
     * These are read once as the entity comes into the world, because each of them is a fact about
     * the entity that Java holds on the entity rather than on its type.
     */
    private void applyComponents() {
        if (definition.components().containsKey("minecraft:fire_immune")) {
            setRemainingFireTicks(0);
        }
        if (definition.components().containsKey("minecraft:is_hidden_when_invisible")) {
            setInvisible(isInvisible());
        }

        if (definition.components().containsKey("minecraft:experience_reward")) {
            xpReward = (int) number(definition, "minecraft:experience_reward", "on_death", xpReward);
        }
        if (definition.components().containsKey("minecraft:persistent")) {
            setPersistenceRequired();
        }

        // Bedrock names the kind of pathfinding; Java asks whether the navigator may take each route.
        net.minecraft.world.entity.ai.navigation.PathNavigation navigation = getNavigation();
        if (navigation instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation ground) {
            ground.setCanOpenDoors(has("minecraft:annotation.open_door"));
            ground.setCanFloat(has("minecraft:navigation.swim") || has("minecraft:navigation.generic"));
        }
        if (has("minecraft:preferred_path")) {
            // The enum naming a kind of ground was renamed in 1.21.
            //? if >=1.21 {
            setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 8f);
            //?} else {
            /*setPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.WATER, 8f);
            *///?}
        }
    }

    private boolean has(String component) {
        return definition.components().containsKey(component);
    }

    /** Bedrock says an entity is angry; Java asks the entity, so the flag is kept here. */
    public boolean isAngry() {
        return has("minecraft:angry");
    }

    /**
     * Whether a lead can hold this entity.
     *
     * A pack saying so is describing what Java already allows for a mob, so the component only has
     * to be honoured when it says the opposite.
     */
    // A mob could always be leashed before 1.21.2, where the question moved onto the entity.
    //? if >=1.21.2 {
    @Override
    //?}
    public boolean canBeLeashed() {
        return definition == null || !definition.components().containsKey("minecraft:leashable")
            || number(definition, "minecraft:leashable", "can_be_stolen", 1) != 0;
    }

    private void addGoals() {
        int priority = 0;
        for (String behaviour : definition.behaviours()) {
            priority++;
            switch (behaviour) {
                case "minecraft:behavior.float" -> goalSelector.addGoal(priority, new FloatGoal(this));
                case "minecraft:behavior.panic" -> goalSelector.addGoal(priority, new PanicGoal(this, 1.25));
                case "minecraft:behavior.random_stroll", "minecraft:behavior.move_outdoors",
                     "minecraft:behavior.explore_outskirts" ->
                    goalSelector.addGoal(priority, new WaterAvoidingRandomStrollGoal(this, 0.6));
                case "minecraft:behavior.look_at_player", "minecraft:behavior.look_at_trading_player" ->
                    goalSelector.addGoal(priority, new LookAtPlayerGoal(this, Player.class, 8f));
                case "minecraft:behavior.random_look_around" ->
                    goalSelector.addGoal(priority, new RandomLookAroundGoal(this));

                // Bedrock names the kind of wandering; Java has one stroll goal and a speed.
                case "minecraft:behavior.move_indoors",
                     "minecraft:behavior.move_towards_dwelling_restriction",
                     "minecraft:behavior.move_towards_home_restriction",
                     "minecraft:behavior.move_towards_restriction" ->
                    goalSelector.addGoal(priority, new MoveTowardsRestrictionGoal(this, 0.6));
                case "minecraft:behavior.mount_pathing", "minecraft:behavior.follow_owner",
                     "minecraft:behavior.stroll_towards_village" ->
                    goalSelector.addGoal(priority, new WaterAvoidingRandomStrollGoal(this, 0.6));

                case "minecraft:behavior.avoid_mob_type", "minecraft:behavior.hide",
                     "minecraft:behavior.avoid_block" ->
                    goalSelector.addGoal(priority,
                        new AvoidEntityGoal<>(this, Monster.class, 8f, 1.0, 1.2));
                case "minecraft:behavior.panic_on_fire" ->
                    goalSelector.addGoal(priority, new PanicGoal(this, 1.25));

                case "minecraft:behavior.tempt" ->
                    goalSelector.addGoal(priority, Platform.temptGoal(this));
                case "minecraft:behavior.open_door", "minecraft:annotation.open_door" ->
                    goalSelector.addGoal(priority, new OpenDoorGoal(this, true));

                case "minecraft:behavior.melee_attack", "minecraft:behavior.melee_box_attack" ->
                    goalSelector.addGoal(priority,
                        new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(this, 1.0, false));
                case "minecraft:behavior.nearest_attackable_target" ->
                    targetSelector.addGoal(priority,
                        new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                            this, Player.class, true));
                case "minecraft:behavior.hurt_by_target" ->
                    targetSelector.addGoal(priority,
                        new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));

                default -> AddonEntities.unsupported(behaviour);
            }
        }
    }

    /**
     * Bedrock's sensors have no Java equivalent, so they are evaluated here rather than as goals.
     *
     * Staggered by entity id: a pack's two big entities carry dozens of subsensors each, and every
     * one of them landing on the same tick is what turns a crowded village into a stutter.
     */
    @Override
    public void tick() {
        super.tick();
        if (definition == null || !(level() instanceof net.minecraft.server.level.ServerLevel)) return;
        if ((tickCount + getId()) % Sensors.INTERVAL_TICKS == 0) Sensors.tick(this, ServerHooks.state());
    }

    /** True when one of the pack's interactions applied, which is what consumes the click. */
    private boolean interact(Player player) {
        com.google.gson.JsonObject component = definition == null
            ? null : definition.components().get("minecraft:interact");
        if (component == null) return false;

        for (com.google.gson.JsonElement element
            : at.ioexception.mcaddon.util.Json.list(component, "interactions")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject interaction = element.getAsJsonObject();

            com.google.gson.JsonObject onInteract = interaction.getAsJsonObject("on_interact");
            String event = onInteract == null
                ? null : at.ioexception.mcaddon.util.Json.text(onInteract, "event", null);
            if (event == null) continue;

            Filter filter = Filter.parse(onInteract.get("filters"));
            if (!filter.matches(new Filter.Context(this, player, null, ServerHooks.state()))) continue;

            EntityEvents.trigger(this, event, player, ServerHooks.state());
            return true;
        }
        return false;
    }

    @Override
    public boolean isBaby() {
        return false;
    }

    /**
     * What a right click does: a pack's own interaction first, then its trade table.
     *
     * `minecraft:interact` is how a pack makes an entity respond to being used, and each interaction
     * carries the filters that decide whether it applies and the event it fires.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isClientSide() && interact(player)) return InteractionResult.SUCCESS;

        if (AddonEntities.tradesOf(getType()) == null || isClientSide()) {
            return super.mobInteract(player, hand);
        }

        setTradingPlayer(player);
        openTradingScreen(player, getDisplayName(), 1);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void setTradingPlayer(Player player) {
        tradingPlayer = player;
    }

    @Override
    public Player getTradingPlayer() {
        return tradingPlayer;
    }

    /**
     * Built once per entity, because a Bedrock group picks a random few of the trades it lists and
     * rebuilding would hand the player a different shop each time they opened it.
     */
    @Override
    public MerchantOffers getOffers() {
        if (offers == null) {
            offers = new MerchantOffers();
            TradeTable trades = AddonEntities.tradesOf(getType());
            if (trades != null) trades.offers().forEach(offer -> add(offers, offer));
        }
        return offers;
    }

    private static void add(MerchantOffers into, TradeTable.Offer offer) {
        ItemStack cost = stack(offer.costItem(), offer.costCount());
        ItemStack result = stack(offer.resultItem(), offer.resultCount());
        if (cost.isEmpty() || result.isEmpty()) return;

        into.add(Platform.offer(cost, stack(offer.secondCostItem(), offer.secondCostCount()),
            result, offer.maxUses(), offer.experience()));
    }

    private static ItemStack stack(String item, int count) {
        Identifier id = item == null ? null : Identifier.tryParse(item);
        return id == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(id)
            .map(found -> new ItemStack(found, Math.max(1, count)))
            .orElse(ItemStack.EMPTY);
    }

    @Override
    public void overrideOffers(MerchantOffers replacement) {
        offers = replacement;
    }

    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
        tradeExperience += offer.getXp();
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return tradeExperience;
    }

    @Override
    public void overrideXp(int experience) {
        tradeExperience = experience;
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.VILLAGER_YES;
    }

    @Override
    public boolean isClientSide() {
        return !(level() instanceof net.minecraft.server.level.ServerLevel);
    }

    public boolean stillValid(Player player) {
        return tradingPlayer == player && isAlive() && distanceToSqr(player) < 64;
    }
}
