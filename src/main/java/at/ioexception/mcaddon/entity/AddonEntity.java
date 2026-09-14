package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Anger;
import at.ioexception.mcaddon.bedrock.behaviour.Despawn;
import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.TargetSelection;
import at.ioexception.mcaddon.bedrock.behaviour.Teleport;
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
    private Teleport teleport;
    private Anger anger;
    private Despawn despawn;
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

    /** The tick `minecraft:scheduler` may fire again from, which its own delays hold back. */
    public int scheduledFromTick() {
        return scheduledFromTick;
    }

    public void setScheduledFromTick(int tick) {
        scheduledFromTick = tick;
    }

    private int scheduledFromTick;

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
            .add(Attributes.FOLLOW_RANGE, definition.number("minecraft:follow_range", "value", 16))
            .add(Attributes.KNOCKBACK_RESISTANCE,
                definition.number("minecraft:knockback_resistance", "value", 0));

        // 26 moved how far food tempts a mob out of the goal and onto the mob itself, and a goal
        // reading an attribute the type never declared fails the tick it runs in.
        //? if >=26 {
        built = built.add(Attributes.TEMPT_RANGE, 10);
        //?}
        return built;
    }

    /**
     * The components that change the entity itself rather than how it decides what to do.
     *
     * These are read once as the entity comes into the world, because each of them is a fact about
     * the entity that Java holds on the entity rather than on its type.
     */
    private void applyComponents() {
        teleport = Teleport.tryFrom(definition);
        anger = Anger.tryFrom(definition);
        despawn = Despawn.tryFrom(definition);

        if (has("minecraft:fire_immune")) setRemainingFireTicks(0);
        if (has("minecraft:is_hidden_when_invisible")) setInvisible(isInvisible());
        if (has("minecraft:persistent")) setPersistenceRequired();

        setHealth(definition.startingHealth());
        xpReward = definition.experienceOnDeath(xpReward);

        // `color` and `color2` name a dye, which is the number a pack's own render controller reads
        // back as `q.color`. Java has no colour of its own for an arbitrary mob, so it is kept as
        // the property the pack asks about.
        if (has("minecraft:color")) {
            ServerHooks.state().setProperty(getUUID(), "color",
                definition.number("minecraft:color", "value", 0));
        }
        if (has("minecraft:color2")) {
            ServerHooks.state().setProperty(getUUID(), "color2",
                definition.number("minecraft:color2", "value", 0));
        }

        navigate();
        // `home` and `dweller` both say the entity belongs somewhere and should stay near it.
        if (has("minecraft:home")) {
            Platform.setHome(this, blockPosition(),
                (int) Math.max(1, definition.number("minecraft:home", "restriction_radius", 16)));
        } else if (has("minecraft:dweller")) {
            Platform.setHome(this, blockPosition(),
                (int) Math.max(1, definition.number("minecraft:dweller", "restriction_radius", 16)));
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

    /**
     * The routes a pack's navigation component allows, which Java asks the navigator about one by one.
     *
     * Bedrock states the kind of pathfinding in the component's name and the allowances inside it,
     * and an entity that may not float sinks where one that may swims, so the flags decide as much
     * as the name does.
     */
    private void navigate() {
        // A pack writing two of these means the last one, which is the order Bedrock reads them in.
        com.google.gson.JsonObject walking = null;
        for (String component : java.util.List.of(
            "minecraft:navigation.float", "minecraft:navigation.climb", "minecraft:navigation.fly",
            "minecraft:navigation.hover", "minecraft:navigation.swim", "minecraft:navigation.generic",
            "minecraft:navigation.walk")) {
            if (has(component)) walking = definition.component(component);
        }

        net.minecraft.world.entity.ai.navigation.PathNavigation navigation = getNavigation();
        if (!(navigation instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation ground)) {
            return;
        }

        ground.setCanOpenDoors(has("minecraft:annotation.open_door")
            || ServerEntityDefinition.flag(walking, "can_open_doors", false)
            || ServerEntityDefinition.flag(walking, "can_pass_doors", false));
        ground.setCanFloat(has("minecraft:navigation.swim") || has("minecraft:navigation.generic")
            || ServerEntityDefinition.flag(walking, "can_float", false)
            || ServerEntityDefinition.flag(walking, "can_swim", false)
            || ServerEntityDefinition.flag(walking, "can_path_over_water", false)
            || ServerEntityDefinition.flag(walking, "is_amphibious", false));
        ground.setAvoidSun(ServerEntityDefinition.flag(walking, "avoid_sun", false));

        if (!ServerEntityDefinition.flag(walking, "avoid_water", false)) return;

        // The enum naming a kind of ground was renamed in 1.21.
        //? if >=1.21 {
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 8f);
        //?} else {
        /*setPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.WATER, 8f);
        *///?}
    }


    private boolean has(String component) {
        return definition.components().containsKey(component);
    }

    /**
     * Who the pack allows on, and how many of them.
     *
     * Bedrock states the seat count and the families that may use them; Java asks the entity each
     * time somebody tries to get on, so both are read back out of the component rather than kept.
     */
    @Override
    protected boolean canAddPassenger(net.minecraft.world.entity.Entity passenger) {
        if (definition == null || !has("minecraft:rideable")) return false;
        if (getPassengers().size() >= (int) definition.number("minecraft:rideable", "seat_count", 1)) {
            return false;
        }
        return ridableBy(passenger);
    }

    /** A pack listing `family_types` is naming who may ride; listing none allows anyone. */
    private boolean ridableBy(net.minecraft.world.entity.Entity passenger) {
        com.google.gson.JsonObject body = definition.component("minecraft:rideable");
        java.util.List<com.google.gson.JsonElement> families =
            at.ioexception.mcaddon.util.Json.list(body, "family_types");
        if (families.isEmpty()) return true;

        java.util.Set<String> carried = passenger instanceof AddonEntity addon
            ? addon.definition().families() : Platform.tagsOf(passenger);
        return families.stream()
            .filter(com.google.gson.JsonElement::isJsonPrimitive)
            .map(com.google.gson.JsonElement::getAsString)
            .anyMatch(family -> carried.contains(family)
                || (family.equals("player") && passenger instanceof Player));
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

        com.google.gson.JsonObject goal = definition.behaviours().get("minecraft:behavior.pickup_items");
        double range = ServerEntityDefinition.number(goal, "max_dist", 3);
        boolean anything = ServerEntityDefinition.flag(goal, "can_pickup_any_item", false);

        for (net.minecraft.world.entity.item.ItemEntity dropped
            : level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                getBoundingBox().inflate(range))) {
            if (!dropped.isAlive() || dropped.hasPickUpDelay()) continue;
            if (named(goal, "excluded_items", dropped.getItem())) continue;
            if (!anything && !shareable(dropped.getItem())) continue;

            // `singular_pickup` is the pack asking for one of the stack rather than all of it.
            com.google.gson.JsonObject shareables = definition.component("minecraft:shareables");
            net.minecraft.world.item.ItemStack taken = dropped.getItem().copy();
            if (ServerEntityDefinition.flag(shareables, "singular_pickup", false)) {
                taken.setCount(1);
                dropped.getItem().shrink(1);
            }

            setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, taken);
            if (dropped.getItem().isEmpty()) dropped.discard();
            return;
        }
    }

    /**
     * Whether the pack's `shareables` list allows this item, which allows everything when absent.
     *
     * `all_items` is the pack saying the list is a preference rather than a gate, so an entity
     * declaring it takes whatever it finds.
     */
    private boolean shareable(net.minecraft.world.item.ItemStack stack) {
        com.google.gson.JsonObject body = definition.component("minecraft:shareables");
        if (body == null) return true;
        if (ServerEntityDefinition.flag(body, "all_items", false)) return true;
        return named(body, "items", stack);
    }

    /** Whether an item list a component carries names this stack, in either spelling of an entry. */
    private static boolean named(com.google.gson.JsonObject body, String field,
        net.minecraft.world.item.ItemStack stack) {
        java.util.List<com.google.gson.JsonElement> listed =
            at.ioexception.mcaddon.util.Json.list(body, field);
        if (listed.isEmpty()) return false;

        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return listed.stream()
            .map(entry -> entry.isJsonObject()
                ? at.ioexception.mcaddon.util.Json.text(entry.getAsJsonObject(), "item", null)
                : entry.isJsonPrimitive() ? entry.getAsString() : null)
            .filter(java.util.Objects::nonNull)
            .anyMatch(name -> name.equals(id) || ("minecraft:" + name).equals(id));
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
     * A pack states the reach as a cube in blocks and the rate as a time in seconds, so the wait is
     * counted in ticks and the jump is measured against the cube.
     */
    private void teleportAbout() {
        if (teleport == null || level().isClientSide()) return;

        if (teleportDueTick < 0 || tickCount < teleportDueTick) {
            if (teleportDueTick < 0) {
                teleportDueTick = tickCount + teleport.nextDelayTicks(random.nextDouble());
            }
            return;
        }
        teleportDueTick = tickCount + teleport.nextDelayTicks(random.nextDouble());
        if (random.nextDouble() > teleport.chanceAt(level().getMaxLocalRawBrightness(blockPosition()))) {
            return;
        }

        double x = getX() + (random.nextDouble() * 2 - 1) * teleport.reachX();
        double y = getY() + (random.nextDouble() * 2 - 1) * teleport.reachY();
        double z = getZ() + (random.nextDouble() * 2 - 1) * teleport.reachZ();
        Platform.placeAt(this, x, y, z, getYRot(), getXRot());
    }

    /** When the next teleport is due, in ticks of this entity's own age. Negative until armed. */
    private int teleportDueTick = -1;

    /**
     * Counts down a pack's anger and fires the event it wrote for the moment it ends.
     *
     * Bedrock states the anger's length in seconds; an entity whose component is read as a flag is
     * angry for the rest of its life and the pack's `calm_event` never runs.
     */
    private void calmDown() {
        if (anger == null || angryUntilTick < 0) return;
        if (tickCount < angryUntilTick) return;

        angryUntilTick = -1;
        if (anger.calmEvent() != null) {
            EntityEvents.trigger(this, anger.calmEvent(), null, ServerHooks.state());
        }
    }

    /**
     * Makes this entity angry for as long as its pack says, and its neighbours with it.
     *
     * `broadcast_range` is blocks, and the entities it reaches are the ones sharing this definition,
     * which is what Bedrock spreads anger to.
     */
    public void provoke() {
        if (anger == null) return;

        int lifetime = anger.lifetimeTicks(random.nextDouble());
        angryUntilTick = lifetime < 0 ? Integer.MAX_VALUE : tickCount + lifetime;
        if (!anger.broadcastAnger() || !(level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        level.getEntitiesOfClass(AddonEntity.class, getBoundingBox().inflate(anger.broadcastRange()),
            other -> other != this && other.getType() == getType() && !other.isAngry())
            .forEach(AddonEntity::provoke);
    }

    private int angryUntilTick = -1;

    /**
     * Whether a pack's despawn rules would remove this entity.
     *
     * Bedrock states the two distances in blocks and the idle wait in seconds, and asks the same
     * pair of questions Java asks of a vanilla mob, so the rules are answered here rather than left
     * to Java's own defaults.
     */
    @Override
    public void checkDespawn() {
        if (despawn == null || isPersistenceRequired()) {
            super.checkDespawn();
            return;
        }

        Player nearest = level().getNearestPlayer(this, -1);
        if (nearest == null) return;
        if (despawn.despawnsAt(Math.sqrt(distanceToSqr(nearest)), noActionTime, random.nextDouble())) {
            if (despawn.removeChildren()) getPassengers().forEach(net.minecraft.world.entity.Entity::discard);
            discard();
        }
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

    /**
     * Using this entity's own spawn egg on it, which Bedrock takes as asking for another.
     *
     * A pack states the behaviour in `minecraft:spawn_egg_interaction`; what it means is the egg
     * spawns a young one beside the entity rather than a full grown one somewhere else.
     */
    private boolean bredBySpawnEgg(Player player, InteractionHand hand) {
        if (!has("minecraft:spawn_egg_interaction")) return false;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return false;

        net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
        String egg = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
        if (!egg.endsWith("_spawn_egg")) return false;

        net.minecraft.world.entity.Entity child = Platform.createMob(getType(), level);
        if (child == null) return false;

        if (!player.getAbilities().instabuild) held.shrink(1);
        Platform.placeAt(child, getX(), getY(), getZ(), getYRot(), 0f);
        if (child instanceof AddonEntity young) young.setYoung();
        level.addFreshEntity(child);
        return true;
    }

    /**
     * Being handed one of the foods a pack said puts this entity in the mood.
     *
     * Bedrock states breeding as a component naming the items and the offspring; Java writes the
     * same behaviour into `Animal`, which a pack's entity is not one of, so the part of it a pack
     * describes is carried out here.
     */
    private boolean fed(Player player, InteractionHand hand) {
        com.google.gson.JsonObject breedable = definition.component("minecraft:breedable");
        if (breedable == null || !named(breedable, "breed_items", player.getItemInHand(hand))) {
            return false;
        }
        // A pack asking for a healthy parent means an entity at anything less is not tempted at all.
        if (ServerEntityDefinition.flag(breedable, "require_full_health", false)
            && getHealth() < getMaxHealth()) {
            return false;
        }

        if (!player.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
        inLoveUntil = tickCount
            + (int) (ServerEntityDefinition.number(breedable, "breed_cooldown", 30) * 20);
        for (int i = 0; i < 7; i++) {
            level().addParticle(net.minecraft.core.particles.ParticleTypes.HEART,
                getRandomX(1), getRandomY() + 0.5, getRandomZ(1), 0, 0, 0);
        }
        return true;
    }

    /**
     * Finds another of its kind in the mood, and leaves a child where the two of them met.
     *
     * Bedrock names what is born in `minecraft:offspring` and otherwise means another of the same
     * kind, which is what Java does for an animal that has no other answer.
     */
    private void breed() {
        if (inLoveUntil <= tickCount || !(level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        for (AddonEntity other : level.getEntitiesOfClass(AddonEntity.class,
            getBoundingBox().inflate(8), mate -> mate != this && mate.getType() == getType()
                && mate.inLoveUntil > mate.tickCount)) {
            inLoveUntil = 0;
            other.inLoveUntil = 0;

            net.minecraft.world.entity.EntityType<?> born = offspring();
            net.minecraft.world.entity.Entity child = born == null ? null
                : Platform.createMob(born, level);
            if (child == null) return;

            Platform.placeAt(child, getX(), getY(), getZ(), getYRot(), 0f);
            if (child instanceof AddonEntity young) young.setYoung();
            level.addFreshEntity(child);
            EntityEvents.trigger(child, "minecraft:entity_born", this, ServerHooks.state());

            // Bedrock pays for a birth out of the same component that pays for a death.
            int paid = definition.experienceWhenBred();
            if (paid > 0) {
                net.minecraft.world.entity.ExperienceOrb.award(level,
                    new net.minecraft.world.phys.Vec3(getX(), getY(), getZ()), paid);
            }
            return;
        }
    }

    /**
     * What a pack says is born, or another of the same kind when it says nothing.
     *
     * A pack names the child in three places: `minecraft:offspring` names it outright, its
     * `offspring_pairs` name one per pair of parents, and `minecraft:breedable` names one per mate.
     * All three are the same statement, so whichever the pack wrote is taken.
     */
    private net.minecraft.world.entity.EntityType<?> offspring() {
        com.google.gson.JsonObject component = definition.component("minecraft:offspring");
        String named = component == null
            ? null : at.ioexception.mcaddon.util.Json.text(component, "identifier", null);

        com.google.gson.JsonObject breedable = definition.component("minecraft:breedable");
        if (named == null) named = firstNamed(component, "offspring_pairs", "baby");
        if (named == null) named = firstNamed(breedable, "breeds_with", "baby_type");
        return named == null ? getType() : AddonEntities.typeOf(named.split("<")[0]);
    }

    /** The first entity a list of entries names under the given field. */
    private static String firstNamed(com.google.gson.JsonObject body, String list, String field) {
        return at.ioexception.mcaddon.util.Json.list(body, list).stream()
            .filter(com.google.gson.JsonElement::isJsonObject)
            .map(entry -> at.ioexception.mcaddon.util.Json.text(entry.getAsJsonObject(), field, null))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /**
     * Lying down at night, which is what `minecraft:behavior.sleep` describes.
     *
     * Java writes sleeping into the villager alone, tied to a bed it has claimed. A pack's entity
     * has no bed, so what carries across is the part a player sees: it stops where it is until the
     * sun is up again.
     */
    private void rest() {
        if (!definition.behaviours().containsKey("minecraft:behavior.sleep")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        long time = Platform.dayTime(level) % 24000L;
        boolean night = time >= 13000L && time < 23000L;
        setPose(night ? net.minecraft.world.entity.Pose.SLEEPING : net.minecraft.world.entity.Pose.STANDING);
        if (night) {
            getNavigation().stop();
            setDeltaMovement(0, getDeltaMovement().y, 0);
        }
    }

    /**
     * Drinking away what is hurting it, which both drinking behaviours amount to.
     *
     * Bedrock's milk clears every effect and its potion drinking picks one to apply; what a pack
     * uses either for is an entity that does not stay poisoned, so the harmful ones are cleared.
     */
    private void drink() {
        boolean drinks = definition.behaviours().containsKey("minecraft:behavior.drink_milk")
            || definition.behaviours().containsKey("minecraft:behavior.drink_potion");
        if (!drinks || getActiveEffects().isEmpty()) return;

        getActiveEffects().stream()
            .filter(effect -> !Platform.beneficial(effect))
            .toList()
            .forEach(effect -> removeEffect(effect.getEffect()));
    }

    /** How long this entity stays willing to breed, counted in its own ticks. */
    private int inLoveUntil;

    /** Marks a newly born one as a baby, which a pack's render controller reads as `q.is_baby`. */
    private void setYoung() {
        ServerHooks.state().setProperty(getUUID(), "is_baby", true);
    }

    /** Angry until the countdown the pack's `minecraft:angry` component set has run out. */
    public boolean isAngry() {
        return angryUntilTick > tickCount;
    }

    /**
     * Whether a lead can hold this entity.
     *
     * A pack saying so is describing what Java already allows for a mob, so the component only has
     * to be honoured when it says the opposite.
     */
    /**
     * Whether a portal will take this entity anywhere.
     *
     * `minecraft:dimension_bound` says it belongs where it is, which Java expresses by refusing the
     * portal rather than by binding the entity to a dimension. The question was renamed in 1.21.
     */
    //? if >=1.21 {
    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return !bound() && super.canUsePortal(allowPassengers);
    }
    //?} else {
    /*@Override
    public boolean canChangeDimensions() {
        return !bound() && super.canChangeDimensions();
    }
    *///?}

    private boolean bound() {
        return definition != null && definition.components().containsKey("minecraft:dimension_bound");
    }

    /**
     * What a lead may tie this entity to.
     *
     * Bedrock lists the kinds in `minecraft:leashable_to`; Java asks nothing, so a pack naming a
     * list is honoured by refusing anything outside it.
     */
    public boolean canBeLeashedTo(net.minecraft.world.entity.Entity holder) {
        com.google.gson.JsonObject component = definition == null
            ? null : definition.components().get("minecraft:leashable_to");
        com.google.gson.JsonArray kinds = component == null
            ? null : component.getAsJsonArray("leash_entity_type_filters");
        if (kinds == null) return true;

        String id = BuiltInRegistries.ENTITY_TYPE.getKey(holder.getType()).toString();
        for (com.google.gson.JsonElement kind : kinds) {
            if (kind.isJsonPrimitive() && kind.getAsString().equals(id)) return true;
        }
        return false;
    }

    /**
     * Fires the events a pack wrote for a lead going on and coming off.
     *
     * Bedrock writes each as a trigger, which is the bare event name or the object carrying it, and
     * a reader taking only one of the two forms finds nothing in Mojang's own entities.
     */
    private void leashChanged(boolean leashed) {
        if (leashed == wasLeashed) return;
        wasLeashed = leashed;

        com.google.gson.JsonObject body = definition.component("minecraft:leashable");
        String event = leashed
            ? ServerEntityDefinition.event(body, "on_leash")
            : ServerEntityDefinition.event(body, "on_unleash");
        if (event != null) EntityEvents.trigger(this, event, null, ServerHooks.state());
    }

    private boolean wasLeashed;

    /**
     * The goals a pack declared, at the priority and the speed it asked for.
     *
     * Bedrock and Java both order goals by a number where lower wins, and both state a goal's speed
     * as a multiplier of the entity's own movement speed, so each carries straight across. A goal
     * read without them runs in the order the components happened to be written in, at whatever
     * speed the mod chose, which is what makes a panicking mob amble and a strolling one sprint.
     */
    private void addGoals() {
        definition.behaviours().forEach(this::addGoal);
    }

    private void addGoal(String behaviour, com.google.gson.JsonObject body) {
        int priority = (int) ServerEntityDefinition.number(body, "priority", 0);
        double speed = ServerEntityDefinition.number(body, "speed_multiplier", 1);

        switch (behaviour) {
            case "minecraft:behavior.float" -> goalSelector.addGoal(priority, new FloatGoal(this));
            case "minecraft:behavior.panic", "minecraft:behavior.panic_on_fire" ->
                goalSelector.addGoal(priority, new PanicGoal(this, speed));
            case "minecraft:behavior.random_stroll", "minecraft:behavior.move_outdoors",
                 "minecraft:behavior.explore_outskirts" -> {
                // `interval` is the one in N chance of choosing the goal, which Java counts the same way.
                WaterAvoidingRandomStrollGoal stroll = new WaterAvoidingRandomStrollGoal(this, speed);
                stroll.setInterval((int) ServerEntityDefinition.number(body, "interval", 120));
                goalSelector.addGoal(priority, stroll);
            }
            case "minecraft:behavior.look_at_player", "minecraft:behavior.look_at_trading_player" ->
                goalSelector.addGoal(priority, new LookAtPlayerGoal(this, Player.class,
                    (float) ServerEntityDefinition.number(body, "look_distance", 8),
                    (float) ServerEntityDefinition.number(body, "probability", 0.02)));
            case "minecraft:behavior.random_look_around" ->
                goalSelector.addGoal(priority, new RandomLookAroundGoal(this));

            // Bedrock names the kind of wandering; Java has one stroll goal and a speed.
            case "minecraft:behavior.move_indoors",
                 "minecraft:behavior.move_towards_dwelling_restriction",
                 "minecraft:behavior.move_towards_home_restriction",
                 "minecraft:behavior.move_towards_restriction" ->
                goalSelector.addGoal(priority, new MoveTowardsRestrictionGoal(this, speed));
            case "minecraft:behavior.mount_pathing", "minecraft:behavior.follow_owner",
                 "minecraft:behavior.play", "minecraft:behavior.stroll_towards_village",
                 "minecraft:behavior.breed", "minecraft:behavior.follow_parent",
                 "minecraft:behavior.share_items" ->
                goalSelector.addGoal(priority, new WaterAvoidingRandomStrollGoal(this, speed));

            // Hiding and playing are both a Bedrock villager moving somewhere for a reason Java
            // writes into its own villager. What carries across is the moving.
            case "minecraft:behavior.avoid_mob_type", "minecraft:behavior.hide",
                 "minecraft:behavior.avoid_block" -> {
                TargetSelection avoided = TargetSelection.from(body, Monster.class, 8);
                goalSelector.addGoal(priority, new AvoidEntityGoal<>(this, avoided.kind(),
                    (float) avoided.maxDistance(),
                    ServerEntityDefinition.number(body, "walk_speed_modifier", 1),
                    ServerEntityDefinition.number(body, "sprint_speed_modifier", 1.2)));
            }

            case "minecraft:behavior.tempt" -> goalSelector.addGoal(priority,
                Platform.temptGoal(this, speed, tempting(body)));
            case "minecraft:behavior.open_door", "minecraft:annotation.open_door" ->
                goalSelector.addGoal(priority, new OpenDoorGoal(this,
                    ServerEntityDefinition.flag(body, "close_door_after", true)));

            case "minecraft:behavior.melee_attack", "minecraft:behavior.melee_box_attack" ->
                goalSelector.addGoal(priority,
                    new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(this, speed,
                        ServerEntityDefinition.flag(body, "track_target", false)));
            case "minecraft:behavior.nearest_attackable_target" -> {
                TargetSelection hunted = TargetSelection.from(body, Player.class,
                    ServerEntityDefinition.number(body, "within_radius", 0));
                targetSelector.addGoal(priority,
                    new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                        this, hunted.kind(),
                        (int) ServerEntityDefinition.number(body, "scan_interval", 10),
                        ServerEntityDefinition.flag(body, "must_see", false),
                        ServerEntityDefinition.flag(body, "must_reach", false), null));
            }
            case "minecraft:behavior.hurt_by_target" -> {
                net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal hurt =
                    new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this);
                if (ServerEntityDefinition.flag(body, "alert_same_type", false)) hurt.setAlertOthers();
                targetSelector.addGoal(priority, hurt);
            }

            // Each of these is carried out on this entity's own clock rather than as a Java goal,
            // so the goal selector has nothing to add and the component is still honoured.
            case "minecraft:behavior.pickup_items", "minecraft:behavior.sleep",
                 "minecraft:behavior.drink_milk", "minecraft:behavior.drink_potion",
                 "minecraft:behavior.trade_with_player", "minecraft:behavior.trade_interest" -> { }

            default -> AddonEntities.unsupported(behaviour);
        }
    }

    /** What a `minecraft:behavior.tempt` goal says tempts this entity, by item id. */
    private static java.util.Set<String> tempting(com.google.gson.JsonObject body) {
        java.util.Set<String> items = new java.util.LinkedHashSet<>();
        for (com.google.gson.JsonElement item
            : at.ioexception.mcaddon.util.Json.list(body, "items")) {
            if (item.isJsonPrimitive()) {
                items.add(item.getAsString());
            } else if (item.isJsonObject()) {
                String named = at.ioexception.mcaddon.util.Json.text(item.getAsJsonObject(), "item", null);
                if (named != null) items.add(named);
            }
        }
        return items;
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
            breed();
            rest();
            drink();
            calmDown();
            leashChanged(isLeashed());
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
        // `crouching_skip_interact` is a rideable entity asking to be mounted rather than used when
        // the player is crouching, which is the opposite of what Java does for its own mounts.
        com.google.gson.JsonObject rideable = definition == null
            ? null : definition.component("minecraft:rideable");
        if (rideable != null && player.isCrouching()
            && ServerEntityDefinition.flag(rideable, "crouching_skip_interact", true)) {
            return super.mobInteract(player, hand);
        }

        if (!isClientSide() && bredBySpawnEgg(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && fed(player, hand)) return InteractionResult.SUCCESS;
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
