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

        // `color` and `color2` name a dye, which is the number a pack's own render controller reads
        // back as `q.color`. Java has no colour of its own for an arbitrary mob, so it is kept as
        // the property the pack asks about.
        for (String named : new String[] {"minecraft:color", "minecraft:color2"}) {
            if (!has(named)) continue;

            ServerHooks.state().setProperty(getUUID(),
                named.substring("minecraft:".length()), number(definition, named, "value", 0));
        }
        if (has("minecraft:is_hidden_when_invisible")) setInvisible(isInvisible());

        // Bedrock names the kind of pathfinding; Java asks whether the navigator may take each route.
        net.minecraft.world.entity.ai.navigation.PathNavigation navigation = getNavigation();
        if (navigation instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation ground) {
            ground.setCanOpenDoors(has("minecraft:annotation.open_door"));
            ground.setCanFloat(has("minecraft:navigation.swim") || has("minecraft:navigation.generic"));
        }
        // `home` and `dweller` both say the entity belongs somewhere and should stay near it.
        if (has("minecraft:home") || has("minecraft:dweller")) {
            int radius = (int) number(definition,
                has("minecraft:home") ? "minecraft:home" : "minecraft:dweller",
                "restriction_radius", 16);
            Platform.setHome(this, blockPosition(), Math.max(1, radius));
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
        com.google.gson.JsonObject breedable = definition.components().get("minecraft:breedable");
        if (breedable == null || !breedsWith(breedable, player.getItemInHand(hand))) return false;

        if (!player.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
        inLoveUntil = tickCount + 600;
        for (int i = 0; i < 7; i++) {
            level().addParticle(net.minecraft.core.particles.ParticleTypes.HEART,
                getRandomX(1), getRandomY() + 0.5, getRandomZ(1), 0, 0, 0);
        }
        return true;
    }

    /** Whether one of the items the pack named as breeding food is what was offered. */
    private static boolean breedsWith(com.google.gson.JsonObject breedable,
        net.minecraft.world.item.ItemStack offered) {
        com.google.gson.JsonArray items = breedable.getAsJsonArray("breed_items");
        if (items == null || offered.isEmpty()) return false;

        String id = BuiltInRegistries.ITEM.getKey(offered.getItem()).toString();
        for (com.google.gson.JsonElement item : items) {
            if (!item.isJsonPrimitive()) continue;

            String named = item.getAsString();
            if (named.equals(id) || ("minecraft:" + named).equals(id)) return true;
        }
        return false;
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
            return;
        }
    }

    /** What a pack says is born, or another of the same kind when it says nothing. */
    private net.minecraft.world.entity.EntityType<?> offspring() {
        com.google.gson.JsonObject component = definition.components().get("minecraft:offspring");
        String named = component == null ? null
            : at.ioexception.mcaddon.util.Json.text(component, "identifier", null);
        return named == null ? getType() : AddonEntities.typeOf(named);
    }

    /**
     * Lying down at night, which is what `minecraft:behavior.sleep` describes.
     *
     * Java writes sleeping into the villager alone, tied to a bed it has claimed. A pack's entity
     * has no bed, so what carries across is the part a player sees: it stops where it is until the
     * sun is up again.
     */
    private void rest() {
        if (!definition.behaviours().contains("minecraft:behavior.sleep")) return;
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
        boolean drinks = definition.behaviours().contains("minecraft:behavior.drink_milk")
            || definition.behaviours().contains("minecraft:behavior.drink_potion");
        if (!drinks || getActiveEffects().isEmpty()) return;

        getActiveEffects().stream()
            .filter(effect -> !Platform.beneficial(effect))
            .toList()
            .forEach(effect -> removeEffect(effect.getEffect()));
    }

    /**
     * Taking the flower a golem is holding out, which is what `behavior.take_flower` waits for.
     *
     * Java's golem offers one and its own villager accepts; a pack's entity has to be walked over
     * and made to take it, which is the whole of what the behaviour describes.
     */
    private void takeOfferedFlower() {
        if (!definition.behaviours().contains("minecraft:behavior.take_flower")) return;

        net.minecraft.world.entity.Entity golem = Platform.offeredFlower(this, 8);
        if (golem == null) return;

        if (distanceTo(golem) > 2) {
            getNavigation().moveTo(golem, 0.6);
            return;
        }
        Platform.takeFlower(golem);
        setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POPPY));
    }

    /** Ticks of celebrating still owed, and whether the raid underfoot was already won last tick. */
    private int celebrateFor;
    private boolean sawRaidWon;

    /**
     * Celebrating a raid that has just been beaten.
     *
     * Bedrock gives the component a duration and the entity jumps for that long. A won raid stands
     * for a while after it is won, so the countdown starts on the tick the win appears and not on
     * every tick the win is still there.
     */
    private void celebrate() {
        if (!definition.behaviours().contains("minecraft:behavior.celebrate_survive")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        boolean won = Platform.raidWon(level, blockPosition());
        if (won && !sawRaidWon) {
            celebrateFor = (int) (number(definition, "minecraft:behavior.celebrate_survive",
                "duration", 30) * 20);
        }
        sawRaidWon = won;
        if (celebrateFor <= 0) return;
        celebrateFor--;

        getNavigation().stop();
        if (onGround()) setDeltaMovement(getDeltaMovement().add(0, 0.42, 0));
        if (celebrateFor % 20 == 0) {
            playSound(SoundEvents.VILLAGER_CELEBRATE, getSoundVolume(), getVoicePitch());
        }
    }

    /**
     * Rising while something is tied to it, which is what a balloon does in Bedrock.
     *
     * Java has no balloon, and it has the lead: a thing tied to a mob at a distance. A pack marking
     * an entity balloonable is describing one that floats once something is attached, so the force
     * and the mass it gives are applied while a lead holds it, and the lead breaks past the distance
     * the pack allows.
     */
    private void rise() {
        if (!has("minecraft:balloonable") || getLeashHolder() == null) return;

        double force = number(definition, "minecraft:balloonable", "force", 0.05);
        double mass = Math.max(0.01, number(definition, "minecraft:balloonable", "mass", 1));
        double liftUntil = number(definition, "minecraft:balloonable", "max_distance", 10);

        if (distanceTo(getLeashHolder()) > liftUntil) {
            Platform.dropLeash(this);
            return;
        }
        setDeltaMovement(getDeltaMovement().add(0, force / mass, 0));
        fallDistance = 0;
    }

    /**
     * Going away again the way Bedrock's own trader does.
     *
     * `managed_wandering_trader` hands the entity to a manager that gives it a lifetime; Java has no
     * such manager for anything but its own trader, so the lifetime is kept here and the entity
     * leaves when it runs out.
     */
    private void manageAsTrader() {
        if (!has("minecraft:managed_wandering_trader") || isPersistenceRequired()) return;

        // Bedrock's own manager gives a trader two game days before it wanders off.
        if (tickCount > 48000) discard();
    }

    /** How long this entity stays willing to breed, counted in its own ticks. */
    private int inLoveUntil;

    /** Marks a newly born one as a baby, which a pack's render controller reads as `q.is_baby`. */
    private void setYoung() {
        ServerHooks.state().setProperty(getUUID(), "is_baby", true);
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
                     "minecraft:behavior.play", "minecraft:behavior.stroll_towards_village" ->
                    goalSelector.addGoal(priority, new WaterAvoidingRandomStrollGoal(this, 0.6));

                // Hiding and playing are both a Bedrock villager moving somewhere for a reason Java
                // writes into its own villager. What carries across is the moving.
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

                // A behaviour this switch does not name may still be one the entity carries out on
                // its own clock rather than through a goal, so the register decides, not the switch.
                default -> {
                    if (!AddonEntities.implemented(behaviour)) AddonEntities.unsupported(behaviour);
                }
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
            breed();
            rest();
            drink();
            takeOfferedFlower();
            celebrate();
            rise();
            manageAsTrader();
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
