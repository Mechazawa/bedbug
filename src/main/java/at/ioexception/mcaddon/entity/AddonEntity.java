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
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.BreathAirGoal;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.entity.ai.goal.FleeSunGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowMobGoal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.MoveBackToVillageGoal;
import net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomSwimmingGoal;
import net.minecraft.world.entity.ai.goal.RestrictSunGoal;
import net.minecraft.world.entity.ai.goal.StrollThroughVillageGoal;
import net.minecraft.world.entity.ai.goal.TryFindWaterGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
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
public class AddonEntity extends PathfinderMob
    implements Merchant, net.minecraft.world.entity.monster.RangedAttackMob,
        net.minecraft.world.entity.OwnableEntity {
    private final ServerEntityDefinition definition;
    private final java.util.Set<String> activeGroups = new java.util.LinkedHashSet<>();
    private int timerDueTick = -1;
    private int admireUntilTick = -1;
    private String lastBlockInside;
    private int celebrateUntilTick = -1;
    private int boostedUntilTick = -1;
    private Player tradingPlayer;
    private MerchantOffers offers;
    private int tradeExperience;
    private Identifier lootTable;
    private Age age;
    private Anger anger;
    private net.minecraft.server.level.ServerBossEvent bossBar;
    private net.minecraft.world.SimpleContainer carried;

    public AddonEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.definition = AddonEntities.definitionOf(type);
        if (definition != null) {
            age = Age.tryFrom(definition);
            anger = Anger.tryFrom(definition);
            addGoals();
            applyMoveControl();
            applyComponents();
        }
    }

    /**
     * The loot table this entity drops, which a pack names in `minecraft:loot`.
     *
     * A component group can bring a different one in, so it belongs to the entity rather than to its
     * type. Null leaves the entity dropping whatever Java would have it drop.
     */
    public void setLootTable(Identifier table) {
        lootTable = table;
    }

    /**
     * What the entity leaves behind, asked for where each version lets it be answered.
     *
     * Before 26 a mob is asked for its default table and hands one back. From 26 that question is
     * final on `Mob` and answered by the entity's type, so the drop itself is taken over instead.
     */
    //? if >=26 {
    @Override
    protected void dropFromLootTable(net.minecraft.server.level.ServerLevel level,
        net.minecraft.world.damagesource.DamageSource source, boolean playerKill) {
        if (lootTable == null) {
            super.dropFromLootTable(level, source, playerKill);
            return;
        }
        dropFromLootTable(level, source, playerKill, Platform.lootTableKey(lootTable));
    }
    //?} else if >=1.21 {
    /*@Override
    protected net.minecraft.resources.ResourceKey<net.minecraft.world.level.storage.loot.LootTable>
        getDefaultLootTable() {
        return lootTable == null ? super.getDefaultLootTable() : Platform.lootTableKey(lootTable);
    }
    *///?} else {
    /*@Override
    protected Identifier getDefaultLootTable() {
        return lootTable == null ? super.getDefaultLootTable() : lootTable;
    }
    *///?}

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
     * When admiring what the entity is holding ends, in ticks of its own age. Negative until set.
     *
     * Bedrock splits admiring and bartering over two behaviours, and the second waits for the first
     * to finish. A goal cannot see another goal, so the entity is where the two meet.
     */
    public int admireUntilTick() {
        return admireUntilTick;
    }

    public void setAdmireUntilTick(int tick) {
        admireUntilTick = tick;
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
                number(definition, "minecraft:knockback_resistance", "value", 0))
            .add(Attributes.ATTACK_DAMAGE, attackDamage(definition))
            .add(Attributes.FLYING_SPEED, number(definition, "minecraft:flying_speed", "value", 0.4))
            .add(Attributes.LUCK, number(definition, "minecraft:luck", "value", 0))
            .add(Attributes.JUMP_STRENGTH,
                number(definition, "minecraft:horse.jump_strength", "value", 0.42));

        // 26 moved how far food tempts a mob out of the goal and onto the mob itself, and a goal
        // reading an attribute the type never declared fails the tick it runs in.
        //? if >=26 {
        built = built.add(Attributes.TEMPT_RANGE, 10);
        //?}
        // From 1.21 how high a mob steps is an attribute; before that it is a field on the entity.
        //? if >=1.21 {
        built = built.add(Attributes.STEP_HEIGHT,
            number(definition, "minecraft:variable_max_auto_step", "base_value", 0.6));
        //?}
        return built;
    }

    /**
     * How hard the entity hits, from either component Bedrock lets a pack say it with.
     *
     * `minecraft:attack` writes the damage as a number or as a low and high pair, and a Java
     * attribute holds one number, so a range becomes its midpoint.
     */
    private static double attackDamage(ServerEntityDefinition definition) {
        com.google.gson.JsonObject attack = definition.components().get("minecraft:attack");
        if (attack == null) {
            return number(definition, "minecraft:attack_damage", "value", 1);
        }

        com.google.gson.JsonElement damage = attack.get("damage");
        if (damage != null && damage.isJsonArray() && damage.getAsJsonArray().size() >= 2) {
            return (damage.getAsJsonArray().get(0).getAsDouble()
                + damage.getAsJsonArray().get(1).getAsDouble()) / 2;
        }
        return damage != null && damage.isJsonPrimitive() ? damage.getAsDouble() : 1;
    }

    /** A number a component carries, or the fallback when the pack declared neither. */
    /**
     * The number a component holds under one field.
     *
     * Bedrock writes a field that varies per entity as a range rather than a number, and Java fixes
     * one value on the type, so the middle of the range is what every entity of that type gets.
     */
    private static double number(ServerEntityDefinition definition, String component, String field,
        double fallback) {
        com.google.gson.JsonObject body = definition.components().get(component);
        com.google.gson.JsonElement value = body == null ? null : body.get(field);
        if (value == null) return fallback;
        if (value.isJsonPrimitive()) return value.getAsDouble();
        if (!value.isJsonObject()) return fallback;

        com.google.gson.JsonObject range = value.getAsJsonObject();
        if (!range.has("range_min") || !range.has("range_max")) return fallback;
        return (range.get("range_min").getAsDouble() + range.get("range_max").getAsDouble()) / 2;
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
        if (declares("minecraft:absorption")) {
            setAbsorptionAmount((float) number(definition, "minecraft:absorption", "value", 0));
        }

        // `color` and `color2` name a dye, which is the number a pack's own render controller reads
        // back as `q.color`. Java has no colour of its own for an arbitrary mob, so it is kept as
        // the property the pack asks about.
        for (String named : new String[] {"minecraft:color", "minecraft:color2"}) {
            if (!declares(named)) continue;

            ServerHooks.state().setProperty(getUUID(),
                named.substring("minecraft:".length()), number(definition, named, "value", 0));
        }
        if (declares("minecraft:is_hidden_when_invisible")) setInvisible(isInvisible());

        for (String marker : MARKERS) {
            if (!declares(marker)) continue;

            ServerHooks.state().setProperty(getUUID(),
                marker.substring("minecraft:".length()), true);
        }

        // Bedrock names the kind of pathfinding; Java asks whether the navigator may take each route.
        PathNavigation navigation = getNavigation();
        if (navigation instanceof GroundPathNavigation ground) {
            ground.setCanOpenDoors(declares("minecraft:annotation.open_door"));
            ground.setCanFloat(declares("minecraft:navigation.swim") || declares("minecraft:navigation.generic"));
        }
        // `home` and `dweller` both say the entity belongs somewhere and should stay near it.
        if (declares("minecraft:home") || declares("minecraft:dweller")) {
            int radius = (int) number(definition,
                declares("minecraft:home") ? "minecraft:home" : "minecraft:dweller",
                "restriction_radius", 16);
            Platform.setHome(this, blockPosition(), Math.max(1, radius));
        }

        String table = text("minecraft:loot", "table");
        if (table != null) lootTable = AddonEntities.lootTable(getType(), table);

        if (declares("minecraft:inventory")) {
            carried = new net.minecraft.world.SimpleContainer(
                Math.max(1, (int) number(definition, "minecraft:inventory", "inventory_size", 5)));
        } else if (declares("minecraft:strength")) {
            // A llama's strength is how much it carries, three slots per point in both editions.
            carried = new net.minecraft.world.SimpleContainer(
                Math.max(1, (int) number(definition, "minecraft:strength", "value", 1) * 3));
        }

        //? if <1.21 {
        /*if (declares("minecraft:variable_max_auto_step")) {
            setMaxUpStep((float) number(definition, "minecraft:variable_max_auto_step", "base_value", 0.6));
        }
        *///?}

        wearWhatThePackRolled();
        publishWhatItIs();
        if (declares("minecraft:boss")) {
            bossBar = Platform.bossBar(getDisplayName());
            bossBar.setDarkenScreen(number(definition, "minecraft:boss", "should_darken_sky", 0) != 0);
        }
        // What a pack's render controller branches on, so a young entity is drawn as one.
        if (age != null && age.baby()) ServerHooks.state().setProperty(getUUID(), "is_baby", true);

        if (declares("minecraft:default_look_angle")) {
            float angle = (float) number(definition, "minecraft:default_look_angle", "value", 0);
            setYHeadRot(angle);
            setYBodyRot(angle);
        }

        if (declares("minecraft:preferred_path")) {
            // The enum naming a kind of ground was renamed in 1.21.
            //? if >=1.21 {
            setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 8f);
            //?} else {
            /*setPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.WATER, 8f);
            *///?}
        }
    }

    /**
     * The components that are a fact about the entity and nothing else.
     *
     * Each is a switch a pack sets on the entity and then reads back from its render controllers,
     * its filters and its Molang, so carrying it as a property the server syncs is the whole of
     * what Bedrock does with it. Java has no state of its own for any of them.
     */
    private static final String[] MARKERS = {
        "minecraft:is_charged", "minecraft:is_chested", "minecraft:is_saddled",
        "minecraft:is_ignited", "minecraft:is_illager_captain", "minecraft:is_pregnant",
        "minecraft:is_shaking", "minecraft:is_stackable", "minecraft:is_stunned",
        "minecraft:is_tamed", "minecraft:is_sheared", "minecraft:is_dyeable",
    };

    public boolean declares(String component) {
        return definition.components().containsKey(component);
    }

    /** A string a component carries, or null when the pack declared neither. */
    private String text(String component, String field) {
        com.google.gson.JsonObject body = definition.components().get(component);
        return body == null ? null : at.ioexception.mcaddon.util.Json.text(body, field, null);
    }

    /**
     * The body of a component wherever the pack declared it, or an empty one when it carries none.
     *
     * A behaviour is as real inside a component group as it is at the top, and a caller reading a
     * list or a string out of one should not have to know which of the two it came from.
     */
    public com.google.gson.JsonObject componentBody(String component) {
        com.google.gson.JsonObject declared = definition.components().get(component);
        if (declared != null) return declared;

        return definition.componentGroups().values().stream()
            .filter(group -> group.has(component) && group.get(component).isJsonObject())
            .map(group -> group.getAsJsonObject(component))
            .findFirst()
            .orElseGet(com.google.gson.JsonObject::new);
    }

    /**
     * The navigator the pack's `minecraft:navigation.*` component names.
     *
     * Java builds this inside the mob constructor, before the definition field is assigned, so the
     * components are reached through the registry by type instead. An entity whose pack asked to
     * fly and got a ground navigator walks under whatever it was supposed to fly over, which is
     * why this is chosen per entity rather than left at the default.
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        ServerEntityDefinition declared = AddonEntities.definitionOf(getType());
        if (declared == null) return super.createNavigation(level);

        java.util.Set<String> components = declared.components().keySet();
        if (components.contains("minecraft:navigation.fly")
            || components.contains("minecraft:navigation.hover")
            || components.contains("minecraft:navigation.float")
            || components.contains("minecraft:can_fly")) {
            return new FlyingPathNavigation(this, level);
        }
        if (components.contains("minecraft:navigation.swim")) {
            return new WaterBoundPathNavigation(this, level);
        }
        if (components.contains("minecraft:navigation.climb")
            || components.contains("minecraft:can_climb")
            || components.contains("minecraft:block_climber")) {
            return new WallClimberNavigation(this, level);
        }
        return super.createNavigation(level);
    }

    /**
     * Holds the entity up when its pack described something that moves through the air.
     *
     * Java's default move control pulls a mob back to the ground between steps, so a flying
     * navigator on its own produces a path the mob cannot follow. Hovering differs from flying only
     * in whether the entity stops in place when it has nowhere to be.
     */
    private void applyMoveControl() {
        boolean hovers = declares("minecraft:movement.hover");
        if (hovers || declares("minecraft:movement.fly") || declares("minecraft:movement.glide")) {
            moveControl = new net.minecraft.world.entity.ai.control.FlyingMoveControl(this, 20, hovers);
            return;
        }

        // Swimming that banks into its turns, which is what Bedrock's dolphin and its sway both are.
        if (declares("minecraft:movement.dolphin") || declares("minecraft:movement.sway")) {
            moveControl = new net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl(
                this, 85, 10, 0.02f, 0.1f, true);
            return;
        }

        if (declares("minecraft:movement.jump") || declares("minecraft:movement.skip")) {
            moveControl = new HopMoveControl(this);
        }
    }

    /**
     * How many riders the pack allows, which is what makes an entity rideable at all.
     *
     * Bedrock states the seats and Java asks the entity each time somebody tries to get on, so the
     * count is read back out of the component rather than kept. `minecraft:behavior.player_ride_tamed`
     * is the pack saying a player may only ride once the entity belongs to somebody, which is the one
     * thing that behaviour decides, so it is answered here rather than as a goal.
     */
    @Override
    protected boolean canAddPassenger(net.minecraft.world.entity.Entity passenger) {
        if (definition == null || !declares("minecraft:rideable")) return false;
        if (passenger instanceof Player && ownerUuid() == null
            && definition.behaviours().contains("minecraft:behavior.player_ride_tamed")) {
            return false;
        }
        return getPassengers().size() < (int) number(definition, "minecraft:rideable", "seat_count", 1);
    }

    /**
     * Who is steering, which is what turns a passenger into a driver.
     *
     * Bedrock splits the ways an entity can be steered across four components, and Java asks one
     * question: which passenger, if any, the entity takes its movement from. A pack naming any of
     * the four is describing a mount a player drives, so the first player aboard is the driver.
     */
    @Override
    public net.minecraft.world.entity.LivingEntity getControllingPassenger() {
        if (definition == null || !steerable()) return super.getControllingPassenger();

        return getPassengers().stream()
            .filter(Player.class::isInstance)
            .map(Player.class::cast)
            .filter(this::holdsTheReins)
            .findFirst()
            .map(net.minecraft.world.entity.LivingEntity.class::cast)
            .orElseGet(super::getControllingPassenger);
    }

    private boolean steerable() {
        return declares("minecraft:input_ground_controlled")
            || declares("minecraft:free_camera_controlled")
            || declares("minecraft:item_controllable")
            || definition.behaviours().contains("minecraft:behavior.controlled_by_player");
    }

    /**
     * Whether the rider is holding what `minecraft:item_controllable` says steers this entity.
     *
     * A pack naming no such component is describing a mount steered by sitting on it, which is what
     * every rider does.
     */
    private boolean holdsTheReins(Player rider) {
        if (!declares("minecraft:item_controllable")) return true;

        String held = BuiltInRegistries.ITEM.getKey(rider.getMainHandItem().getItem()).toString();
        for (com.google.gson.JsonElement item : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:item_controllable"), "control_items")) {
            if (item.isJsonPrimitive() && names(item.getAsString(), held)) return true;
        }
        return false;
    }

    /**
     * Where the rider is asking the entity to go.
     *
     * `minecraft:input_ground_controlled` is the pair of axes a player walks with; a free camera adds
     * the rise and fall, which Bedrock scales apart from the rest, so the sideways and backwards
     * modifiers are read off the component rather than left at Java's own.
     */
    @Override
    protected net.minecraft.world.phys.Vec3 getRiddenInput(Player rider,
        net.minecraft.world.phys.Vec3 asked) {
        if (definition == null || !steerable()) return super.getRiddenInput(rider, asked);

        double sideways = asked.x * number(definition, "minecraft:free_camera_controlled",
            "strafe_speed_modifier", 0.4) * 2.5;
        double forward = asked.z < 0
            ? asked.z * number(definition, "minecraft:free_camera_controlled",
                "backwards_movement_modifier", 0.5) * 2
            : asked.z;
        double vertical = declares("minecraft:vertical_movement_action")
            && ((at.ioexception.mcaddon.mixin.JumpingAccessor) rider).mcaddon$jumping()
            ? number(definition, "minecraft:vertical_movement_action", "vertical_velocity", 0.5) : 0;
        return new net.minecraft.world.phys.Vec3(sideways, vertical, forward);
    }

    /** How fast a driven mount goes, which Bedrock scales off the entity's own movement speed. */
    @Override
    protected float getRiddenSpeed(Player rider) {
        if (definition == null
            || !definition.behaviours().contains("minecraft:behavior.controlled_by_player")) {
            return super.getRiddenSpeed(rider);
        }
        return (float) (getAttributeValue(Attributes.MOVEMENT_SPEED) * number(definition,
            "minecraft:behavior.controlled_by_player", "mount_speed_multiplier", 1));
    }

    /** `minecraft:underwater_mount_breathing` keeps a rider's air up for as long as they stay on. */
    private void breatheForRider() {
        if (!declares("minecraft:underwater_mount_breathing")) return;

        getPassengers().stream()
            .filter(net.minecraft.world.entity.LivingEntity.class::isInstance)
            .map(net.minecraft.world.entity.LivingEntity.class::cast)
            .forEach(rider -> rider.setAirSupply(rider.getMaxAirSupply()));
    }

    /**
     * `minecraft:tick_world` keeps the world around the entity awake whether a player is there or not.
     *
     * Bedrock hands the entity a ticking area that follows it. Java ticks a chunk because somebody
     * holds a ticket on it, so one is held on whichever chunk the entity is standing in and let go
     * of again as it walks out.
     */
    private void tickTheWorldAround() {
        if (!declares("minecraft:tick_world")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        net.minecraft.world.level.ChunkPos standing = chunkPosition();
        if (standing.equals(held)) return;

        if (held != null) Platform.releaseChunk(level, held);
        Platform.holdChunk(level, standing);
        held = standing;
    }

    private net.minecraft.world.level.ChunkPos held;

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
        if (!declares("minecraft:behavior.pickup_items") || level().isClientSide()) return;
        if (carried == null && !getMainHandItem().isEmpty()) return;

        double range = number(definition, "minecraft:behavior.pickup_items", "max_dist", 3);
        for (net.minecraft.world.entity.item.ItemEntity dropped
            : level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                getBoundingBox().inflate(range))) {
            if (!dropped.isAlive() || dropped.hasPickUpDelay()) continue;
            if (!shareable(dropped.getItem())) continue;
            if (stow(dropped.getItem().copy())) dropped.discard();
            return;
        }
    }

    /**
     * Puts one item where the pack said this entity keeps what it collects.
     *
     * `minecraft:inventory` gives the entity slots of its own, which is what a pack describes for
     * something that hauls goods. Without it there is one hand, which is enough for the villager
     * carrying bread that most packs use this for. False when there is nowhere left to put it.
     */
    private boolean stow(ItemStack stack) {
        if (carried == null) {
            setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stack);
            return true;
        }
        return carried.addItem(stack).isEmpty();
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
        if (offers == null || !declares("minecraft:trade_resupply")) return;

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
        if (!declares("minecraft:teleport") || level().isClientSide()) return;

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
        if (!declares("minecraft:spawn_egg_interaction")) return false;
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

    /** Whether the food `minecraft:breedable` names has put this entity in the mood. */
    public boolean inTheMood() {
        return inLoveUntil > tickCount;
    }

    /** A pack's controller reads being in the mood back as `q.is_in_love`. */
    private void publishBeingInLove() {
        if (!declares("minecraft:breedable")) return;

        boolean inLove = inTheMood();
        if (inLove != Boolean.TRUE.equals(value("is_in_love"))) setValue("is_in_love", inLove);
    }

    public void leaveTheMood() {
        inLoveUntil = 0;
    }

    /** What a pack says is born, or another of the same kind when it says nothing. */
    public net.minecraft.world.entity.EntityType<?> offspringType() {
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
     * Going away again because the pack said this entity may.
     *
     * `minecraft:despawn` names the conditions under which an entity is allowed to disappear, and a
     * pack leans on it to keep a crowd from growing without bound. An entity a player asked to stay
     * keeps its place, which is what persistence means on both editions.
     */
    private void despawn() {
        if (!declares("minecraft:despawn") || isPersistenceRequired()) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel)) return;

        com.google.gson.JsonObject body = definition.components().get("minecraft:despawn");
        Filter filter = body.has("filters") ? Filter.parse(body.get("filters")) : null;
        if (filter == null || !filter.matches(new Filter.Context(this, null, null, ServerHooks.state()))) {
            return;
        }
        discard();
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
        if (!declares("minecraft:balloonable") || getLeashHolder() == null) return;

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
        if (!declares("minecraft:managed_wandering_trader") || isPersistenceRequired()) return;

        // Bedrock's own manager gives a trader two game days before it wanders off.
        if (tickCount > 48000) discard();
    }

    /** How long this entity stays willing to breed, counted in its own ticks. */
    private int inLoveUntil;

    /** Marks a newly born one as a baby, which a pack's render controller reads as `q.is_baby`. */
    void setYoung() {
        ServerHooks.state().setProperty(getUUID(), "is_baby", true);
    }

    /** Bedrock says an entity is angry; Java asks the entity, so the flag is kept here. */
    public boolean isAngry() {
        return declares("minecraft:angry");
    }

    /**
     * Sets one of the values a pack reads back off the entity, and tells the clients drawing it.
     *
     * A behaviour whose state Java has nowhere to keep is carried as the property the pack's own
     * render controllers, filters and Molang ask about, which is where Bedrock keeps it too.
     */
    public void setValue(String name, Object value) {
        if (isClientSide() || ServerHooks.state() == null) return;

        ServerHooks.state().setProperty(getUUID(), name, value);
        at.ioexception.mcaddon.server.EntityPropertySync.broadcast(this, ServerHooks.state());
    }

    /** One of those values as it stands, or null when nothing has set it. */
    public Object value(String name) {
        return isClientSide() || ServerHooks.state() == null
            ? null : ServerHooks.state().property(getUUID(), name);
    }

    /** Who this entity belongs to, cached from the add-on state the taming wrote it into. */
    private java.util.UUID owner;
    private boolean ownerRead;

    /**
     * Marks the entity as belonging to somebody, which is what taming it amounts to.
     *
     * The add-on state holds it rather than entity NBT, the same as every other fact a pack sets on
     * an entity, so it survives a restart and reaches the clients drawing the entity.
     */
    public void setOwner(Player player) {
        owner = player.getUUID();
        ownerRead = true;
        ServerHooks.state().setProperty(getUUID(), "owner", player.getStringUUID());
    }

    /** The owner's id, or null while nobody owns this entity. */
    public java.util.UUID ownerUuid() {
        if (ownerRead || isClientSide() || ServerHooks.state() == null) return owner;

        ownerRead = true;
        Object stored = ServerHooks.state().property(getUUID(), "owner");
        if (stored instanceof String text) {
            try {
                owner = java.util.UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                owner = null;
            }
        }
        return owner;
    }

    /**
     * What a pack's owner behaviours follow, fight for and sleep beside.
     *
     * Java's own answer for an ownable entity reaches players alone before 26, and every owner a
     * pack's taming components can produce is a player, so both forms find the same entity.
     */
    //? if >=26 {
    @Override
    public net.minecraft.world.entity.EntityReference<net.minecraft.world.entity.LivingEntity>
        getOwnerReference() {
        java.util.UUID id = ownerUuid();
        return id == null ? null : net.minecraft.world.entity.EntityReference.of(id);
    }
    //?} else {
    /*@Override
    public java.util.UUID getOwnerUUID() {
        return ownerUuid();
    }
    *///?}

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

    /**
     * Breaks the blocks a pack listed as the entity walks into them.
     *
     * `minecraft:break_blocks` is how Bedrock describes a mob that leaves a hole behind it. The
     * entity's own box is what decides which blocks those are, so nothing is searched for.
     */
    private void breakBlocks() {
        if (!declares("minecraft:break_blocks")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
        for (com.google.gson.JsonElement named
            : at.ioexception.mcaddon.util.Json.list(componentBody("minecraft:break_blocks"), "breakable_blocks")) {
            if (named.isJsonPrimitive()) wanted.add(named.getAsString());
        }
        if (wanted.isEmpty()) return;

        net.minecraft.world.phys.AABB box = getBoundingBox().inflate(0.2);
        net.minecraft.core.BlockPos.betweenClosedStream(box)
            .filter(at -> wanted.contains(
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString()))
            .map(net.minecraft.core.BlockPos::immutable)
            .toList()
            .forEach(at -> level.destroyBlock(at, true, this));
    }

    /**
     * Hands out the effect `minecraft:mob_effect` names to whatever comes close enough.
     *
     * Bedrock states the effect, how far it reaches and how long it lasts, and applies it to
     * everything the component's filters allow. The entity itself is never one of them.
     */
    private void giveEffect() {
        if (!declares("minecraft:mob_effect")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel)) return;

        String effect = text("minecraft:mob_effect", "mob_effect");
        Identifier id = effect == null ? null : Identifier.tryParse(
            effect.contains(":") ? effect : "minecraft:" + effect);
        if (id == null) return;

        double range = number(definition, "minecraft:mob_effect", "effect_range", 0.2);
        int seconds = (int) number(definition, "minecraft:mob_effect", "effect_time", 10);
        Filter filter = Filter.parse(componentBody("minecraft:mob_effect").get("entity_filter"));

        for (net.minecraft.world.entity.LivingEntity nearby : level().getEntitiesOfClass(
            net.minecraft.world.entity.LivingEntity.class, getBoundingBox().inflate(range))) {
            if (nearby == this) continue;
            if (!filter.matches(new Filter.Context(this, nearby, nearby, ServerHooks.state()))) continue;

            Platform.addEffect(nearby, id, seconds * 20, 0, true);
        }
    }

    /**
     * Hurts whatever is standing against the entity, which is what `minecraft:area_attack` does.
     *
     * The damage is per second in Bedrock and this runs on the sensor interval, so what lands is
     * the share of a second that has passed.
     */
    private void attackAround() {
        if (!declares("minecraft:area_attack")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        double range = number(definition, "minecraft:area_attack", "damage_range", 0.2);
        float perSecond = (float) number(definition, "minecraft:area_attack", "damage_per_tick", 2);
        Filter filter = Filter.parse(componentBody("minecraft:area_attack").get("entity_filter"));

        for (net.minecraft.world.entity.LivingEntity nearby : level.getEntitiesOfClass(
            net.minecraft.world.entity.LivingEntity.class, getBoundingBox().inflate(range))) {
            if (nearby == this) continue;
            if (!filter.matches(new Filter.Context(this, nearby, nearby, ServerHooks.state()))) continue;

            Platform.hurt(nearby, level, damageSources().mobAttack(this),
                perSecond * Sensors.INTERVAL_TICKS / 20f);
        }
    }

    /**
     * Leaves a block behind as the entity moves, the way `minecraft:trail` describes.
     *
     * Bedrock spawns the block under the entity while its own filters hold, which is how a pack
     * makes something freeze the water it swims over or scorch the ground it walks on.
     */
    private void leaveTrail() {
        if (!declares("minecraft:trail")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        String named = text("minecraft:trail", "block_type");
        Identifier id = named == null ? null : Identifier.tryParse(named);
        net.minecraft.world.level.block.Block block = id == null ? null
            : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) return;

        if (!Filter.parse(componentBody("minecraft:trail").get("spawn_filter"))
            .matches(new Filter.Context(this, this, null, ServerHooks.state()))) {
            return;
        }

        net.minecraft.core.BlockPos under = blockPosition().below();
        if (!level.getBlockState(under).canBeReplaced()) return;

        level.setBlockAndUpdate(under, block.defaultBlockState());
    }

    /**
     * Ripens the crops the entity is standing among, which is what `minecraft:grows_crop` does.
     *
     * Bedrock gives a chance and a radius. Java already knows what growing means for every block
     * that can be given bone meal, so the block decides rather than a table of crops.
     */
    private void growCrops() {
        if (!declares("minecraft:grows_crop")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        double chance = number(definition, "minecraft:grows_crop", "chance", 0.02);
        if (random.nextDouble() > chance) return;

        int radius = (int) number(definition, "minecraft:grows_crop", "charges", 1) + 1;
        net.minecraft.core.BlockPos at = blockPosition().offset(
            random.nextInt(radius * 2 + 1) - radius, 0, random.nextInt(radius * 2 + 1) - radius);
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(at);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.BonemealableBlock crop)) return;
        if (!Platform.canBonemeal(crop, level, at, state)) return;

        crop.performBonemeal(level, random, at, state);
    }

    /**
     * Fires the event a pack asked for while somebody is looking at the entity.
     *
     * `minecraft:looked_at` is what makes an enderman's stare work, and a pack uses it for anything
     * that should react to being watched. Bedrock measures the angle rather than the distance.
     */
    private void noticeBeingWatched() {
        if (!declares("minecraft:looked_at")) return;

        String event = text("minecraft:looked_at", "looked_at_event");
        if (event == null) return;

        double reach = number(definition, "minecraft:looked_at", "search_radius", 10);
        for (Player player : level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(reach))) {
            net.minecraft.world.phys.Vec3 towards = position().subtract(player.getEyePosition()).normalize();
            if (player.getLookAngle().normalize().dot(towards) < 0.95) continue;

            EntityEvents.trigger(this, event, player, ServerHooks.state());
            return;
        }
    }

    /**
     * Heals the entity back up once nothing has hurt it for a while.
     *
     * `minecraft:combat_regeneration` is Bedrock's name for a mob that recovers between fights.
     * Being hit resets the wait, which is what keeps it from healing through one.
     */
    private void regenerate() {
        if (!declares("minecraft:combat_regeneration") || getHealth() >= getMaxHealth()) return;
        if (tickCount - lastHurtTick < 100) return;

        heal(1);
    }

    /** Rises through the liquid it is in, which is the whole of what a buoyant entity does. */
    private void rideLiquid() {
        if (!declares("minecraft:buoyant") && !declares("minecraft:floats_in_liquid")) return;
        if (!isInWater() && !isInLava()) return;

        double lift = number(definition, "minecraft:buoyant", "liquid_blocks_scale", 1) * 0.03;
        setDeltaMovement(getDeltaMovement().add(0, lift, 0));
        fallDistance = 0;
    }

    /** Ticks of the entity's own age at which something last hurt it, for the components that wait. */
    private int lastHurtTick = -1000;

    /** Health as it stood last tick, which is how being hurt is noticed without a damage hook. */
    private float healthLastTick = -1;

    /**
     * Hurts the entity while it is out of the liquid it belongs in.
     *
     * `minecraft:drying_out_timer` gives a pack's fish a while on land before it suffers, and names
     * the events fired as it dries out and as it is put back.
     */
    private void dryOut() {
        if (!declares("minecraft:drying_out_timer")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        if (isInWater()) {
            if (dryingFor > 0) fire(text("minecraft:drying_out_timer", "stopped_drying_out_event"));
            dryingFor = 0;
            return;
        }

        int allowed = (int) (number(definition, "minecraft:drying_out_timer", "total_time", 60) * 20);
        if (dryingFor == 0) fire(text("minecraft:drying_out_timer", "dried_out_event"));

        dryingFor += Sensors.INTERVAL_TICKS;
        if (dryingFor < allowed) return;

        Platform.hurt(this, level, damageSources().dryOut(), 1);
    }

    private int dryingFor;

    /** Runs one of a component's named events, and does nothing when the pack named none. */
    private void fire(String event) {
        if (event != null) EntityEvents.trigger(this, event, null, ServerHooks.state());
    }

    /**
     * Hits the target, and throws it the way `minecraft:apply_knockback_rules` asks.
     *
     * Bedrock lists presets and picks the first whose conditions hold, which is how a drowned pushes
     * a swimmer under rather than back. The question moved onto the level in 26.
     */
    //? if >=26 {
    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel level,
        net.minecraft.world.entity.Entity target) {
        boolean hit = super.doHurtTarget(level, target);
    //?} else {
    /*@Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        boolean hit = super.doHurtTarget(target);
    *///?}
        if (hit) knockBack(target);
        return hit;
    }

    /** The first knockback preset whose conditions the target meets, applied where Java's would be. */
    private void knockBack(net.minecraft.world.entity.Entity target) {
        if (definition == null || !declares("minecraft:apply_knockback_rules")) return;

        for (com.google.gson.JsonElement element : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:apply_knockback_rules"), "presets")) {
            if (!element.isJsonObject()) continue;

            com.google.gson.JsonObject preset = element.getAsJsonObject();
            boolean needsWater = preset.has("check_if_target_is_immersed_in_water")
                && preset.get("check_if_target_is_immersed_in_water").getAsBoolean();
            if (needsWater && !target.isInWater()) continue;

            double flat = preset.has("horizontal_power") ? preset.get("horizontal_power").getAsDouble() : 0.4;
            double up = preset.has("vertical_power") ? preset.get("vertical_power").getAsDouble() : 0.4;
            double ceiling = preset.has("vertical_velocity_cap")
                ? preset.get("vertical_velocity_cap").getAsDouble() : Double.MAX_VALUE;

            net.minecraft.world.phys.Vec3 away =
                target.position().subtract(position()).normalize();
            net.minecraft.world.phys.Vec3 thrown = target.getDeltaMovement()
                .add(away.x * flat, up, away.z * flat);
            target.setDeltaMovement(thrown.x, Math.min(thrown.y, ceiling), thrown.z);
            return;
        }
    }

    /**
     * Whatever the pack's shooter component names, thrown at what the entity is hunting.
     *
     * `minecraft:shooter` names the projectile by its entity id, which is enough for Java to build
     * one and aim it. The arc is Java's own for a bow, so an arrow drops the way a player's does.
     */
    @Override
    public void performRangedAttack(net.minecraft.world.entity.LivingEntity target, float power) {
        if (definition == null || !(level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        String named = text("minecraft:shooter", "def");
        net.minecraft.world.entity.EntityType<?> type =
            AddonEntities.typeOf(named == null ? "minecraft:arrow" : named);
        net.minecraft.world.entity.Entity shot = type == null ? null : Platform.createMob(type, level);
        if (!(shot instanceof net.minecraft.world.entity.projectile.Projectile projectile)) return;

        Platform.placeAt(projectile, getX(), getEyeY() - 0.1, getZ(), getYRot(), getXRot());
        projectile.setOwner(this);

        double toX = target.getX() - getX();
        double toY = target.getY(0.3333) - projectile.getY();
        double toZ = target.getZ() - getZ();
        double flat = Math.sqrt(toX * toX + toZ * toZ);
        projectile.shoot(toX, toY + flat * 0.2, toZ, 1.6f, 6f);

        playSound(SoundEvents.SKELETON_SHOOT, 1f, 1f / (random.nextFloat() * 0.4f + 0.8f));
        level.addFreshEntity(projectile);
    }

    private void addGoals() {
        int priority = 0;
        for (String behaviour : definition.behaviours()) {
            priority++;
            switch (behaviour) {
                case "minecraft:behavior.float" -> goalSelector.addGoal(priority, new FloatGoal(this));
                case "minecraft:behavior.panic" -> goalSelector.addGoal(priority, new PanicGoal(this, 1.25));
                case "minecraft:behavior.swell" ->
                    goalSelector.addGoal(priority, new SwellGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.beg" ->
                    goalSelector.addGoal(priority, new BegGoal(this, componentBody(behaviour)));

                // One squid moves five ways, and Bedrock names each of them separately.
                case "minecraft:behavior.squid_idle" ->
                    goalSelector.addGoal(priority, SquidGoal.idling(this));
                case "minecraft:behavior.squid_dive" ->
                    goalSelector.addGoal(priority, SquidGoal.diving(this));
                case "minecraft:behavior.squid_flee" ->
                    goalSelector.addGoal(priority, SquidGoal.fleeing(this));
                case "minecraft:behavior.squid_move_away_from_ground" ->
                    goalSelector.addGoal(priority, SquidGoal.leavingGround(this));
                case "minecraft:behavior.squid_out_of_water" ->
                    goalSelector.addGoal(priority, SquidGoal.flopping(this));
                // A slime turns, hops, floats and closes on a target, and Bedrock names each of the
                // four separately.
                case "minecraft:behavior.slime_random_direction" ->
                    goalSelector.addGoal(priority, SlimeGoal.turning(this, componentBody(behaviour)));
                case "minecraft:behavior.slime_keep_on_jumping" ->
                    goalSelector.addGoal(priority, SlimeGoal.hopping(this, componentBody(behaviour)));
                case "minecraft:behavior.slime_float" ->
                    goalSelector.addGoal(priority, SlimeGoal.floating(this, componentBody(behaviour)));
                case "minecraft:behavior.slime_attack" ->
                    goalSelector.addGoal(priority, SlimeGoal.attacking(this, componentBody(behaviour)));

                case "minecraft:behavior.random_stroll", "minecraft:behavior.move_outdoors",
                     "minecraft:behavior.explore_outskirts" ->
                    goalSelector.addGoal(priority, new WaterAvoidingRandomStrollGoal(this, 0.6));
                case "minecraft:behavior.look_at_player", "minecraft:behavior.look_at_trading_player" ->
                    goalSelector.addGoal(priority, new LookAtPlayerGoal(this, Player.class, 8f));
                case "minecraft:behavior.random_look_around",
                     "minecraft:behavior.random_look_around_and_sit" ->
                    goalSelector.addGoal(priority, new RandomLookAroundGoal(this));

                // Bedrock names the kind of entity to watch; Java's goal takes a class, and the
                // widest class that still means a creature is every living thing.
                case "minecraft:behavior.look_at_entity" -> goalSelector.addGoal(priority,
                    new LookAtPlayerGoal(this, net.minecraft.world.entity.LivingEntity.class, 8f));

                // Bedrock names the kind of wandering; Java has one stroll goal and a speed.
                case "minecraft:behavior.move_indoors",
                     "minecraft:behavior.move_towards_dwelling_restriction",
                     "minecraft:behavior.move_towards_home_restriction",
                     "minecraft:behavior.move_towards_restriction",
                     "minecraft:behavior.go_home" ->
                    goalSelector.addGoal(priority, new MoveTowardsRestrictionGoal(this, 0.6));

                case "minecraft:behavior.move_to_village" ->
                    goalSelector.addGoal(priority, new MoveBackToVillageGoal(this, 0.6, false));
                // Java's goal asks separately whether the route may run at night and whether the mob
                // can get through a door. Bedrock states neither, so the walk is allowed at any
                // hour and doors are left to the navigator.
                case "minecraft:behavior.move_through_village" ->
                    goalSelector.addGoal(priority,
                        new MoveThroughVillageGoal(this, 0.6, false, 4, () -> false));
                case "minecraft:behavior.mingle" ->
                    goalSelector.addGoal(priority, new StrollThroughVillageGoal(this, 60));
                case "minecraft:behavior.move_towards_target" ->
                    goalSelector.addGoal(priority, new MoveTowardsTargetGoal(this, 0.9, 32f));

                case "minecraft:behavior.random_fly", "minecraft:behavior.random_hover",
                     "minecraft:behavior.hover", "minecraft:behavior.float_wander" ->
                    goalSelector.addGoal(priority, new WaterAvoidingRandomFlyingGoal(this, 1.0));
                case "minecraft:behavior.random_swim", "minecraft:behavior.swim_wander" ->
                    goalSelector.addGoal(priority, new RandomSwimmingGoal(this, 1.0, 40));
                case "minecraft:behavior.move_to_water", "minecraft:behavior.move_to_liquid" ->
                    goalSelector.addGoal(priority, new TryFindWaterGoal(this));
                case "minecraft:behavior.swim_up_for_breath" ->
                    goalSelector.addGoal(priority, new BreathAirGoal(this));

                case "minecraft:behavior.eat_block" ->
                    goalSelector.addGoal(priority, new EatBlockGoal(this));
                // Bedrock's find_cover and flee_sun both end with the mob standing out of the light.
                case "minecraft:behavior.flee_sun", "minecraft:behavior.find_cover" ->
                    goalSelector.addGoal(priority, new FleeSunGoal(this, 1.0));
                case "minecraft:behavior.restrict_sun" ->
                    goalSelector.addGoal(priority, new RestrictSunGoal(this));
                // Java asks per difficulty whether a door may be broken; Bedrock states the ability
                // once and leaves the difficulty out of it.
                case "minecraft:behavior.break_door", "minecraft:annotation.break_door" ->
                    goalSelector.addGoal(priority, new BreakDoorGoal(this, difficulty -> true));
                case "minecraft:behavior.follow_mob" ->
                    goalSelector.addGoal(priority, new FollowMobGoal(this, 1.0, 3f, 7f));
                case "minecraft:behavior.leap_at_target",
                     "minecraft:behavior.stalk_and_pounce_on_target" ->
                    goalSelector.addGoal(priority, new LeapAtTargetGoal(this, 0.4f));
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

                case "minecraft:behavior.tempt", "minecraft:behavior.float_tempt" ->
                    goalSelector.addGoal(priority, Platform.temptGoal(this));
                case "minecraft:behavior.open_door", "minecraft:annotation.open_door" ->
                    goalSelector.addGoal(priority, new OpenDoorGoal(this, true));

                case "minecraft:behavior.melee_attack", "minecraft:behavior.melee_box_attack",
                     "minecraft:behavior.delayed_attack", "minecraft:behavior.stomp_attack" ->
                    goalSelector.addGoal(priority,
                        new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(this, 1.0, false));
                // A charge is a melee attack run at, which Java expresses as the speed the goal
                // closes the distance with.
                case "minecraft:behavior.charge_attack" ->
                    goalSelector.addGoal(priority,
                        new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(this, 1.4, false));
                case "minecraft:behavior.nearest_attackable_target",
                     "minecraft:behavior.nearest_prioritized_attackable_target" ->
                    targetSelector.addGoal(priority,
                        new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                            this, Player.class, true));
                case "minecraft:behavior.hurt_by_target", "minecraft:behavior.target_when_pushed" ->
                    targetSelector.addGoal(priority,
                        new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));

                // Bedrock states the gap between shots in seconds and the reach in blocks; Java's
                // goal counts the gap in ticks and asks the entity itself to release the projectile.
                case "minecraft:behavior.ranged_attack" -> goalSelector.addGoal(priority,
                    new net.minecraft.world.entity.ai.goal.RangedAttackGoal(this,
                        number(definition, behaviour, "speed_multiplier", 1),
                        (int) (number(definition, behaviour, "attack_interval_min", 1) * 20),
                        (float) number(definition, behaviour, "attack_radius", 16)));
                case "minecraft:behavior.ocelotattack" -> goalSelector.addGoal(priority,
                    new net.minecraft.world.entity.ai.goal.OcelotAttackGoal(this));

                case "minecraft:behavior.follow_parent" -> goalSelector.addGoal(priority,
                    FollowKinGoal.parent(this, componentBody(behaviour)));
                case "minecraft:behavior.follow_caravan",
                     "minecraft:behavior.follow_target_leader" -> goalSelector.addGoal(priority,
                    FollowKinGoal.leader(this, componentBody(behaviour)));

                case "minecraft:behavior.move_to_block" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.named(this, componentBody(behaviour)));
                case "minecraft:behavior.move_to_land" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.land(this, componentBody(behaviour)));
                case "minecraft:behavior.move_to_lava" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.lava(this, componentBody(behaviour)));
                case "minecraft:behavior.move_to_random_block" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.anywhere(this, componentBody(behaviour)));

                case "minecraft:behavior.equip_item" -> goalSelector.addGoal(priority,
                    new EquipItemGoal(this, definition.components().get("minecraft:equip_item")));
                case "minecraft:behavior.hold_ground" ->
                    goalSelector.addGoal(priority, HoldGroundGoal.standing(this));
                case "minecraft:behavior.look_at_target" ->
                    goalSelector.addGoal(priority, HoldGroundGoal.watching(this));
                // Java writes door handling as two goals under one base, and opening is the half
                // Bedrock's own door interaction does.
                case "minecraft:behavior.door_interact" ->
                    goalSelector.addGoal(priority, new OpenDoorGoal(this, true));

                case "minecraft:behavior.raid_garden" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.raid(this, componentBody(behaviour)));
                case "minecraft:behavior.harvest_farm_block" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.harvest(this, componentBody(behaviour)));
                case "minecraft:behavior.fertilize_farm_block" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.fertilize(this, componentBody(behaviour)));
                // Java's own goal for this is the one its zombie uses on the same block.
                case "minecraft:behavior.stomp_turtle_egg" -> goalSelector.addGoal(priority,
                    new net.minecraft.world.entity.ai.goal.RemoveBlockGoal(
                        net.minecraft.world.level.block.Blocks.TURTLE_EGG, this,
                        number(definition, behaviour, "speed_multiplier", 1),
                        (int) number(definition, behaviour, "search_height", 2)));

                case "minecraft:behavior.timer_flag_1", "minecraft:behavior.timer_flag_2",
                     "minecraft:behavior.timer_flag_3" ->
                    goalSelector.addGoal(priority, new TimerFlagGoal(this, componentBody(behaviour),
                        behaviour.substring("minecraft:behavior.".length())));
                case "minecraft:behavior.random_sitting" ->
                    goalSelector.addGoal(priority, RestGoal.sitting(this, componentBody(behaviour)));
                case "minecraft:behavior.lay_down" ->
                    goalSelector.addGoal(priority, RestGoal.lyingDown(this, componentBody(behaviour)));
                case "minecraft:behavior.knockback_roar" ->
                    goalSelector.addGoal(priority, new KnockbackRoarGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.eat_carried_item" ->
                    goalSelector.addGoal(priority, new EatCarriedItemGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.move_around_target" ->
                    goalSelector.addGoal(priority, CircleTargetGoal.walking(this, componentBody(behaviour)));
                case "minecraft:behavior.jump_around_target" ->
                    goalSelector.addGoal(priority, CircleTargetGoal.jumping(this, componentBody(behaviour)));

                // What the entity does because it belongs to somebody. Each of these reads back the
                // owner that taming stored, so an untamed entity carrying one simply never starts it.
                case "minecraft:behavior.owner_hurt_target" -> targetSelector.addGoal(priority,
                    OwnerTargetGoal.whatTheOwnerHurt(this, componentBody(behaviour)));
                case "minecraft:behavior.owner_hurt_by_target" -> targetSelector.addGoal(priority,
                    OwnerTargetGoal.whoHurtTheOwner(this, componentBody(behaviour)));
                case "minecraft:behavior.defend_trusted_target" -> targetSelector.addGoal(priority,
                    OwnerTargetGoal.defendingTheOwner(this, componentBody(behaviour)));
                case "minecraft:behavior.teleport_to_owner" -> goalSelector.addGoal(priority,
                    new TeleportToOwnerGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.go_and_give_items_to_owner" -> goalSelector.addGoal(priority,
                    new GiveItemsToOwnerGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.pet_sleep_with_owner" -> goalSelector.addGoal(priority,
                    PetRestGoal.besideSleepingOwner(this, componentBody(behaviour)));
                case "minecraft:behavior.stay_while_sitting" -> goalSelector.addGoal(priority,
                    PetRestGoal.whileSitting(this));
                case "minecraft:behavior.find_mount" -> goalSelector.addGoal(priority,
                    new FindMountGoal(this, componentBody(behaviour)));

                case "minecraft:behavior.roar" -> goalSelector.addGoal(priority,
                    PoseHoldGoal.roaring(this, componentBody(behaviour)));
                case "minecraft:behavior.croak" -> goalSelector.addGoal(priority,
                    PoseHoldGoal.croaking(this, componentBody(behaviour)));
                case "minecraft:behavior.sniff" -> goalSelector.addGoal(priority,
                    PoseHoldGoal.sniffing(this, componentBody(behaviour)));
                case "minecraft:behavior.emerge" -> goalSelector.addGoal(priority,
                    PoseHoldGoal.emerging(this, componentBody(behaviour)));
                case "minecraft:behavior.dig" -> goalSelector.addGoal(priority,
                    PoseHoldGoal.digging(this, componentBody(behaviour)));
                case "minecraft:behavior.random_search_and_dig" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.searchingAndDigging(this, componentBody(behaviour)));

                // What a Bedrock dolphin does that nothing else does.
                case "minecraft:behavior.random_breach" -> goalSelector.addGoal(priority,
                    DolphinGoal.breaching(this, componentBody(behaviour)));
                case "minecraft:behavior.find_underwater_treasure" -> goalSelector.addGoal(priority,
                    DolphinGoal.seekingTreasure(this, componentBody(behaviour)));
                case "minecraft:behavior.swim_with_entity" -> goalSelector.addGoal(priority,
                    DolphinGoal.escorting(this, componentBody(behaviour)));

                case "minecraft:behavior.play_dead" -> goalSelector.addGoal(priority,
                    new PlayDeadGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.scared" -> goalSelector.addGoal(priority,
                    new ScaredGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.celebrate" -> goalSelector.addGoal(priority,
                    new CelebrateGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.sneeze" -> goalSelector.addGoal(priority,
                    new SneezeGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.roll" -> goalSelector.addGoal(priority,
                    new RollGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.swim_idle" -> goalSelector.addGoal(priority,
                    new SwimIdleGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.restrict_open_door" -> goalSelector.addGoal(priority,
                    new RestrictOpenDoorGoal(this));
                case "minecraft:behavior.rise_to_liquid_level" -> goalSelector.addGoal(priority,
                    new RiseToLiquidLevelGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.move_to_poi" -> goalSelector.addGoal(priority,
                    new MoveToPoiGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.jump_to_block" -> goalSelector.addGoal(priority,
                    new JumpToBlockGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.lay_egg" -> goalSelector.addGoal(priority,
                    new LayEggGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.eat_mob" -> goalSelector.addGoal(priority,
                    new EatMobGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.fire_at_target" -> goalSelector.addGoal(priority,
                    new FireAtTargetGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.charge_held_item" -> goalSelector.addGoal(priority,
                    new ChargeHeldItemGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.circle_around_anchor" -> goalSelector.addGoal(priority,
                    new CircleAroundAnchorGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.controlled_by_player" -> goalSelector.addGoal(priority,
                    new ControlledByPlayerGoal(this, componentBody(behaviour)));

                case "minecraft:behavior.take_block" -> goalSelector.addGoal(priority,
                    CarryBlockGoal.taking(this, componentBody(behaviour)));
                case "minecraft:behavior.place_block" -> goalSelector.addGoal(priority,
                    CarryBlockGoal.placing(this, componentBody(behaviour)));
                case "minecraft:behavior.enderman_take_block" -> goalSelector.addGoal(priority,
                    CarryBlockGoal.endermanTaking(this, componentBody(behaviour)));
                case "minecraft:behavior.enderman_leave_block" -> goalSelector.addGoal(priority,
                    CarryBlockGoal.endermanLeaving(this, componentBody(behaviour)));

                case "minecraft:behavior.ram_attack" -> goalSelector.addGoal(priority,
                    ChargeAttackGoal.ramming(this, componentBody(behaviour)));
                case "minecraft:behavior.swoop_attack" -> goalSelector.addGoal(priority,
                    ChargeAttackGoal.swooping(this, componentBody(behaviour)));
                case "minecraft:behavior.aquatic_charge_attack" -> goalSelector.addGoal(priority,
                    ChargeAttackGoal.chargingUnderwater(this, componentBody(behaviour)));

                case "minecraft:behavior.breed" -> goalSelector.addGoal(priority,
                    new BreedGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.trade_with_player" -> goalSelector.addGoal(priority,
                    TradeGoal.whileTrading(this, componentBody(behaviour)));
                case "minecraft:behavior.trade_interest" -> goalSelector.addGoal(priority,
                    TradeGoal.showingWares(this, componentBody(behaviour)));

                // A villager's working life: the shift at a station, the handing round of what was
                // gathered, and the pair of behaviours that make another villager.
                case "minecraft:behavior.work" -> goalSelector.addGoal(priority,
                    WorkGoal.working(this, componentBody(behaviour)));
                case "minecraft:behavior.work_composter" -> goalSelector.addGoal(priority,
                    WorkGoal.composting(this, componentBody(behaviour)));
                case "minecraft:behavior.share_items" -> goalSelector.addGoal(priority,
                    ShareItemsGoal.sharing(this, componentBody(behaviour)));
                case "minecraft:behavior.transport_items" -> goalSelector.addGoal(priority,
                    ShareItemsGoal.transporting(this, componentBody(behaviour)));
                case "minecraft:behavior.drop_item_for" -> goalSelector.addGoal(priority,
                    ShareItemsGoal.dropping(this, componentBody(behaviour)));
                case "minecraft:behavior.offer_flower" -> goalSelector.addGoal(priority,
                    ShareItemsGoal.offering(this, componentBody(behaviour)));
                case "minecraft:behavior.inspect_bookshelf" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.bookshelf(this, componentBody(behaviour)));
                // Bedrock's villager courtship is its own pair of behaviours; what they come to is
                // the same walk towards a willing partner that every other pack animal does.
                case "minecraft:behavior.make_love", "minecraft:behavior.receive_love" ->
                    goalSelector.addGoal(priority, new BreedGoal(this, componentBody(behaviour)));
                // Napping is lying down, and snacking is eating what is already in hand.
                case "minecraft:behavior.nap" -> goalSelector.addGoal(priority,
                    RestGoal.lyingDown(this, componentBody(behaviour)));
                case "minecraft:behavior.snacking" -> goalSelector.addGoal(priority,
                    new EatCarriedItemGoal(this, componentBody(behaviour)));

                case "minecraft:behavior.admire_item" -> goalSelector.addGoal(priority,
                    BarterGoal.admiring(this, componentBody(behaviour)));
                case "minecraft:behavior.barter" -> goalSelector.addGoal(priority,
                    BarterGoal.bartering(this, componentBody(behaviour)));

                // A wind-up held at range: a guardian's beam and a warden's boom.
                case "minecraft:behavior.guardian_attack" -> goalSelector.addGoal(priority,
                    BeamAttackGoal.beam(this, componentBody(behaviour)));
                case "minecraft:behavior.sonic_boom" -> goalSelector.addGoal(priority,
                    BeamAttackGoal.sonicBoom(this, componentBody(behaviour)));
                case "minecraft:behavior.investigate_suspicious_location" ->
                    goalSelector.addGoal(priority, new InvestigateGoal(this, componentBody(behaviour)));

                case "minecraft:behavior.silverfish_merge_with_stone" ->
                    goalSelector.addGoal(priority, SilverfishGoal.merging(this));
                case "minecraft:behavior.silverfish_wake_up_friends" ->
                    goalSelector.addGoal(priority, SilverfishGoal.wakingFriends(this));

                case "minecraft:behavior.vex_random_move" ->
                    goalSelector.addGoal(priority, new VexMoveGoal(this));
                case "minecraft:behavior.vex_copy_owner_target" -> targetSelector.addGoal(priority,
                    OwnerTargetGoal.whatTheOwnerIsAfter(this, componentBody(behaviour)));

                case "minecraft:behavior.wither_random_attack_pos_goal" ->
                    goalSelector.addGoal(priority,
                        WitherGoal.loosingShots(this, componentBody(behaviour)));
                case "minecraft:behavior.wither_target_highest_damage" ->
                    targetSelector.addGoal(priority,
                        WitherGoal.answeringTheWorstAttacker(this, componentBody(behaviour)));

                case "minecraft:behavior.run_around_like_crazy" -> goalSelector.addGoal(priority,
                    new BuckRiderGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.ocelot_sit_on_block" -> goalSelector.addGoal(priority,
                    MoveToMatchingBlockGoal.sitOnBlock(this, componentBody(behaviour)));
                case "minecraft:behavior.skeleton_horse_trap" -> goalSelector.addGoal(priority,
                    new HorseTrapGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.defend_village_target" -> targetSelector.addGoal(priority,
                    new DefendVillageGoal(this, componentBody(behaviour)));
                case "minecraft:behavior.use_kinetic_weapon" -> goalSelector.addGoal(priority,
                    new KineticWeaponGoal(this, componentBody(behaviour)));

                case "minecraft:behavior.stay_near_noteblock" -> goalSelector.addGoal(priority,
                    NoteblockGoal.stayingNear(this, componentBody(behaviour)));
                case "minecraft:behavior.go_and_give_items_to_noteblock" ->
                    goalSelector.addGoal(priority,
                        NoteblockGoal.deliveringItems(this, componentBody(behaviour)));

                // A dragon's flight and its three attacks, anchored on the place the entity is kept
                // near rather than on the podium Java writes each of these phases around.
                case "minecraft:behavior.dragonholdingpattern" ->
                    goalSelector.addGoal(priority, DragonFlightGoal.circling(this));
                case "minecraft:behavior.dragontakeoff" ->
                    goalSelector.addGoal(priority, DragonFlightGoal.takingOff(this));
                case "minecraft:behavior.dragonlanding" ->
                    goalSelector.addGoal(priority, DragonFlightGoal.landing(this));
                case "minecraft:behavior.dragonscanning" ->
                    goalSelector.addGoal(priority, DragonFlightGoal.scanning(this));
                case "minecraft:behavior.dragonchargeplayer" -> goalSelector.addGoal(priority,
                    DragonAttackGoal.charging(this, componentBody(behaviour)));
                case "minecraft:behavior.dragonstrafeplayer" -> goalSelector.addGoal(priority,
                    DragonAttackGoal.strafing(this, componentBody(behaviour)));
                case "minecraft:behavior.dragonflaming" -> goalSelector.addGoal(priority,
                    DragonAttackGoal.flaming(this, componentBody(behaviour)));

                case "minecraft:behavior.send_event" -> goalSelector.addGoal(priority,
                    SpellGoal.sendingEvents(this, componentBody(behaviour)));
                case "minecraft:behavior.summon_entity" -> goalSelector.addGoal(priority,
                    SpellGoal.summoning(this, componentBody(behaviour)));

                // A behaviour this switch does not name may still be one the entity carries out on
                // its own clock rather than through a goal, so the register decides, not the switch.
                default -> {
                    if (!AddonEntities.implemented(behaviour)) AddonEntities.unsupported(behaviour);
                }
            }
        }
    }

    /**
     * Sends the drawing side the facts about this entity that never change while it lives.
     *
     * A pack's render controllers ask what the entity is as freely as they ask what it is doing:
     * which families it belongs to, whether it can climb or swim, how long its fuse burns. All of
     * that is in the behaviour pack, which the client may not have, so it is sent rather than read.
     */
    private void publishWhatItIs() {
        for (com.google.gson.JsonElement family : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:type_family"), "family")) {
            if (family.isJsonPrimitive()) setValue("family." + family.getAsString(), true);
        }

        setValue("can_climb", declares("minecraft:can_climb"));
        setValue("can_fly", declares("minecraft:navigation.fly") || declares("minecraft:movement.fly"));
        setValue("can_swim", declares("minecraft:navigation.swim")
            || declares("minecraft:movement.amphibious"));
        setValue("can_walk", declares("minecraft:navigation.walk")
            || declares("minecraft:navigation.generic"));
        setValue("can_power_jump", declares("minecraft:can_power_jump"));

        if (declares("minecraft:heartbeat")) {
            setValue("heartbeat_interval", number(definition, "minecraft:heartbeat", "interval", 2));
        }
        if (declares("minecraft:explode")) {
            setValue("fuse_time", number(definition, "minecraft:explode", "fuse_length", 1.5));
        }
    }

    /**
     * Puts on whatever `minecraft:equipment` rolls, and sets how likely each piece is to drop.
     *
     * The component names a loot table rather than the items, so what the entity wears is decided
     * once as it comes into the world. Which slot a piece belongs in is Java's own answer, so a
     * table handing out a helmet and a sword dresses the entity in both.
     */
    private void wearWhatThePackRolled() {
        String table = text("minecraft:equipment", "table");
        if (table == null || !(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        Identifier named = AddonEntities.lootTable(getType(), table);
        if (named == null) return;

        for (net.minecraft.world.item.ItemStack rolled : Platform.rollLoot(level, named, this)) {
            if (rolled.isEmpty()) continue;
            setItemSlot(getEquipmentSlotForItem(rolled), rolled);
        }
        for (com.google.gson.JsonElement element : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:equipment"), "slot_drop_chance")) {
            if (!element.isJsonObject()) continue;

            com.google.gson.JsonObject entry = element.getAsJsonObject();
            net.minecraft.world.entity.EquipmentSlot slot =
                equipmentSlot(at.ioexception.mcaddon.util.Json.text(entry, "slot", null));
            if (slot == null || !entry.has("drop_chance")) continue;

            setDropChance(slot, entry.get("drop_chance").getAsFloat());
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

        everyTick();
        if ((tickCount + getId()) % Sensors.INTERVAL_TICKS == 0) {
            Sensors.tick(this, ServerHooks.state());
            resupply();
            teleportAbout();
            pickUpNearby();
            rest();
            drink();
            takeOfferedFlower();
            celebrate();
            rise();
            manageAsTrader();
            despawn();
            burnInDaylight();
            hurtOverTime();
            breakBlocks();
            giveEffect();
            attackAround();
            leaveTrail();
            growCrops();
            noticeBeingWatched();
            regenerate();
            dryOut();
            showBossBar();
            addRider();
            countKin();
            noticeKinAnger();
            reflectProjectiles();
            drawFromHopper();
            coolDownAnger();
        }
    }

    /** `minecraft:group_size` is how many of its own kind a pack's entity can see around it. */
    private void countKin() {
        if (!declares("minecraft:group_size")) return;

        double radius = number(definition, "minecraft:group_size", "radius", 16);
        Filter counted = Filter.parse(componentBody("minecraft:group_size").get("filters"));
        long kin = level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                getBoundingBox().inflate(radius)).stream()
            .filter(nearby -> nearby != this)
            .filter(nearby -> counted.matches(
                new Filter.Context(this, nearby, nearby, ServerHooks.state())))
            .count();
        setValue("group_size", (double) kin);
    }

    /**
     * `minecraft:on_friendly_anger` fires when another of this entity's kind loses its temper.
     *
     * Bedrock raises it from the angry entity's own side; Java has no such notification, so each
     * entity watches its neighbours instead, and only the tick a calm crowd turns angry counts.
     */
    private void noticeKinAnger() {
        if (!declares("minecraft:on_friendly_anger")) return;

        boolean angry = level().getEntitiesOfClass(AddonEntity.class, getBoundingBox().inflate(16))
            .stream()
            .anyMatch(kin -> kin != this && kin.getType() == getType() && kin.getTarget() != null);
        boolean rising = angry && !sawKinAngry;
        sawKinAngry = angry;
        if (rising) fire(text("minecraft:on_friendly_anger", "event"));
    }

    private boolean sawKinAngry;

    /**
     * Sends back the projectiles `minecraft:reflect_projectiles` names.
     *
     * Bedrock turns the shot by the angle the pack gives and scales what is left of its speed. A
     * projectile this entity fired itself is left alone, or a reflector would return its own shots.
     */
    private void reflectProjectiles() {
        if (!declares("minecraft:reflect_projectiles")) return;

        java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
        for (com.google.gson.JsonElement named : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:reflect_projectiles"), "reflected_projectiles")) {
            if (named.isJsonPrimitive()) wanted.add(named.getAsString());
        }

        double scale = number(definition, "minecraft:reflect_projectiles", "reflection_scale", 1);
        for (net.minecraft.world.entity.projectile.Projectile shot : level().getEntitiesOfClass(
            net.minecraft.world.entity.projectile.Projectile.class, getBoundingBox().inflate(2))) {
            if (shot.getOwner() == this || !named(shot, wanted)) continue;

            shot.setDeltaMovement(shot.getDeltaMovement().scale(-scale));
            shot.setOwner(this);
        }
    }

    /** Whether a projectile is one of those a pack listed, by either spelling of its name. */
    private static boolean named(net.minecraft.world.entity.Entity shot,
        java.util.Set<String> wanted) {
        if (wanted.isEmpty()) return true;

        String id = BuiltInRegistries.ENTITY_TYPE.getKey(shot.getType()).toString();
        return wanted.contains(id) || wanted.contains(id.substring("minecraft:".length()));
    }

    /**
     * `minecraft:item_hopper` takes what is in the container above the entity.
     *
     * A Bedrock hopper minecart is described this way, and what it does is a hopper's: one item per
     * pull, out of whatever is directly overhead, into the inventory the pack gave the entity.
     */
    private void drawFromHopper() {
        if (!declares("minecraft:item_hopper") || carried == null) return;
        if (!(level().getBlockEntity(blockPosition().above())
            instanceof net.minecraft.world.Container above)) {
            return;
        }

        for (int slot = 0; slot < above.getContainerSize(); slot++) {
            ItemStack held = above.getItem(slot);
            if (held.isEmpty()) continue;

            ItemStack one = held.split(1);
            if (carried.addItem(one).isEmpty()) {
                above.setChanged();
                return;
            }
            held.grow(1);
            return;
        }
    }

    /** Lets an entity's anger fade, and tells everybody watching how cross it still is. */
    private void coolDownAnger() {
        if (anger == null || !anger.decay(Sensors.INTERVAL_TICKS)) return;

        setValue("anger_level", (double) anger.level());
        setValue("is_angry", anger.angry());
    }

    /**
     * The components that have to be answered on the entity's own clock rather than the sensors'.
     *
     * Growing up, floating and how the body is held all read as a stutter when they are decided
     * every tenth tick, so these are the few that pay for being asked every one.
     */
    private void everyTick() {
        noticeBeingHurt();
        noticeCatchingFire();
        noticeLeavingTheGround();
        growUp();
        rideLiquid();
        holdBody();
        bounce();
        swimAtItsOwnSpeed();
        dragThroughAir();
        rubAlongTheGround();
        faceTheGrid();
        flock();
        beBoosted();
        faceTheVehicle();
        breatheForRider();
        tickTheWorldAround();
        noticeEquipmentChanging();
        beat();
        hover();
        noticeTheBlockItIsIn();
        celebrateTheKill();
        publishBeingInLove();
        if (bossBar != null) bossBar.setProgress(getMaxHealth() <= 0 ? 0 : getHealth() / getMaxHealth());
    }

    /**
     * `minecraft:heartbeat` is a sound the entity makes on its own clock.
     *
     * The interval is a Molang expression on the warden, because its heart quickens as it gets
     * angrier. A pack that wrote a plain number gets that number; one that wrote an expression gets
     * the two seconds Bedrock's own default is, because the expression is answered on the drawing
     * side and there is no drawing here.
     */
    private void beat() {
        if (!declares("minecraft:heartbeat")) return;

        int interval = (int) (number(definition, "minecraft:heartbeat", "interval", 2)
            * Goals.TICKS_PER_SECOND);
        if (interval <= 0 || tickCount % interval != 0) return;

        playSound(sound(text("minecraft:heartbeat", "sound_event"), SoundEvents.WARDEN_HEARTBEAT),
            getSoundVolume(), getVoicePitch());
    }

    /** `minecraft:ground_offset` is how far above the ground the entity is meant to sit. */
    private void hover() {
        if (!declares("minecraft:ground_offset") || !onGround()) return;

        double offset = number(definition, "minecraft:ground_offset", "value", 0);
        if (offset <= 0) return;

        setPos(getX(), getY() + offset, getZ());
    }

    /**
     * `minecraft:inside_block_notifier` fires an event while the entity stands inside a named block.
     *
     * Bedrock lists each block with the event for entering it and the event for leaving it, so what
     * is remembered between ticks is which of them the entity was in last.
     */
    private void noticeTheBlockItIsIn() {
        if (!declares("minecraft:inside_block_notifier")) return;

        String inside = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(level().getBlockState(blockPosition()).getBlock()).toString();
        if (inside.equals(lastBlockInside)) return;

        for (com.google.gson.JsonElement element : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:inside_block_notifier"), "block_list")) {
            if (!element.isJsonObject()) continue;

            com.google.gson.JsonObject entry = element.getAsJsonObject();
            String named = blockNameOf(entry);
            if (named == null) continue;

            if (named.equals(inside)) fire(Goals.event(entry, "entered_block_event"));
            if (named.equals(lastBlockInside)) fire(Goals.event(entry, "exited_block_event"));
        }
        lastBlockInside = inside;
    }

    /**
     * Tells the hunters nearby that something they were after has died.
     *
     * `minecraft:celebrate_hunt` is what makes a piglin cheer over a hoglin. `broadcast` says the
     * cheer spreads to its own kind within the radius, which is why every entity in range is asked
     * rather than only whatever landed the kill.
     */
    public static void noticeHuntSucceeded(net.minecraft.world.entity.LivingEntity quarry) {
        if (quarry.level().isClientSide()
            || !AddonEntities.anyoneDeclares("minecraft:celebrate_hunt")) {
            return;
        }

        for (AddonEntity hunter : quarry.level().getEntitiesOfClass(AddonEntity.class,
            quarry.getBoundingBox().inflate(CELEBRATION_RANGE))) {
            hunter.celebrateTheHunt(quarry);
        }
    }

    /** The widest radius `minecraft:celebrate_hunt` is given, which bounds the search for hunters. */
    private static final double CELEBRATION_RANGE = 32;

    private void celebrateTheHunt(net.minecraft.world.entity.LivingEntity quarry) {
        if (definition == null || quarry == this || !declares("minecraft:celebrate_hunt")) return;

        double radius = number(definition, "minecraft:celebrate_hunt", "radius", 16);
        if (distanceToSqr(quarry) > radius * radius) return;

        Filter celebrated = Filter.parse(componentBody("minecraft:celebrate_hunt")
            .get("celebration_targets"));
        if (!celebrated.matches(new Filter.Context(this, quarry, quarry, ServerHooks.state()))) return;

        celebrateUntilTick = tickCount
            + (int) (number(definition, "minecraft:celebrate_hunt", "duration", 4)
                * Goals.TICKS_PER_SECOND);
        ServerHooks.state().setProperty(getUUID(), "is_celebrating", true);
    }

    /** Cheers for as long as the hunt's celebration lasts, and stops cheering when it is over. */
    private void celebrateTheKill() {
        if (celebrateUntilTick < 0) return;

        if (tickCount >= celebrateUntilTick) {
            celebrateUntilTick = -1;
            ServerHooks.state().setProperty(getUUID(), "is_celebrating", false);
            return;
        }
        if (tickCount % Goals.TICKS_PER_SECOND != 0) return;

        playSound(sound(text("minecraft:celebrate_hunt", "celebrate_sound"),
            SoundEvents.VILLAGER_CELEBRATE), getSoundVolume(), getVoicePitch());
    }

    /**
     * Tells every entity watching for it that one of the blocks it cares about was broken.
     *
     * `minecraft:block_sensor` is how a Bedrock piglin knows somebody opened a chest near it. Java
     * raises breaking once, from the block rather than from anything watching, so the entities in
     * range are found from the block's own position instead.
     */
    public static void noticeBlockBroken(net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos broken, net.minecraft.world.level.block.state.BlockState state) {
        if (level.isClientSide() || !AddonEntities.anyoneDeclares("minecraft:block_sensor")) return;

        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(state.getBlock()).toString();
        for (AddonEntity nearby : level.getEntitiesOfClass(AddonEntity.class,
            new net.minecraft.world.phys.AABB(broken).inflate(BLOCK_SENSOR_RANGE))) {
            nearby.noticeBrokenBlock(broken, name);
        }
    }

    /** The widest radius `minecraft:block_sensor` is given, which bounds the search for watchers. */
    private static final double BLOCK_SENSOR_RANGE = 32;

    /** Fires whatever this entity's sensor names for the block that was broken, if it is in range. */
    private void noticeBrokenBlock(net.minecraft.core.BlockPos broken, String name) {
        if (definition == null || !declares("minecraft:block_sensor")) return;

        double radius = number(definition, "minecraft:block_sensor", "sensor_radius", 16);
        if (blockPosition().distSqr(broken) > radius * radius) return;

        for (com.google.gson.JsonElement element : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:block_sensor"), "on_break")) {
            if (!element.isJsonObject()) continue;

            com.google.gson.JsonObject entry = element.getAsJsonObject();
            boolean listed = at.ioexception.mcaddon.util.Json.list(entry, "block_list").stream()
                .filter(com.google.gson.JsonElement::isJsonPrimitive)
                .map(com.google.gson.JsonElement::getAsString)
                .anyMatch(listedName -> (listedName.contains(":")
                    ? listedName : "minecraft:" + listedName).equals(name));
            if (listed) fire(Goals.event(entry, "on_block_broken"));
        }
    }

    /** A block descriptor names itself either bare or under `name`, in both shapes a pack writes. */
    private static String blockNameOf(com.google.gson.JsonObject entry) {
        com.google.gson.JsonElement block = entry.get("block");
        String named = block == null ? null
            : block.isJsonPrimitive() ? block.getAsString()
            : block.isJsonObject()
                ? at.ioexception.mcaddon.util.Json.text(block.getAsJsonObject(), "name", null) : null;
        if (named == null) return null;

        return named.contains(":") ? named : "minecraft:" + named;
    }

    /** The sound a pack named, or the one the game already plays for this when it named none. */
    private static net.minecraft.sounds.SoundEvent sound(String named,
        net.minecraft.sounds.SoundEvent fallback) {
        Identifier id = named == null ? null
            : Identifier.tryParse(named.contains(":") ? named : "minecraft:" + named);
        return id == null ? fallback
            : net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getOptional(id)
                .orElse(fallback);
    }

    /**
     * Fires what `minecraft:on_equipment_changed` names as a slot is filled or emptied.
     *
     * Bedrock raises this from the equipping itself, which on Java is every path that writes a slot,
     * so the slots the pack listed are compared against what was in them last tick instead.
     */
    private void noticeEquipmentChanging() {
        if (!declares("minecraft:on_equipment_changed")) return;

        for (com.google.gson.JsonElement element : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:on_equipment_changed"), "slots")) {
            if (!element.isJsonObject()) continue;

            com.google.gson.JsonObject slot = element.getAsJsonObject();
            String named = at.ioexception.mcaddon.util.Json.text(slot, "slot", null);
            net.minecraft.world.entity.EquipmentSlot where = equipmentSlot(named);
            if (where == null) continue;

            ItemStack now = getItemBySlot(where);
            ItemStack was = wornLastTick.put(where, now.copy());
            if (was == null || ItemStack.isSameItem(was, now)) continue;

            fire(at.ioexception.mcaddon.util.Json.text(slot,
                now.isEmpty() ? "on_unequip" : "on_equip", null));
        }
    }

    private final java.util.Map<net.minecraft.world.entity.EquipmentSlot, ItemStack> wornLastTick =
        new java.util.EnumMap<>(net.minecraft.world.entity.EquipmentSlot.class);

    /**
     * The Java slot a Bedrock slot name means.
     *
     * Bedrock writes `slot.weapon.mainhand` and `slot.armor.chest`, and
     * `minecraft:entity_armor_equipment_slot_mapping` is how a pack says which armour slot its
     * second equippable slot stands for. Both reach Java as the same enum.
     */
    private static net.minecraft.world.entity.EquipmentSlot equipmentSlot(String bedrock) {
        if (bedrock == null) return null;

        return switch (bedrock) {
            case "slot.weapon.mainhand" -> net.minecraft.world.entity.EquipmentSlot.MAINHAND;
            case "slot.weapon.offhand" -> net.minecraft.world.entity.EquipmentSlot.OFFHAND;
            case "slot.armor.head" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
            case "slot.armor.chest", "slot.armor.torso" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
            case "slot.armor.legs" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
            case "slot.armor.feet" -> net.minecraft.world.entity.EquipmentSlot.FEET;
            default -> null;
        };
    }

    /** `minecraft:on_ignite` names what a pack does about its entity being set alight. */
    private void noticeCatchingFire() {
        boolean alight = getRemainingFireTicks() > 0;
        boolean caught = alight && !wasAlight;
        wasAlight = alight;
        if (caught) fire(text("minecraft:on_ignite", "event"));
    }

    private boolean wasAlight;
    private boolean wasOnGround = true;

    /**
     * `minecraft:on_start_takeoff` and `minecraft:on_start_landing` are the two edges of a flight.
     *
     * Bedrock raises them from its own flying navigation, which a pack's entity reaches only when it
     * asked for one. Whether the entity is standing on something is the same question either way.
     */
    private void noticeLeavingTheGround() {
        boolean grounded = onGround();
        if (grounded == wasOnGround) return;

        wasOnGround = grounded;
        fire(text(grounded ? "minecraft:on_start_landing" : "minecraft:on_start_takeoff", "event"));
    }

    /**
     * Moves the entity at the speed the pack said its medium allows.
     *
     * Bedrock states a speed per medium as its own component and Java has one movement speed, so the
     * attribute is moved as the entity enters and leaves, and put back on land.
     */
    private void swimAtItsOwnSpeed() {
        if (!declares("minecraft:lava_movement") && !declares("minecraft:underwater_movement")
            && !declares("minecraft:water_movement")) {
            return;
        }

        String component = null;
        if (isInLava() && declares("minecraft:lava_movement")) {
            component = "minecraft:lava_movement";
        } else if (isInWater() && declares("minecraft:underwater_movement")) {
            component = "minecraft:underwater_movement";
        } else if (isInWater() && declares("minecraft:water_movement")) {
            component = "minecraft:water_movement";
        }

        net.minecraft.world.entity.ai.attributes.AttributeInstance speed =
            getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;

        double wanted = component == null ? definition.movementSpeed()
            : number(definition, component, "value", definition.movementSpeed());
        if (speed.getBaseValue() != wanted) speed.setBaseValue(wanted);
    }

    /** `minecraft:air_drag_modifier` is how quickly the entity loses the speed it left the ground with. */
    private void dragThroughAir() {
        if (!declares("minecraft:air_drag_modifier") || onGround()) return;

        double kept = number(definition, "minecraft:air_drag_modifier", "value", 1);
        setDeltaMovement(getDeltaMovement().multiply(kept, 1, kept));
    }

    /**
     * `minecraft:friction_modifier` is how much of the ground's grip this entity feels.
     *
     * Java takes friction from the block rather than from whatever is standing on it, so what the
     * modifier changes here is the speed the entity keeps between two ticks: above one it stops
     * sooner than the ground alone would stop it, and below one it slides further.
     */
    private void rubAlongTheGround() {
        if (!declares("minecraft:friction_modifier") || !onGround()) return;

        double modifier = number(definition, "minecraft:friction_modifier", "value", 1);
        if (modifier == 1 || modifier < 0) return;

        double kept = Math.max(0, Math.min(1, 1 - (modifier - 1) * FRICTION_PER_STEP));
        setDeltaMovement(getDeltaMovement().multiply(kept, 1, kept));
    }

    /** How much of its speed an entity loses per tick for each point of friction above one. */
    private static final double FRICTION_PER_STEP = 0.2;

    /** `minecraft:rotation_axis_aligned` keeps the entity square to the world rather than turning freely. */
    private void faceTheGrid() {
        if (!declares("minecraft:rotation_axis_aligned")) return;

        float square = Math.round(getYRot() / 90f) * 90f;
        setYRot(square);
        setYBodyRot(square);
        setYHeadRot(square);
    }

    /**
     * `minecraft:flocking` steers the entity towards the others of its kind swimming around it.
     *
     * Bedrock states a dozen weights for how tightly a school holds together. What carries across is
     * the school itself: an entity outside the distance the pack allows is pulled back towards the
     * middle of whatever it can see.
     */
    private void flock() {
        if (!declares("minecraft:flocking")) return;

        double influence = number(definition, "minecraft:flocking", "influence_radius", 3);
        double innerRadius = number(definition, "minecraft:flocking", "inner_cohesion_radius", 0.5);
        java.util.List<AddonEntity> school = level().getEntitiesOfClass(AddonEntity.class,
            getBoundingBox().inflate(influence),
            other -> other != this && other.getType() == getType());
        if (school.isEmpty()) return;

        net.minecraft.world.phys.Vec3 middle = net.minecraft.world.phys.Vec3.ZERO;
        for (AddonEntity other : school) middle = middle.add(other.position());
        middle = middle.scale(1d / school.size());

        net.minecraft.world.phys.Vec3 towards = middle.subtract(position());
        double apart = towards.length();
        if (apart < innerRadius) return;

        double pull = number(definition, "minecraft:flocking", "goal_weight", 0.4) * FLOCK_PULL;
        setDeltaMovement(getDeltaMovement().add(towards.normalize().scale(pull)));
    }

    /** How hard one tick of schooling pulls, at the weight a pack states as one. */
    private static final double FLOCK_PULL = 0.01;

    /**
     * `minecraft:boostable` lets whoever is riding spend an item to go faster for a while.
     *
     * The item is the rider's own and the speed is the mount's, so the two only meet while somebody
     * is aboard. Bedrock spends durability off the item and can swap it for another; both are what
     * a carrot on a stick does in Java as well.
     */
    private void beBoosted() {
        if (!declares("minecraft:boostable")) return;

        if (boostedUntilTick > tickCount) {
            double faster = number(definition, "minecraft:boostable", "speed_multiplier", 1.35) - 1;
            setDeltaMovement(getDeltaMovement().multiply(1 + faster, 1, 1 + faster));
            return;
        }
        if (!(getControllingPassenger() instanceof Player rider)) return;

        for (com.google.gson.JsonElement element : at.ioexception.mcaddon.util.Json.list(
            componentBody("minecraft:boostable"), "boost_items")) {
            if (!element.isJsonObject()) continue;
            if (!spendBoost(rider, element.getAsJsonObject())) continue;

            boostedUntilTick = tickCount
                + (int) (number(definition, "minecraft:boostable", "duration", 3)
                    * Goals.TICKS_PER_SECOND);
            return;
        }
    }

    /** Whether the rider was holding this boost item, and has now paid for the boost with it. */
    private boolean spendBoost(Player rider, com.google.gson.JsonObject entry) {
        net.minecraft.world.item.Item wanted =
            itemNamed(at.ioexception.mcaddon.util.Json.text(entry, "item", null));
        net.minecraft.world.item.ItemStack held = rider.getMainHandItem();
        if (wanted == null || held.getItem() != wanted) return false;

        net.minecraft.world.item.Item becomes =
            itemNamed(at.ioexception.mcaddon.util.Json.text(entry, "replace_item", null));
        if (becomes != null) {
            rider.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(becomes));
        } else {
            held.shrink(1);
        }
        return true;
    }

    /** A Bedrock item name, which is as often written without its namespace as with it. */
    private static net.minecraft.world.item.Item itemNamed(String bedrock) {
        if (bedrock == null) return null;

        return at.ioexception.mcaddon.registry.AddonItems.named(
            bedrock.contains(":") ? bedrock : "minecraft:" + bedrock);
    }

    /**
     * `minecraft:vibration_damper` is an entity nothing hears.
     *
     * Java raises a game event for everything an entity does, and sculk and a warden both listen to
     * those, so an entity that raises none is one that makes no vibration.
     */
    //? if >=1.21 {
    @Override
    public void gameEvent(net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event,
        net.minecraft.world.entity.Entity cause) {
        if (definition != null && declares("minecraft:vibration_damper")) return;

        super.gameEvent(event, cause);
    }
    //?} else {
    /*@Override
    public void gameEvent(net.minecraft.world.level.gameevent.GameEvent event,
        net.minecraft.world.entity.Entity cause) {
        if (definition != null && declares("minecraft:vibration_damper")) return;

        super.gameEvent(event, cause);
    }
    *///?}

    /** `minecraft:rotation_locked_to_vehicle` keeps a rider facing wherever it is being carried. */
    private void faceTheVehicle() {
        if (!declares("minecraft:rotation_locked_to_vehicle") || getVehicle() == null) return;

        float yaw = getVehicle().getYRot();
        setYRot(yaw);
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    /**
     * Puts the rider `minecraft:addrider` names on the entity's back, once it is in the world.
     *
     * The rider has to be spawned rather than declared, and an entity that is still being built has
     * nowhere to put one, so this waits for the entity's first tick in a level.
     */
    private void addRider() {
        if (!declares("minecraft:addrider") || carriesRider) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        carriesRider = true;
        String named = text("minecraft:addrider", "entity_type");
        net.minecraft.world.entity.EntityType<?> type = named == null
            ? null : AddonEntities.typeOf(named);
        net.minecraft.world.entity.Entity rider = type == null ? null : Platform.createMob(type, level);
        if (rider == null) return;

        Platform.placeAt(rider, getX(), getY(), getZ(), getYRot(), 0f);
        level.addFreshEntity(rider);
        rider.startRiding(this);

        String event = text("minecraft:addrider", "spawn_event");
        if (event != null) EntityEvents.trigger(rider, event, this, ServerHooks.state());
    }

    private boolean carriesRider;

    /**
     * Counts the entity's growing up, and fires the event the pack named at the end of it.
     *
     * Bedrock states the wait in seconds and this runs once a tick, which is the same clock.
     */
    private void growUp() {
        if (age == null || !age.advance(1)) return;

        ServerHooks.state().setProperty(getUUID(), "is_baby", false);
        fire(age.grownEvent());
    }

    /**
     * Being handed one of the foods `minecraft:healable` names, which puts the health back.
     *
     * `force_use` is what lets a pack have the food eaten even by an entity that is already whole,
     * which matters when the entry also carries effects.
     */
    private boolean healed(Player player, InteractionHand hand) {
        if (!declares("minecraft:healable")) return false;

        ItemStack held = player.getItemInHand(hand);
        boolean forced = number(definition, "minecraft:healable", "force_use", 0) != 0;
        if (held.isEmpty() || (!forced && getHealth() >= getMaxHealth())) return false;
        if (!Filter.parse(componentBody("minecraft:healable").get("filters"))
            .matches(new Filter.Context(this, player, null, ServerHooks.state()))) {
            return false;
        }

        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        for (com.google.gson.JsonElement element
            : at.ioexception.mcaddon.util.Json.list(componentBody("minecraft:healable"), "items")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject food = element.getAsJsonObject();
            if (!names(at.ioexception.mcaddon.util.Json.text(food, "item", null), id)) continue;

            heal(food.has("heal_amount") ? food.get("heal_amount").getAsFloat() : 1);
            if (!player.getAbilities().instabuild) held.shrink(1);
            return true;
        }
        return false;
    }

    /**
     * Taming by hand, which both of Bedrock's components describe as feeding an entity the right food.
     *
     * `minecraft:tameable` gives the chance outright; `minecraft:tamemount` counts temper towards a
     * ceiling. Either way what a player sees is a few tries and then an entity that belongs to them,
     * so the chance stands in for the count.
     */
    private boolean tamedBy(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return false;

        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        for (String component
            : new String[] {"minecraft:tameable", "minecraft:tamemount", "minecraft:trusting"}) {
            if (!declares(component) || !foodOf(component, id)) continue;

            if (!player.getAbilities().instabuild) held.shrink(1);
            if (random.nextDouble() > number(definition, component, "probability", 0.33)) return true;

            ServerHooks.state().setProperty(getUUID(), "is_tamed", true);
            setOwner(player);
            fire(namedEvent(component, component.endsWith("trusting") ? "trust_event" : "tame_event"));
            return true;
        }
        return false;
    }

    /** Whether the item offered is one of the foods a taming or trusting component named. */
    private boolean foodOf(String component, String offered) {
        for (String key : new String[] {"tame_items", "feed_items", "trust_items", "items"}) {
            for (com.google.gson.JsonElement element
                : at.ioexception.mcaddon.util.Json.list(componentBody(component), key)) {
                if (element.isJsonPrimitive() && names(element.getAsString(), offered)) return true;
                if (!element.isJsonObject()) continue;

                if (names(at.ioexception.mcaddon.util.Json.text(
                    element.getAsJsonObject(), "item", null), offered)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The event named under one of a component's own `*_event` objects, or null when it names none. */
    private String namedEvent(String component, String key) {
        com.google.gson.JsonObject wrapper = componentBody(component).getAsJsonObject(key);
        return wrapper == null ? null : at.ioexception.mcaddon.util.Json.text(wrapper, "event", null);
    }

    /**
     * Putting an item on the entity, which is what `minecraft:equippable` lets a player do.
     *
     * Bedrock lists the slots and what each accepts; what a pack uses this for is a saddle or a
     * piece of armour on something Java would not otherwise let a player dress.
     */
    private boolean equippedBy(Player player, InteractionHand hand) {
        if (!declares("minecraft:equippable")) return false;

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return false;

        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        java.util.List<com.google.gson.JsonElement> slots =
            at.ioexception.mcaddon.util.Json.list(componentBody("minecraft:equippable"), "slots");
        for (int index = 0; index < slots.size(); index++) {
            if (!slots.get(index).isJsonObject()) continue;
            com.google.gson.JsonObject slot = slots.get(index).getAsJsonObject();
            if (!names(at.ioexception.mcaddon.util.Json.text(slot, "item", null), id)) continue;

            setItemSlot(slotFor(held, index), held.split(1));
            fire(at.ioexception.mcaddon.util.Json.text(slot, "on_equip", null));
            return true;
        }
        return false;
    }

    /**
     * Where an equippable slot's item goes.
     *
     * Java picks the slot off the item itself, which is right for a helmet and for a saddle alike.
     * Bedrock's second equippable slot has no Java counterpart, and
     * `minecraft:entity_armor_equipment_slot_mapping` is where a pack says which armour slot it
     * means; without one the game puts it on the chest, the same as Bedrock's own older worlds.
     */
    private net.minecraft.world.entity.EquipmentSlot slotFor(ItemStack held, int index) {
        if (index != 1 || !declares("minecraft:entity_armor_equipment_slot_mapping")) {
            return getEquipmentSlotForItem(held);
        }

        net.minecraft.world.entity.EquipmentSlot mapped =
            equipmentSlot(text("minecraft:entity_armor_equipment_slot_mapping", "slot"));
        return mapped == null ? net.minecraft.world.entity.EquipmentSlot.CHEST : mapped;
    }

    /**
     * Handing the entity something it was waiting for, which raises the pack's own event.
     *
     * `minecraft:giveable` is how a pack trades without a trade table: the entity takes the item and
     * its event decides what that was worth.
     */
    private boolean givenTo(Player player, InteractionHand hand) {
        if (!declares("minecraft:giveable")) return false;

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return false;

        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        for (com.google.gson.JsonElement element
            : at.ioexception.mcaddon.util.Json.list(componentBody("minecraft:giveable"), "triggers")) {
            if (!element.isJsonObject()) continue;
            com.google.gson.JsonObject trigger = element.getAsJsonObject();

            boolean wanted = at.ioexception.mcaddon.util.Json.list(trigger, "items").stream()
                .anyMatch(item -> item.isJsonPrimitive() && names(item.getAsString(), id));
            if (!wanted) continue;

            if (!player.getAbilities().instabuild) held.shrink(1);
            com.google.gson.JsonObject given = trigger.getAsJsonObject("on_give");
            fire(given == null ? null : at.ioexception.mcaddon.util.Json.text(given, "event", null));
            return true;
        }
        return false;
    }

    /** `minecraft:sittable` is an entity a player sits down and stands up again by clicking it. */
    private boolean satDown(Player player) {
        if (!declares("minecraft:sittable") || !player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
            return false;
        }

        boolean sitting = getPose() == net.minecraft.world.entity.Pose.SITTING;
        setPose(sitting ? net.minecraft.world.entity.Pose.STANDING
            : net.minecraft.world.entity.Pose.SITTING);
        if (!sitting) getNavigation().stop();

        fire(namedEvent("minecraft:sittable", sitting ? "stand_event" : "sit_event"));
        return true;
    }

    /** Bedrock writes an item without its namespace as often as with one, and means the same thing. */
    private static boolean names(String bedrock, String javaId) {
        return bedrock != null && (bedrock.equals(javaId) || ("minecraft:" + bedrock).equals(javaId));
    }

    /** One of `minecraft:ageable`'s own feed items, which brings growing up forward. */
    private boolean grownByHand(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (age == null || !age.fed(held)) return false;

        if (!player.getAbilities().instabuild) held.shrink(1);
        for (int i = 0; i < 5; i++) {
            level().addParticle(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                getRandomX(1), getRandomY() + 0.5, getRandomZ(1), 0, 0, 0);
        }
        return true;
    }

    /**
     * Keeps the entity's body where the pack said it should be.
     *
     * `minecraft:body_rotation_blocked` is a body that never turns and
     * `minecraft:body_rotation_always_follows_head` is one that turns with every glance, which are
     * the two ends of what Java otherwise eases between.
     */
    private void holdBody() {
        if (declares("minecraft:body_rotation_blocked")) {
            setYBodyRot(yBodyRotO);
        } else if (declares("minecraft:body_rotation_always_follows_head")) {
            setYBodyRot(getYHeadRot());
        }
    }

    /** Throws the entity back up off the ground by as much as `minecraft:bounciness` asks. */
    private void bounce() {
        if (!declares("minecraft:bounciness") || !onGround()) return;

        double fell = -getDeltaMovement().y;
        if (fell <= 0.1) return;

        double kept = number(definition, "minecraft:bounciness", "bounciness_value", 0.9);
        setDeltaMovement(getDeltaMovement().x, fell * kept, getDeltaMovement().z);
        fallDistance = 0;
    }

    /**
     * Takes a boss bar off every screen once the entity it belongs to is gone.
     *
     * A bar is drawn by whoever was told about it and nothing else takes it back, so an entity that
     * died, despawned or left with its chunk would otherwise leave one standing at zero.
     */
    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        super.remove(reason);
        if (bossBar != null) bossBar.removeAllPlayers();
    }

    /** Shows a pack's boss its bar, to everybody inside the range the component named. */
    private void showBossBar() {
        if (bossBar == null || !(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        double range = number(definition, "minecraft:boss", "hud_range", 55);

        java.util.List<net.minecraft.server.level.ServerPlayer> watching =
            level.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                getBoundingBox().inflate(range));
        java.util.List.copyOf(bossBar.getPlayers()).stream()
            .filter(player -> !watching.contains(player))
            .forEach(bossBar::removePlayer);
        watching.forEach(bossBar::addPlayer);
    }

    /**
     * Fires the event `minecraft:on_hurt` names, on the tick the entity's health drops.
     *
     * Bedrock raises this from inside the damage itself. Java's own damage events differ across the
     * versions this runs on, and the health the entity is left with does not, so that is watched.
     */
    private void noticeBeingHurt() {
        float health = getHealth();
        if (healthLastTick < 0) {
            healthLastTick = health;
            return;
        }

        boolean hurt = health < healthLastTick;
        float lost = healthLastTick - health;
        healthLastTick = health;
        if (!hurt) return;

        lastHurtTick = tickCount;
        recordDisturbance(getLastHurtByMob(), lost);
        annoy(getLastHurtByMob());
        raise("minecraft:on_hurt", getLastHurtByMob());
        if (getLastHurtByMob() instanceof Player) raise("minecraft:on_hurt_by_player", getLastHurtByMob());
    }

    /** How many attackers are kept a running total for, past which the smallest is dropped. */
    private static final int ATTACKERS_KEPT = 8;

    /** Where the entity was last hurt from, which is the one place a mob has reason to go and see. */
    private net.minecraft.core.BlockPos disturbance;

    /** What each attacker has taken off the entity, for the goal that answers the worst of them. */
    private final java.util.Map<java.util.UUID, Float> damageTaken = new java.util.LinkedHashMap<>();

    /** Where something last hurt the entity from, or null while nothing has. */
    public net.minecraft.core.BlockPos disturbance() {
        return disturbance;
    }

    /** Clears the place, so an entity that has been to look does not keep going back. */
    public void forgetDisturbance() {
        disturbance = null;
    }

    /** Whoever has taken the most health off the entity, or null while nobody has taken any. */
    public java.util.UUID worstAttacker() {
        return damageTaken.entrySet().stream()
            .max(java.util.Map.Entry.comparingByValue())
            .map(java.util.Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Notes where a hit came from and what it cost, on the tick the entity's health drops.
     *
     * The tally is kept to a handful of attackers by dropping the one that has done least, so an
     * entity standing in a crowd for an hour holds a fixed number of entries rather than one per
     * thing that has ever touched it.
     */
    private void recordDisturbance(net.minecraft.world.entity.Entity attacker, float lost) {
        if (attacker == null) return;

        disturbance = attacker.blockPosition();
        damageTaken.merge(attacker.getUUID(), lost, Float::sum);
        if (damageTaken.size() <= ATTACKERS_KEPT) return;

        damageTaken.entrySet().stream()
            .min(java.util.Map.Entry.comparingByValue())
            .map(java.util.Map.Entry::getKey)
            .ifPresent(damageTaken::remove);
    }

    /**
     * Counts a hit towards the entity's temper, when the pack says this is somebody worth minding.
     *
     * `minecraft:anger_level` splits a provocation by whether it arrived from a distance, which is
     * the difference between being shot at and being hit, so the reach decides which it was.
     */
    private void annoy(net.minecraft.world.entity.Entity nuisance) {
        if (anger == null || nuisance == null) return;
        if (!anger.annoyedBy(new Filter.Context(this, nuisance, nuisance, ServerHooks.state()))) {
            return;
        }

        anger.provoke(distanceTo(nuisance) > getBbWidth() + 3);
        setValue("anger_level", (double) anger.level());
        setValue("is_angry", anger.angry());
    }

    /** Runs the event a component names, against whoever brought it about. */
    private void raise(String component, net.minecraft.world.entity.Entity other) {
        String event = text(component, "event");
        if (event != null) EntityEvents.trigger(this, event, other, ServerHooks.state());
    }

    /**
     * What a pack asked for as the entity dies: its own event, what it leaves behind, and the bang.
     *
     * `minecraft:spawn_on_death` and `minecraft:explode` both happen here because both are a thing
     * Bedrock does at the moment of death rather than a behaviour the entity carries out.
     */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        super.die(source);
        if (definition == null || !(level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        String event = text("minecraft:on_death", "event");
        if (event != null) EntityEvents.trigger(this, event, source.getEntity(), ServerHooks.state());

        // What a mob was hauling belongs on the ground once it is dead, the same as a chest's does.
        if (carried != null) net.minecraft.world.Containers.dropContents(level, blockPosition(), carried);
        spawnOnDeath(level);
        explode(level);
    }

    /** `minecraft:spawn_on_death` leaves another entity where this one fell. */
    private void spawnOnDeath(net.minecraft.server.level.ServerLevel level) {
        String named = text("minecraft:spawn_on_death", "id");
        net.minecraft.world.entity.EntityType<?> type = named == null
            ? null : AddonEntities.typeOf(named);
        net.minecraft.world.entity.Entity born = type == null ? null : Platform.createMob(type, level);
        if (born == null) return;

        Platform.placeAt(born, getX(), getY(), getZ(), getYRot(), 0f);
        level.addFreshEntity(born);
    }

    /**
     * `minecraft:explode` goes off where the entity was.
     *
     * Bedrock describes the fuse as well as the power, and a fuse only matters while the entity is
     * still alive to count it; what a pack uses this for is the blast, which is what happens here.
     */
    void detonate() {
        if (level() instanceof net.minecraft.server.level.ServerLevel level) {
            explode(level);
            discard();
        }
    }

    private void explode(net.minecraft.server.level.ServerLevel level) {
        if (!declares("minecraft:explode")) return;

        boolean breaks = number(definition, "minecraft:explode", "breaks_blocks", 1) != 0;
        boolean burns = number(definition, "minecraft:explode", "causes_fire", 0) != 0;
        level.explode(this, getX(), getY(), getZ(),
            (float) number(definition, "minecraft:explode", "power", 3), burns,
            breaks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);
    }

    /**
     * Fires the events a pack asked for as the entity picks something to hunt, or loses it.
     *
     * Bedrock raises these from the targeting itself, which on Java is every goal that sets one, so
     * the entity is asked here instead of each goal being wrapped.
     */
    @Override
    public void setTarget(net.minecraft.world.entity.LivingEntity target) {
        net.minecraft.world.entity.LivingEntity had = getTarget();
        if (target != null && had == null && coolingDown()) return;

        super.setTarget(target);
        if (definition == null || !(level() instanceof net.minecraft.server.level.ServerLevel)) return;
        if (had == target) return;

        if (target == null && had != null) startAttackCooldown();

        String event = target != null
            ? text("minecraft:on_target_acquired", "event")
            : text("minecraft:on_target_escape", "event");
        if (event != null) {
            EntityEvents.trigger(this, event, target == null ? had : target, ServerHooks.state());
        }
    }

    /**
     * How big the entity is drawn, which `minecraft:scale_by_age` moves as it grows up.
     *
     * 1.20.1 has one scale for the whole entity; from 1.21 the part that grows with age is asked
     * for on its own, and the whole scale is final from 26.
     */
    //? if >=1.21 {
    @Override
    public float getAgeScale() {
        return age == null ? super.getAgeScale() : age.scale();
    }
    //?} else {
    /*@Override
    public float getScale() {
        return age == null ? super.getScale() : age.scale();
    }
    *///?}

    /**
     * Sets the entity alight while the sun is on it, the way a Bedrock undead mob burns.
     *
     * The helmet exemption is Java's rule for its own undead and the one a player expects, so a
     * pack's zombie behaves like the game's beside it.
     */
    private void burnInDaylight() {
        if (!declares("minecraft:burns_in_daylight") || isInWaterOrRain()) return;
        if (Platform.dayTime(level()) % 24000L >= 12000L) return;
        if (!level().canSeeSkyFromBelowWater(blockPosition())) return;
        if (!getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty()) return;

        //? if >=1.21 {
        igniteForSeconds(8);
        //?} else {
        /*setSecondsOnFire(8);
        *///?}
    }

    /**
     * Wears the entity down at the rate `minecraft:damage_over_time` names.
     *
     * Bedrock counts the gap between hits in seconds and this runs on the sensor interval, so the
     * gap is rounded to whole intervals rather than to ticks.
     */
    private void hurtOverTime() {
        if (!declares("minecraft:damage_over_time")
            || !(level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        int seconds = (int) number(definition, "minecraft:damage_over_time", "time_between_hurt", 1);
        int interval = Math.max(Sensors.INTERVAL_TICKS, seconds * 20);
        if (tickCount % interval >= Sensors.INTERVAL_TICKS) return;

        Platform.hurt(this, level, damageSources().magic(),
            (float) number(definition, "minecraft:damage_over_time", "damage_per_hurt", 1));
    }

    /** Bedrock states the gap between idle sounds in seconds; Java counts it in ticks. */
    @Override
    public int getAmbientSoundInterval() {
        return definition == null || !declares("minecraft:ambient_sound_interval")
            ? super.getAmbientSoundInterval()
            : (int) (number(definition, "minecraft:ambient_sound_interval", "value", 8) * 20);
    }

    @Override
    protected float getSoundVolume() {
        return definition == null || !declares("minecraft:sound_volume") ? super.getSoundVolume()
            : (float) number(definition, "minecraft:sound_volume", "value", 1);
    }

    @Override
    public boolean isAttackable() {
        return (definition == null || !declares("minecraft:cannot_be_attacked")) && super.isAttackable();
    }

    /** The tick of this entity's own age at which `minecraft:attack_cooldown` lets it hunt again. */
    private int huntingAllowedAt;

    private boolean coolingDown() {
        if (definition == null || !declares("minecraft:attack_cooldown")) return false;
        if (tickCount < huntingAllowedAt) return true;
        if (huntingAllowedAt <= 0) return false;

        huntingAllowedAt = 0;
        fire(namedEvent("minecraft:attack_cooldown", "attack_cooldown_complete_event"));
        return false;
    }

    /**
     * Starts the wait `minecraft:attack_cooldown` puts between one fight and the next.
     *
     * Bedrock states it as a number or as the two ends of a range, and what it buys the player is a
     * mob that gives up rather than turning straight to whoever is next.
     */
    private void startAttackCooldown() {
        if (!declares("minecraft:attack_cooldown")) return;

        com.google.gson.JsonObject component = componentBody("minecraft:attack_cooldown");
        huntingAllowedAt = tickCount + Goals.between(random,
            Goals.ticksLow(component, "attack_cooldown_time", 0),
            Goals.ticksHigh(component, "attack_cooldown_time", 1));
    }

    /**
     * Whether this entity may pick a fight with something that says it cannot be attacked.
     *
     * `minecraft:ignore_cannot_be_attacked` is the exception a pack writes for the one entity that
     * is allowed through, so the filter decides per candidate rather than per entity.
     */
    @Override
    public boolean canAttack(net.minecraft.world.entity.LivingEntity target) {
        if (definition != null && declares("minecraft:ignore_cannot_be_attacked")
            && Filter.parse(componentBody("minecraft:ignore_cannot_be_attacked").get("filters"))
                .matches(new Filter.Context(this, target, target, ServerHooks.state()))) {
            return true;
        }
        return super.canAttack(target);
    }

    /** `minecraft:transient` marks an entity the world is not meant to hold on to. */
    @Override
    public boolean shouldBeSaved() {
        return (definition == null || !declares("minecraft:transient")) && super.shouldBeSaved();
    }

    /**
     * The effects `minecraft:mob_effect_immunity` names bounce off.
     *
     * Bedrock lists the effects by their own names, which carry no namespace, so both spellings are
     * compared against the effect Java is about to apply.
     */
    @Override
    public boolean canBeAffected(net.minecraft.world.effect.MobEffectInstance effect) {
        com.google.gson.JsonObject component = definition == null
            ? null : definition.components().get("minecraft:mob_effect_immunity");
        if (component == null) return super.canBeAffected(effect);

        String id = Platform.effectId(effect);
        for (com.google.gson.JsonElement named
            : at.ioexception.mcaddon.util.Json.list(component, "mob_effects")) {
            if (!named.isJsonPrimitive()) continue;

            String wanted = named.getAsString();
            if (wanted.equals(id) || ("minecraft:" + wanted).equals(id)) return false;
        }
        return super.canBeAffected(effect);
    }

    /**
     * Whether other entities walk into this one or through it.
     *
     * Bedrock splits the question in two: `is_collidable` decides whether anything collides at all,
     * and `pushable` decides whether a collision moves this entity. Java asks only the second, so
     * an entity a pack made non-collidable is one nothing can push.
     */
    @Override
    public boolean isPushable() {
        if (definition == null) return super.isPushable();
        if (declares("minecraft:is_collidable")
            && number(definition, "minecraft:is_collidable", "value", 1) == 0) {
            return false;
        }
        return !declares("minecraft:pushable")
            || number(definition, "minecraft:pushable", "is_pushable", 1) != 0;
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
        return age != null && age.baby();
    }

    /**
     * What a right click does: a pack's own interaction first, then its trade table.
     *
     * `minecraft:interact` is how a pack makes an entity respond to being used, and each interaction
     * carries the filters that decide whether it applies and the event it fires.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // `minecraft:not_pickable_from_inside` is an entity a rider cannot reach from the seat.
        if (definition != null && declares("minecraft:not_pickable_from_inside")
            && player.getVehicle() == this) {
            return InteractionResult.PASS;
        }
        if (!isClientSide() && bredBySpawnEgg(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && fed(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && healed(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && tamedBy(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && grownByHand(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && equippedBy(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && givenTo(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && satDown(player)) return InteractionResult.SUCCESS;
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
