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
        AttributeSupplier.Builder built = PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, definition.maxHealth())
            .add(Attributes.MOVEMENT_SPEED, definition.movementSpeed())
            .add(Attributes.FOLLOW_RANGE, number(definition, "minecraft:follow_range", "value", 16))
            .add(Attributes.KNOCKBACK_RESISTANCE,
                number(definition, "minecraft:knockback_resistance", "value", 0));

        // 26 moved how far food tempts a mob out of the goal and onto the mob itself, and a goal
        // reading an attribute the type never declared fails the tick it runs in.
        //? if >=26 {
        built = built.add(Attributes.TEMPT_RANGE, 10);
        //?}
        return built;
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

    /**
     * How many riders the pack allows, which is what makes an entity rideable at all.
     *
     * Bedrock states the seats and Java asks the entity each time somebody tries to get on, so the
     * count is read back out of the component rather than kept.
     */
    @Override
    protected boolean canAddPassenger(net.minecraft.world.entity.Entity passenger) {
        if (definition == null || !has("minecraft:rideable")) return false;
        return getPassengers().size() < (int) number(definition, "minecraft:rideable", "seat_count", 1);
    }

    /**
     * Where a rider sits, taken from the first seat the pack described.
     *
     * Bedrock measures a seat in the model's own units, sixteen to the block, and puts the vertical
     * axis where Java does, so only the scale differs.
     */
    //? if >=1.21 {
    @Override
    protected net.minecraft.world.phys.Vec3 getPassengerAttachmentPoint(
        net.minecraft.world.entity.Entity passenger,
        net.minecraft.world.entity.EntityDimensions dimensions, float scale) {
        return seat() == null
            ? super.getPassengerAttachmentPoint(passenger, dimensions, scale) : seat();
    }
    //?} else {
    /*@Override
    public double getPassengersRidingOffset() {
        return seat() == null ? super.getPassengersRidingOffset() : seat().y;
    }
    *///?}

    /**
     * Picks up what the pack said this entity collects.
     *
     * Bedrock keeps an inventory on the entity and lists what may go in it; Java gives a mob one
     * hand and a body, so what is picked up is held rather than stored, which is what a pack using
     * this for a villager carrying bread is describing anyway.
     */
    private void pickUpNearby() {
        if (!has("minecraft:behavior.pickup_items") || level().isClientSide()) return;
        if (!getMainHandItem().isEmpty()) return;

        double range = number(definition, "minecraft:behavior.pickup_items", "max_dist", 3);
        for (net.minecraft.world.entity.item.ItemEntity dropped
            : level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                getBoundingBox().inflate(range))) {
            if (!dropped.isAlive() || dropped.hasPickUpDelay()) continue;
            if (!shareable(dropped.getItem())) continue;

            setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, dropped.getItem().copy());
            dropped.discard();
            return;
        }
    }

    /** Whether the pack's `shareables` list allows this item, which allows everything when absent. */
    private boolean shareable(net.minecraft.world.item.ItemStack stack) {
        com.google.gson.JsonObject body = definition.components().get("minecraft:shareables");
        com.google.gson.JsonArray items = body == null ? null : body.getAsJsonArray("items");
        if (items == null) return true;

        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        for (com.google.gson.JsonElement item : items) {
            if (!item.isJsonObject()) continue;

            String named = at.ioexception.mcaddon.util.Json.text(item.getAsJsonObject(), "item", null);
            if (named != null && (named.equals(id) || ("minecraft:" + named).equals(id))) return true;
        }
        return false;
    }

    /**
     * Restocks the trades a pack said should come back.
     *
     * Bedrock counts the restock in days and Java counts the world's own time, so a day is the
     * twenty-four thousand ticks the game already measures one by.
     */
    private void resupply() {
        if (offers == null || !has("minecraft:trade_resupply")) return;

        long day = level().getGameTime() / 24000L;
        if (day == lastResupplyDay) return;

        lastResupplyDay = day;
        offers.forEach(MerchantOffer::resetUses);
    }

    private long lastResupplyDay = -1;

    /**
     * Moves the entity somewhere else nearby, the way Bedrock's teleport component does.
     *
     * A pack gives a chance and a distance; what it is describing is a mob that blinks about rather
     * than walks, which Java has no component for but can be asked to do outright.
     */
    private void teleportAbout() {
        if (!has("minecraft:teleport") || level().isClientSide()) return;

        double chance = number(definition, "minecraft:teleport", "random_teleport_chance", 0.01);
        if (random.nextDouble() > chance) return;

        double range = number(definition, "minecraft:teleport", "max_random_teleport_time", 16);
        double x = getX() + (random.nextDouble() - 0.5) * range * 2;
        double y = getY() + (random.nextInt(8) - 4);
        double z = getZ() + (random.nextDouble() - 0.5) * range * 2;
        Platform.placeAt(this, x, y, z, getYRot(), getXRot());
    }

    /** The first seat a `minecraft:rideable` component names, or null when it names none. */
    private net.minecraft.world.phys.Vec3 seat() {
        com.google.gson.JsonObject body = definition == null
            ? null : definition.components().get("minecraft:rideable");
        com.google.gson.JsonArray seats = body == null ? null : body.getAsJsonArray("seats");
        if (seats == null || seats.isEmpty() || !seats.get(0).isJsonObject()) return null;

        com.google.gson.JsonArray at = seats.get(0).getAsJsonObject().getAsJsonArray("position");
        return at == null || at.size() < 3 ? null
            : new net.minecraft.world.phys.Vec3(at.get(0).getAsDouble() / 16,
                at.get(1).getAsDouble() / 16, at.get(2).getAsDouble() / 16);
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
        if ((tickCount + getId()) % Sensors.INTERVAL_TICKS == 0) {
            Sensors.tick(this, ServerHooks.state());
            resupply();
            teleportAbout();
            pickUpNearby();
        }
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
