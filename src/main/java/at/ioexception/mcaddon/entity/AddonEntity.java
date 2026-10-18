package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Despawn;
import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.SpawnRuleDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.TargetSelection;
import at.ioexception.mcaddon.bedrock.behaviour.TradeTable;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.registry.AddonSpawns;
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
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RestrictSunGoal;
import net.minecraft.world.entity.ai.goal.StrollThroughVillageGoal;
import net.minecraft.world.entity.ai.goal.TryFindWaterGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
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
        net.minecraft.world.entity.OwnableEntity,
        //? if >=26.2 {
        net.minecraft.world.entity.Bucketable {
        //?} else {
        /*net.minecraft.world.entity.animal.Bucketable {
        *///?}
    private final ServerEntityDefinition definition;
    private final java.util.Set<String> activeGroups = new java.util.LinkedHashSet<>();
    private final java.util.Map<String, Integer> sensorReady = new java.util.HashMap<>();
    private final java.util.Set<String> spent = new java.util.HashSet<>();
    private int timerDueTick = -1;
    private int calmAtTick = -1;
    private int admireUntilTick = -1;
    private String lastBlockInside;
    private int celebrateUntilTick = -1;
    private int boostedUntilTick = -1;
    private boolean boosting;
    private int dryTicks;
    private int schedulerDueTick = -1;
    private String spawnRuleEvent;
    private Player tradingPlayer;
    private MerchantOffers offers;
    private java.util.List<TradeTable.Offer> shown = new java.util.ArrayList<>();
    /** Which of Bedrock's ways of getting about the move control standing on the entity was built for. */
    private String mover = "";
    private int offeredTiers = -1;
    /** The table the offers on the screen were built out of, so a group naming another rebuilds them. */
    private TradeTable offered;
    private boolean offersOverridden;
    private int tradeExperience;
    private Identifier lootTable;
    private Age age;
    private Anger anger;
    private Genetics genetics;
    private Rails rails;
    private Memories memories;
    private Projectiles flight;
    private net.minecraft.server.level.ServerBossEvent bossBar;
    private net.minecraft.world.SimpleContainer carried;

    public AddonEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.definition = AddonEntities.definitionOf(type);
        if (definition != null) {
            age = Age.tryFrom(this);
            anger = Anger.tryFrom(definition);
            genetics = Genetics.tryFrom(definition);
            rails = Rails.tryFrom(this);
            memories = Memories.tryFrom(this);
            flight = Projectiles.tryFrom(this);
            if (flight != null) {
                // A projectile's arc is the component's own, so Java's mob physics and its goals
                // both step aside for it rather than pulling against it every tick.
                setNoAi(true);
                setNoGravity(true);
            }
            addGoals();
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

    /** The prefix the live set of component groups is written under, one property per group. */
    public static final String GROUP = "group.";

    /**
     * Reads the live component groups back off the entity's own stored state.
     *
     * Which groups are on is what a pack changed about the entity rather than what it declared, so
     * it has to survive the chunk being written out and read back: a creeper struck by lightning is
     * charged until something uncharges it. Each group is already stored as a property of its own,
     * because the drawing side branches on `q.had_component_group`, and this is the other end of
     * that: the set the server decides with is built again from what was stored.
     */
    public void restoreGroups() {
        if (isClientSide() || definition == null || ServerHooks.state() == null) return;

        for (String group : definition.componentGroups().keySet()) {
            if (Boolean.TRUE.equals(value(GROUP + group))) activeGroups.add(group);
        }
        refreshAge();
        refreshComponents();
        publishMarkers();
    }

    /**
     * Takes the live component groups from the properties a server sent this client.
     *
     * The server decides which groups are on and stores each as a property; a client copy of the
     * entity holds no state of its own, so this is what lets its size, its box and every read of a
     * group on the drawing side agree with the server.
     */
    public void mirrorGroups(java.util.Map<String, Object> properties) {
        if (!isClientSide() || definition == null) return;

        java.util.Set<String> on = definition.componentGroups().keySet().stream()
            .filter(group -> Boolean.TRUE.equals(properties.get(GROUP + group)))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (on.equals(activeGroups)) return;

        activeGroups.clear();
        activeGroups.addAll(on);
        refreshDimensions();
    }

    /** When `minecraft:timer` next fires, in ticks of this entity's own age. Negative until set. */
    public int timerDueTick() {
        return timerDueTick;
    }

    public void setTimerDueTick(int tick) {
        timerDueTick = tick;
    }

    /** When `minecraft:scheduler` may run again, which is how its delay fields are kept. */
    public int schedulerDueTick() {
        return schedulerDueTick;
    }

    public void setSchedulerDueTick(int tick) {
        schedulerDueTick = tick;
    }

    /** How long this entity has been out of water, for `minecraft:drying_out_timer`. */
    public int dryTicks() {
        return dryTicks;
    }

    public void setDryTicks(int ticks) {
        dryTicks = ticks;
    }

    /** True the first time a `single_use` sensor asks, and false for every ask after it. */
    public boolean spend(String key) {
        return spent.add(key);
    }

    /**
     * Whether a sensor carrying a cooldown may fire, and takes the cooldown when it may.
     *
     * Bedrock counts the cooldown per subsensor rather than per entity, so the key names which one
     * is asking. A subsensor with no cooldown never reaches here.
     */
    public boolean sensorReady(String key, double seconds) {
        Integer due = sensorReady.get(key);
        if (due != null && tickCount < due) return false;

        sensorReady.put(key, tickCount + Math.max(1, (int) (seconds * 20)));
        return true;
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
        return attack == null
            ? number(definition, "minecraft:attack_damage", "value", 1) : attackDamage(attack);
    }

    /** The one number `minecraft:attack` names, whichever of the two shapes a pack wrote it in. */
    private static double attackDamage(com.google.gson.JsonObject attack) {
        com.google.gson.JsonElement damage = attack.get("damage");
        if (damage != null && damage.isJsonArray() && damage.getAsJsonArray().size() >= 2) {
            return (damage.getAsJsonArray().get(0).getAsDouble()
                + damage.getAsJsonArray().get(1).getAsDouble()) / 2;
        }
        return damage != null && damage.isJsonPrimitive() ? damage.getAsDouble() : 1;
    }

    /**
     * The number a component holds under one field.
     *
     * Bedrock writes a field that varies per entity as a range rather than a number, and Java fixes
     * one value on the type, so the middle of the range is what every entity of that type gets.
     */
    private static double number(ServerEntityDefinition definition, String component, String field,
        double fallback) {
        return number(definition.components().get(component), field, fallback);
    }

    /**
     * The number one field of a component body holds, whichever of the shapes it was written in.
     *
     * Bedrock writes a field that is on or off as a boolean and a field that varies per entity as a
     * range, and a caller comparing against zero wants an answer for all three. Asking Gson for the
     * number behind `false` throws, so the two states are counted as one and nothing instead.
     */
    private static double number(com.google.gson.JsonObject body, String field, double fallback) {
        com.google.gson.JsonElement value = body == null ? null : body.get(field);
        if (value == null) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean() ? 1 : 0;
        }
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
        if (definition.components().containsKey("minecraft:is_hidden_when_invisible")) {
            setInvisible(isInvisible());
        }

        if (definition.components().containsKey("minecraft:persistent")) {
            setPersistenceRequired();
        }
        // Bedrock states a starting health below the ceiling for an entity that arrives already hurt.
        setHealth(definition.startingHealth());
        if (declares("minecraft:absorption")) {
            setAbsorptionAmount((float) number(definition, "minecraft:absorption", "value", 0));
        }

        if (declares("minecraft:is_hidden_when_invisible")) setInvisible(isInvisible());

        refreshComponents();
        publishMarkers();
        setAirSupply(getMaxAirSupply());

        String table = liveText("minecraft:loot", "table");
        if (table != null) lootTable = AddonEntities.lootTable(getType(), table);

        wearWhatThePackRolled();
        publishWhatItIs();
        if (declares("minecraft:boss")) {
            bossBar = Platform.bossBar(getDisplayName());
            bossBar.setDarkenScreen(number(definition, "minecraft:boss", "should_darken_sky", 0) != 0);
        }

        if (declares("minecraft:default_look_angle")) {
            float angle = (float) number(definition, "minecraft:default_look_angle", "value", 0);
            setYHeadRot(angle);
            setYBodyRot(angle);
        }

        wearTheSkinOfAnNpc();
    }

    /**
     * Gives a `minecraft:npc` entity one of the skins its pack listed.
     *
     * Bedrock keeps the skins in the component and picks one per entity, which a render controller
     * then reads back as the variant. The rest of the component describes where a Bedrock client
     * puts the portrait in its dialogue screen, which Java draws no such screen for.
     */
    private void wearTheSkinOfAnNpc() {
        if (!declares("minecraft:npc")) return;

        com.google.gson.JsonObject data = componentBody("minecraft:npc").getAsJsonObject("npc_data");
        java.util.List<com.google.gson.JsonElement> skins = data == null
            ? java.util.List.of() : at.ioexception.mcaddon.util.Json.list(data, "skin_list");
        if (skins.isEmpty()) return;

        com.google.gson.JsonElement chosen = skins.get(random.nextInt(skins.size()));
        double variant = chosen.isJsonObject()
            ? Goals.number(chosen.getAsJsonObject(), "variant", 0) : 0;
        setInitialValue("variant", variant);
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

    /**
     * Publishes each marker the pack mentions as the flag its own Molang reads back.
     *
     * Bedrock states nearly every one of them inside a component group rather than at the top: a
     * horse carries `minecraft:is_saddled` only while its saddled group is on, and a sheep carries
     * `minecraft:is_sheared` until it eats grass and the group goes again. So each is worked out
     * from what is on the entity at this moment rather than set once, and a flag whose group has
     * gone is published as false. A marker the pack names nowhere is left off the entity, because a
     * query about one reads false from its absence.
     */
    public void publishMarkers() {
        for (String marker : MARKERS) {
            boolean mentioned = definition.components().containsKey(marker)
                || definition.componentGroups().values().stream().anyMatch(group -> group.has(marker));
            if (mentioned) setValue(marker.substring("minecraft:".length()), declaresNow(marker));
        }
        // Being young is the same kind of fact and the client cannot work it out for itself: the
        // component groups live on the server, and Java syncs no age for an entity that is not one
        // of its own ageing mobs.
        setValue("is_baby", isBaby());
    }

    /**
     * Whether a component is on the entity at this moment, in its own list or in a live group.
     *
     * A pack that switches a component on partway through writes it into a component group rather
     * than at the top, which is how Bedrock says a boat has been taken out of its rider's hands.
     */
    public boolean declaresNow(String component) {
        if (declares(component)) return true;

        return activeGroups.stream()
            .map(definition.componentGroups()::get)
            .anyMatch(group -> group != null && group.has(component));
    }

    /**
     * Whether the entity carries a component at this moment, answered before the definition as well.
     *
     * Java builds the navigator and the move control inside the mob constructor, which runs before
     * the definition field is assigned, so what the type was registered with is the only answer
     * available there. Everywhere else the live set decides, which is how a group that puts an
     * entity in the air takes the navigator with it.
     */
    private boolean carriesNow(String component) {
        if (definition != null) return declaresNow(component);

        ServerEntityDefinition declared = AddonEntities.definitionOf(getType());
        return declared != null && declared.components().containsKey(component);
    }

    /**
     * A string a component the entity carries at this moment holds, or null where it carries none.
     *
     * Each of these names an event the entity raises or something it reaches for, which is what a
     * pack switches on and off with a component group: `minecraft:on_target_acquired` and
     * `minecraft:shooter` are each written into a group about as often as at the top, and
     * `minecraft:addrider` and `minecraft:tamemount` are never written anywhere else.
     */
    private String liveText(String component, String field) {
        com.google.gson.JsonObject body = liveComponent(component);
        return body == null ? null : at.ioexception.mcaddon.util.Json.text(body, field, null);
    }

    /**
     * A number a component the entity carries at this moment holds, or the fallback where it carries
     * none.
     *
     * A component group is where Bedrock states a number the entity takes on partway through: the
     * blast of a primed creeper, the reach of a mount's dash and the gap between a mob's idle sounds
     * all arrive with the group that describes that state and go again with it.
     */
    private double liveNumber(String component, String field, double fallback) {
        return number(liveComponent(component), field, fallback);
    }

    /**
     * The body of a component the entity carries at this moment, or null where it carries none.
     *
     * A group the entity has not been given answers nothing here, which is the difference from
     * {@link #componentBody}: this is for a component whose presence is itself the state, where
     * reading one out of a group that is off says the entity is something it is not.
     *
     * A live group outranks the top level, which is how a pack says the entity has changed rather
     * than gained something: a camel sitting has a shorter `minecraft:collision_box` than the one on
     * the entity and a mooshroom's `minecraft:variant` is redeclared per colour. Thirty-four
     * components are written both ways in Mojang's own behaviour pack, and reading the top level
     * first would make every one of those group declarations say nothing.
     */
    public com.google.gson.JsonObject liveComponent(String component) {
        if (definition == null) return null;

        return activeGroups.stream()
            .map(definition.componentGroups()::get)
            .filter(group -> group != null && group.has(component)
                && group.get(component).isJsonObject())
            .map(group -> group.getAsJsonObject(component))
            .findFirst()
            .orElseGet(() -> definition.components().get(component));
    }

    /**
     * Builds the growing-up count again over what the entity carries now.
     *
     * Bedrock states `minecraft:ageable` inside the component group that makes an entity young
     * rather than on the entity itself, because only a young one grows: of the thirty-five vanilla
     * entities that age, one writes it at the top level. So the count begins when that group arrives
     * and ends when it goes. A count already running is left where it is, because the entity has
     * already spent part of its wait.
     */
    public void refreshAge() {
        if (liveComponent("minecraft:ageable") == null) {
            age = null;
        } else if (age == null) {
            age = Age.tryFrom(this);
        }
    }

    /**
     * The components Java holds on the entity rather than on its type, worked out over the live set.
     *
     * Each of these is something the entity becomes, so Bedrock states it inside a component group
     * as readily as at the top: a young entity is smaller, a grown one is worth killing, and a bee
     * in its angry group hits harder than the same bee calm. Java keeps all three on the entity, so
     * each can move while the entity lives and a group leaving takes its value away again.
     */
    public void refreshComponents() {
        // The size feeds both the box the entity collides in and the size it is drawn at, and Java
        // caches it, so it has to be asked for again rather than left to the next pose change.
        refreshDimensions();
        refreshNavigation();

        com.google.gson.JsonObject attack = liveComponent("minecraft:attack");
        net.minecraft.world.entity.ai.attributes.AttributeInstance damage =
            getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(attack == null ? attackDamage(definition) : attackDamage(attack));
        }

        com.google.gson.JsonObject footing = liveComponent("minecraft:knockback_resistance");
        net.minecraft.world.entity.ai.attributes.AttributeInstance knockback =
            getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.setBaseValue(footing == null ? 0
                : at.ioexception.mcaddon.util.Json.number(footing, "value", 0));
        }

        // Java has no way back: a mob told to stay cannot be told to despawn again.
        if (liveComponent("minecraft:persistent") != null) setPersistenceRequired();

        // `minecraft:preferred_path` is the entity keeping to the ground it was given, so water
        // costs it what Java charges a villager for it, whatever its navigation allowed.
        if (declaresNow("minecraft:preferred_path")) Platform.priceWater(this, true);

        // `minecraft:variable_max_auto_step` arrives with the group that lets an entity climb what
        // it otherwise could not. Six tenths of a block is what a Java mob steps otherwise.
        Platform.setStepHeight(this,
            (float) liveNumber("minecraft:variable_max_auto_step", "base_value", 0.6));

        refreshBreath();
        refreshFuse();
        refreshHome();
        refreshContainer();
        releaseLeadsAGroupTook();
        publishAnger();
        publishLiveFlags();
    }

    /**
     * Lets go of the leads a group took with it, which `unleash_on_removal` asks for by default.
     *
     * `minecraft:leashable` going takes the lead off this entity, and `minecraft:leashable_to` going
     * frees whatever was tied to it. Each is compared with what the entity carried before the groups
     * changed, so an entity read back from storage lets go of nothing.
     */
    private void releaseLeadsAGroupTook() {
        com.google.gson.JsonObject leashable = liveComponent("minecraft:leashable");
        if (leashable == null && releases(heldLeashable) && isLeashed()) Platform.unleash(this);
        heldLeashable = leashable;

        com.google.gson.JsonObject leashableTo = liveComponent("minecraft:leashable_to");
        if (leashableTo == null && releases(heldLeashableTo)) {
            level().getEntities(this, getBoundingBox().inflate(LEASH_REACH),
                other -> Platform.leashHolder(other) == this).forEach(Platform::unleash);
        }
        heldLeashableTo = leashableTo;
    }

    private static boolean releases(com.google.gson.JsonObject held) {
        return held != null && ServerEntityDefinition.flag(held, "unleash_on_removal", true);
    }

    private com.google.gson.JsonObject heldLeashable;
    private com.google.gson.JsonObject heldLeashableTo;

    /** Past the distance at which Java snaps a lead on any supported version. */
    private static final double LEASH_REACH = 16;

    /**
     * What the entity breathes, which a component group may change like any other component. Air
     * already spent stays spent, up to the new supply.
     */
    private void refreshBreath() {
        breath = Breath.tryFrom(liveComponent("minecraft:breathable"));
        if (getAirSupply() > getMaxAirSupply()) setAirSupply(getMaxAirSupply());
    }

    private Breath breath;

    /**
     * Lights the fuse of a `minecraft:explode` that arrives with `fuse_lit`, and puts it out again
     * when the component goes.
     *
     * This is how a Bedrock creeper goes off: its sensor adds the group carrying the explosion, and
     * the fuse that group lights burns for `fuse_length` seconds, a range a random point of which is
     * taken. A negative length goes off at once. A fuse already burning keeps the time it has left.
     */
    private void refreshFuse() {
        com.google.gson.JsonObject explode = liveComponent("minecraft:explode");
        if (explode == null || !Goals.flag(explode, "fuse_lit", false)) {
            fuseEndsAt = -1;
            return;
        }
        if (fuseEndsAt >= 0) return;

        double low = Goals.low(explode, "fuse_length", 0);
        double high = Math.max(low, Goals.high(explode, "fuse_length", low));
        double seconds = low + random.nextDouble() * (high - low);
        fuseEndsAt = tickCount + Math.max(0, (int) (seconds * Goals.TICKS_PER_SECOND));
    }

    private int fuseEndsAt = -1;

    private void burnFuse() {
        if (fuseEndsAt >= 0 && tickCount >= fuseEndsAt) {
            fuseEndsAt = -1;
            detonate();
        }
    }

    @Override
    public int getMaxAirSupply() {
        return breath == null ? super.getMaxAirSupply() : breath.supply();
    }

    /**
     * The air a tick under water leaves, which is the only medium Java counts down in by itself.
     *
     * An entity that breathes water fills up there instead, and one carrying no
     * `minecraft:breathable` never loses any.
     */
    @Override
    protected int decreaseAirSupply(int current) {
        if (definition == null) return super.decreaseAirSupply(current);
        if (breath == null) return current;

        return breath.breathesAround(this) ? breath.inhale(current) : super.decreaseAirSupply(current);
    }

    /** Java refills a mob's air whenever it is out of water, which a fish on land does not get. */
    @Override
    protected int increaseAirSupply(int current) {
        if (breath == null) return super.increaseAirSupply(current);

        return breath.breathesAround(this) ? breath.inhale(current) : current;
    }

    /**
     * Counts the breath down wherever the entity cannot breathe and Java does not count it.
     *
     * Java drowns a mob only under water, so a fish on land, a mob that breathes nothing but water
     * and one choking in lava lose their air here, at the rate and with the damage Java drowns with.
     */
    private void holdBreath() {
        if (breath == null || Breath.underwater(this) || breath.breathesAround(this)) return;

        setAirSupply(super.decreaseAirSupply(getAirSupply()));
        if (getAirSupply() > DROWNED_AT) return;

        setAirSupply(0);
        Platform.hurt(this, (net.minecraft.server.level.ServerLevel) level(), damageSources().drown(), 2f);
    }

    /** How far below empty Java lets a mob's air fall before drowning hurts it. */
    private static final int DROWNED_AT = -20;

    /** Whether the entity stands in a level, which it does not while it is being built. */
    private boolean placed;

    @Override
    public void setLevelCallback(net.minecraft.world.level.entity.EntityInLevelCallback callback) {
        super.setLevelCallback(callback);
        placed = callback != net.minecraft.world.level.entity.EntityInLevelCallback.NULL;
        if (placed && definition != null && !isClientSide()) refreshHome();
    }

    /**
     * Where the entity is held, which `minecraft:home` and `minecraft:dweller` both state as a radius.
     *
     * Both say the entity belongs somewhere rather than wandering, and Bedrock brings either in with
     * the group that gives the entity its place: a villager is a dweller of the village it took a job
     * in. So the centre is wherever the entity stands as the group arrives, and it wanders again once
     * the group has gone. A place it already has is left alone, because walking away from home is not
     * the same as moving house.
     */
    private void refreshHome() {
        // The entity is built before it is put anywhere, and a centre taken then is the world's origin.
        if (!placed) return;

        String component = declaresNow("minecraft:home") ? "minecraft:home"
            : declaresNow("minecraft:dweller") ? "minecraft:dweller" : null;
        // `restriction_type` is `none` unless the pack says otherwise, which keeps a home without
        // holding the entity to it: Mojang writes an empty `minecraft:home` on entities whose events
        // only need somewhere to remember.
        if (component != null && component.equals("minecraft:home")
            && "none".equals(at.ioexception.mcaddon.util.Json.text(
                liveComponent("minecraft:home"), "restriction_type", "none"))) {
            component = null;
        }
        if (component == null) {
            Platform.clearHome(this);
            return;
        }
        if (Platform.hasHome(this)) return;

        Platform.setHome(this, blockPosition(),
            Math.max(1, (int) liveNumber(component, "restriction_radius", 16)));
    }

    /**
     * The container the entity carries, sized by whichever component asks for one at this moment.
     *
     * `minecraft:inventory` states the number of slots outright and `minecraft:strength` states how
     * much the entity can carry, three slots per point in both editions. Either arrives with the
     * group that gives the entity something to carry things for, so the container is built again
     * whenever the number of slots the pack asks for has moved. What was in it is carried across,
     * because the items belong to the entity rather than to the group that asked for the room.
     */
    private void refreshContainer() {
        int slots = 0;
        if (declaresNow("minecraft:inventory")) {
            slots = Math.max(1, (int) liveNumber("minecraft:inventory", "inventory_size", 5));
        } else if (declaresNow("minecraft:strength")) {
            slots = Math.max(1, (int) liveNumber("minecraft:strength", "value", 1) * 3);
        }
        if (slots == 0 || (carried != null && carried.getContainerSize() == slots)) return;

        net.minecraft.world.SimpleContainer sized = new net.minecraft.world.SimpleContainer(slots);
        if (carried != null) {
            for (int slot = 0; slot < Math.min(slots, carried.getContainerSize()); slot++) {
                sized.setItem(slot, carried.getItem(slot));
            }
        }
        carried = sized;
    }

    /**
     * The flags a pack's own Molang reads back off a component whose group can come and go.
     *
     * Each is a fact about what the entity may do rather than a number Java holds anywhere, so the
     * flag is the whole of what the component is worth on this side: a mount gains its dash and its
     * power jump with the group that saddles it, and the fuse a primed entity carries is what its
     * controllers count down over its head.
     */
    private void publishLiveFlags() {
        setValue("can_power_jump", declaresNow("minecraft:can_power_jump"));

        // How a mob gets about is something it takes on, so the four ways of saying it move with
        // the group that granted them and a controller asking is answered about the entity now.
        setValue("can_climb", declaresNow("minecraft:can_climb"));
        setValue("can_fly", declaresNow("minecraft:navigation.fly")
            || declaresNow("minecraft:movement.fly"));
        setValue("can_swim", declaresNow("minecraft:navigation.swim")
            || declaresNow("minecraft:movement.amphibious"));
        setValue("can_walk", declaresNow("minecraft:navigation.walk")
            || declaresNow("minecraft:navigation.generic"));
        for (String dye : new String[] {"minecraft:color", "minecraft:color2"}) {
            if (declaresNow(dye)) {
                setValue(dye.substring("minecraft:".length()), liveNumber(dye, "value", 0));
            }
        }
        if (declaresNow("minecraft:explode")) {
            setValue("fuse_time", liveNumber("minecraft:explode", "fuse_length", 1.5));
        }
        if (declaresNow("minecraft:dash_action") && value("can_dash") == null) {
            setValue("can_dash", true);
        }

        // Java's merchant screen has one list of offers, so how many tiers the table was written in
        // survives only as the number a pack's own controllers read back.
        TradeTable trades = trades();
        if (trades != null) setValue("max_trade_tier", (double) trades.tiers());
        publishTradeTier(trades);
    }

    /**
     * The tier the trader has reached, which `query.trade_tier` reads back. Bedrock counts tiers from
     * nought, so a trader still offering only its first tier answers 0.
     */
    private void publishTradeTier(TradeTable trades) {
        if (trades != null) setValue("trade_tier", (double) (trades.reached(tradeExperience) - 1));
    }

    /**
     * The trade table the entity offers at this moment, or null while it names none.
     *
     * Bedrock states the table inside the group that gives a trader its stock, so which file is
     * offered moves with the group that arrived. Every table either component names was read while
     * the pack was in hand, so what is left here is picking between them.
     */
    public TradeTable trades() {
        String table = liveText("minecraft:trade_table", "table");
        if (table == null) table = liveText("minecraft:economy_trade_table", "table");

        return AddonEntities.tradeTable(getType(), table);
    }

    /**
     * How far the whole entity is grown, beyond the scale its type was registered at.
     *
     * `minecraft:scale` sizes the entity, and its type was built from whatever the top level said,
     * which cannot move once the registries are frozen. Of the forty-two vanilla entities that put
     * the component in a group, every one uses the group that makes it young and none of them
     * carries `minecraft:scale_by_age`, so this is how a pack's baby is drawn small. Dividing by the
     * scale already in the type is what keeps a top level `minecraft:scale` from counting twice.
     */
    private float declaredScale() {
        com.google.gson.JsonObject live = liveComponent("minecraft:scale");
        float baked = definition == null ? 1f : definition.scale();
        if (live == null || baked <= 0) return 1f;

        return (float) at.ioexception.mcaddon.util.Json.number(live, "value", baked) / baked;
    }

    /**
     * Tells everything reading the entity whether the group holding `minecraft:angry` is on.
     *
     * Bedrock has no other place for it: all twenty vanilla uses are inside a group and none is at
     * the top, because being angry is a state something put the entity into. A pack branches on
     * `query.is_angry` in its render controllers and its filters, so the flag is published from the
     * live set the same way a marker is, and goes false again when the group leaves. An entity
     * counting `minecraft:anger_level` publishes the same flag off its own count instead.
     */
    private void publishAnger() {
        if (anger != null || definition == null) return;

        boolean mentioned = definition.components().containsKey("minecraft:angry")
            || definition.componentGroups().values().stream()
                .anyMatch(group -> group.has("minecraft:angry"));
        if (mentioned) setValue("is_angry", declaresNow("minecraft:angry"));
    }

    /**
     * What killing this entity is worth, which Bedrock states as Molang.
     *
     * Eighty-six of the eighty-seven `on_death` rewards in Mojang's own behaviour pack are an
     * expression asking whether a player struck the last blow, so the field is parsed as one and a
     * bare number is the case the parser also covers. The component itself sits in the group that
     * makes the entity grown as often as on the entity, a baby being worth nothing, which is why
     * the live set is what answers.
     */
    private int experienceReward(boolean killedByPlayer) {
        com.google.gson.JsonObject reward = liveComponent("minecraft:experience_reward");
        com.google.gson.JsonElement onDeath = reward == null ? null : reward.get("on_death");
        if (onDeath == null || !onDeath.isJsonPrimitive()) return 0;

        at.ioexception.mcaddon.bedrock.molang.MolangEnvironment environment =
            new at.ioexception.mcaddon.bedrock.molang.MolangEnvironment();
        environment.setQueries((query, arguments) -> query.equals("last_hit_by_player")
            ? at.ioexception.mcaddon.bedrock.molang.MolangValue.of(killedByPlayer)
            : at.ioexception.mcaddon.bedrock.molang.MolangValue.ZERO);
        return Math.max(0, (int) at.ioexception.mcaddon.bedrock.molang.MolangParser
            .parse(onDeath.getAsString()).asFloat(environment));
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
     * An entity whose pack asked to fly and got a ground navigator walks under whatever it was
     * supposed to fly over, which is why this is chosen per entity rather than left at the default.
     * Bedrock states the kind inside a component group as readily as at the top, because how a mob
     * gets about is something it takes on: a strider in lava and a fish out of water each change.
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        if (carriesNow("minecraft:navigation.fly") || carriesNow("minecraft:navigation.hover")
            || carriesNow("minecraft:navigation.float") || carriesNow("minecraft:can_fly")) {
            return new FlyingPathNavigation(this, level);
        }
        if (carriesNow("minecraft:navigation.swim")) {
            return new WaterBoundPathNavigation(this, level);
        }
        if (carriesNow("minecraft:navigation.climb") || carriesNow("minecraft:can_climb")
            || carriesNow("minecraft:block_climber")) {
            return new WallClimberNavigation(this, level);
        }
        PathNavigation swimming = pathing().navigatorFor(this, level);
        return swimming != null ? swimming : super.createNavigation(level);
    }

    /**
     * The navigator and the move control the entity's live components describe.
     *
     * Both are fields on the mob rather than on its type, so a group arriving swaps them the way it
     * swaps a size or an attribute. The navigator is left standing while the kind it is already of
     * is the kind asked for, because building another one throws away the path the entity is on.
     */
    private void refreshNavigation() {
        PathNavigation asked = createNavigation(level());
        if (asked.getClass() != navigation.getClass()) navigation = asked;

        pathing().applyTo(this, navigation, carriesNow("minecraft:annotation.open_door"));
        applyMoveControl();
    }

    /**
     * The allowances of the navigation component the entity carries now.
     *
     * One a live group brought outranks one the entity declares, the way a group outranks the top
     * level for any component, and of two written in the same place the later in this list wins.
     */
    private Pathing pathing() {
        com.google.gson.JsonObject walking = null;
        boolean grouped = false;
        for (String component : java.util.List.of(
            "minecraft:navigation.float", "minecraft:navigation.climb", "minecraft:navigation.fly",
            "minecraft:navigation.hover", "minecraft:navigation.swim", "minecraft:navigation.generic",
            "minecraft:navigation.walk")) {
            if (!carriesNow(component)) continue;

            boolean fromGroup = definition != null && !declares(component);
            if (grouped && !fromGroup) continue;
            walking = bodyNow(component);
            grouped = fromGroup;
        }
        return Pathing.of(walking);
    }

    /**
     * The body of a component the entity carries now, answered before the definition as well, for
     * the same reason {@link #carriesNow} is.
     */
    private com.google.gson.JsonObject bodyNow(String component) {
        if (definition != null) return liveComponent(component);

        ServerEntityDefinition declared = AddonEntities.definitionOf(getType());
        return declared == null ? null : declared.components().get(component);
    }

    /** Which way of getting about the entity's `minecraft:movement.*` names at this moment. */
    private String moverKind() {
        if (carriesNow("minecraft:movement.hover")) return "hover";
        if (carriesNow("minecraft:movement.fly") || carriesNow("minecraft:movement.glide")) return "fly";
        // Swimming that banks into its turns, which is what Bedrock's dolphin and its sway both are.
        if (carriesNow("minecraft:movement.dolphin") || carriesNow("minecraft:movement.sway")) return "swim";
        if (carriesNow("minecraft:movement.jump") || carriesNow("minecraft:movement.skip")) return "hop";

        return "walk";
    }

    /**
     * Holds the entity up when its pack described something that moves through the air.
     *
     * Java's default move control pulls a mob back to the ground between steps, so a flying
     * navigator on its own produces a path the mob cannot follow. Hovering differs from flying only
     * in whether the entity stops in place when it has nowhere to be. A control already of the kind
     * asked for is left where it is, because a new one drops the step the entity is part way
     * through.
     */
    private void applyMoveControl() {
        String asked = moverKind();
        if (asked.equals(mover)) return;

        mover = asked;
        moveControl = switch (asked) {
            case "hover" -> new net.minecraft.world.entity.ai.control.FlyingMoveControl(this, 20, true);
            case "fly" -> new net.minecraft.world.entity.ai.control.FlyingMoveControl(this, 20, false);
            case "swim" -> new net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl(
                this, 85, 10, 0.02f, 0.1f, true);
            case "hop" -> new HopMoveControl(this);
            default -> new net.minecraft.world.entity.ai.control.MoveControl(this);
        };
    }

    /**
     * Who the pack allows on, and how many of them.
     *
     * Bedrock states the seats and the families that may use them, and Java asks the entity each
     * time somebody tries to get on, so both are read back out of the component rather than kept.
     * `minecraft:behavior.player_ride_tamed` is the pack saying a player may only ride once the
     * entity belongs to somebody, which is the one thing that behaviour decides, so it is answered
     * here rather than as a goal.
     */
    @Override
    protected boolean canAddPassenger(net.minecraft.world.entity.Entity passenger) {
        if (definition == null || !declaresNow("minecraft:rideable")) return false;
        if (passenger instanceof Player && ownerUuid() == null
            && declaresNow("minecraft:behavior.player_ride_tamed")) {
            return false;
        }
        if (getPassengers().size() >= (int) liveNumber("minecraft:rideable", "seat_count", 1)) {
            return false;
        }
        return ridableBy(passenger);
    }

    /** A pack listing `family_types` is naming who may ride; listing none allows anyone. */
    private boolean ridableBy(net.minecraft.world.entity.Entity passenger) {
        java.util.List<com.google.gson.JsonElement> families = at.ioexception.mcaddon.util.Json.list(
            liveComponent("minecraft:rideable"), "family_types");
        if (families.isEmpty()) return true;

        java.util.Set<String> carried = at.ioexception.mcaddon.bedrock.behaviour.Families.of(passenger);
        return families.stream()
            .filter(com.google.gson.JsonElement::isJsonPrimitive)
            .map(com.google.gson.JsonElement::getAsString)
            .anyMatch(family -> carried.contains(family)
                || (family.equals("player") && passenger instanceof Player));
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
        // `minecraft:out_of_control` is the mount taken out of its rider's hands, which Bedrock
        // switches on while a boat is over a bubble column and nobody aboard can steer it.
        if (declaresNow("minecraft:out_of_control")) return null;

        return getPassengers().stream()
            .filter(Player.class::isInstance)
            .map(Player.class::cast)
            .filter(this::holdsTheReins)
            .findFirst()
            .map(net.minecraft.world.entity.LivingEntity.class::cast)
            .orElseGet(super::getControllingPassenger);
    }

    private boolean steerable() {
        return declaresNow("minecraft:input_ground_controlled")
            || declaresNow("minecraft:free_camera_controlled")
            || declaresNow("minecraft:item_controllable")
            || declaresNow("minecraft:behavior.controlled_by_player");
    }

    /**
     * Whether the rider is holding what `minecraft:item_controllable` says steers this entity.
     *
     * A pack naming no such component is describing a mount steered by sitting on it, which is what
     * every rider does.
     */
    private boolean holdsTheReins(Player rider) {
        com.google.gson.JsonObject reins = liveComponent("minecraft:item_controllable");
        if (reins == null) return true;

        String held = AddonItems.identifierOf(rider.getMainHandItem().getItem());
        for (com.google.gson.JsonElement item
            : at.ioexception.mcaddon.util.Json.list(reins, "control_items")) {
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

        double sideways = asked.x
            * liveNumber("minecraft:free_camera_controlled", "strafe_speed_modifier", 0.4) * 2.5;
        double forward = asked.z < 0
            ? asked.z * liveNumber("minecraft:free_camera_controlled",
                "backwards_movement_modifier", 0.5) * 2
            : asked.z;
        double vertical = declaresNow("minecraft:vertical_movement_action")
            && ((at.ioexception.mcaddon.mixin.JumpingAccessor) rider).mcaddon$jumping()
            ? liveNumber("minecraft:vertical_movement_action", "vertical_velocity", 0.5) : 0;
        return new net.minecraft.world.phys.Vec3(sideways, vertical, forward);
    }

    /** How fast a driven mount goes, which Bedrock scales off the entity's own movement speed. */
    @Override
    protected float getRiddenSpeed(Player rider) {
        if (definition == null
            || !declaresNow("minecraft:behavior.controlled_by_player")) {
            return super.getRiddenSpeed(rider);
        }
        return (float) (getAttributeValue(Attributes.MOVEMENT_SPEED) * number(definition,
            "minecraft:behavior.controlled_by_player", "mount_speed_multiplier", 1));
    }

    /** `minecraft:underwater_mount_breathing` keeps a rider's air up for as long as they stay on. */
    private void breatheForRider() {
        if (!declaresNow("minecraft:underwater_mount_breathing")) return;

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
     * A seat is stated in blocks relative to the entity's own position, which is the space Java
     * answers an attachment point in, and its vertical axis points the same way.
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
    /** The slots `minecraft:inventory` or `minecraft:strength` gives the entity, or null while it has none. */
    public net.minecraft.world.Container inventory() {
        return carried;
    }

    /** Whether the entity has slots of its own to put what it picks up in. */
    boolean carriesInventory() {
        return carried != null;
    }

    /** Whether the slot Java would wear this item in is empty, a hand for most things. */
    boolean slotFreeFor(ItemStack stack) {
        return getItemBySlot(getEquipmentSlotForItem(stack)).isEmpty();
    }

    /**
     * Takes what the dropped stack holds into wherever the entity keeps things, and answers whether
     * any of it fitted.
     */
    public boolean pickUp(net.minecraft.world.entity.item.ItemEntity dropped) {
        // `singular_pickup` is the pack asking for one of the stack rather than all of it.
        ItemStack taken = dropped.getItem().copy();
        if (ServerEntityDefinition.flag(liveComponent("minecraft:shareables"), "singular_pickup", false)) {
            taken.setCount(1);
        }
        if (!stow(taken)) return false;

        dropped.getItem().shrink(taken.getCount());
        if (dropped.getItem().isEmpty()) dropped.discard();
        return true;
    }

    /** Raises one of the entity's own events, for a goal that names one. */
    void raiseEvent(String event) {
        fire(event);
    }

    /**
     * Puts one item where the pack said this entity keeps what it collects.
     *
     * `minecraft:inventory` gives the entity slots of its own, which is what a pack describes for
     * something that hauls goods. Without it the item goes to the slot Java would wear it in, a
     * hand for most things and the head for a helmet. False when there is nowhere left to put it.
     */
    private boolean stow(ItemStack stack) {
        if (carried != null) return carried.addItem(stack).isEmpty();

        net.minecraft.world.entity.EquipmentSlot slot = getEquipmentSlotForItem(stack);
        if (!getItemBySlot(slot).isEmpty()) return false;

        setItemSlot(slot, stack);
        return true;
    }

    /**
     * Whether the pack's `shareables` list allows this item.
     *
     * An entity with no list wants nothing: `can_pickup_any_item` on the pickup behaviour is how a
     * pack says otherwise. `all_items` is the pack saying the list is a preference rather than a
     * gate, so an entity declaring it takes whatever it finds.
     */
    public boolean shareable(net.minecraft.world.item.ItemStack stack) {
        com.google.gson.JsonObject body = liveComponent("minecraft:shareables");
        if (body == null) return false;
        if (ServerEntityDefinition.flag(body, "all_items", false)) return true;
        return named(body, "items", stack);
    }

    /** Whether an item list a component carries names this stack, in either spelling of an entry. */
    static boolean named(com.google.gson.JsonObject body, String field,
        net.minecraft.world.item.ItemStack stack) {
        java.util.List<com.google.gson.JsonElement> listed =
            at.ioexception.mcaddon.util.Json.list(body, field);
        if (listed.isEmpty()) return false;

        String id = AddonItems.identifierOf(stack.getItem());
        return listed.stream()
            .map(entry -> entry.isJsonObject()
                ? at.ioexception.mcaddon.util.Json.text(entry.getAsJsonObject(), "item", null)
                : entry.isJsonPrimitive() ? entry.getAsString() : null)
            .anyMatch(name -> names(name, id));
    }

    /**
     * Restocks the trades a pack said should come back.
     *
     * Bedrock counts the restock in days and Java counts the world's own time, so a day is the
     * twenty-four thousand ticks the game already measures one by.
     */
    private void resupply() {
        if (offers == null || !declaresNow("minecraft:trade_resupply")) return;

        long day = level().getGameTime() / 24000L;
        if (day == lastResupplyDay) return;

        lastResupplyDay = day;
        offers.forEach(MerchantOffer::resetUses);
    }

    private long lastResupplyDay = -1;

    /**
     * Moves the entity somewhere else nearby, the way Bedrock's teleport component does.
     *
     * A pack gives how often to try, how likely each try is and the box to land in; what it is
     * describing is a mob that blinks about rather than walks, which Java has no component for but
     * can be asked to do outright. The two teleport times are seconds between tries and
     * `random_teleport_cube` is the whole width of the box on each axis, so a jump reaches half of
     * it from where the entity stands. A jump out of the light is priced apart from one out of the
     * dark, which is what makes an enderman's daylight blink more likely than its night one.
     */
    private void teleportAbout() {
        if (!declares("minecraft:teleport") || level().isClientSide()) return;

        com.google.gson.JsonObject body = componentBody("minecraft:teleport");
        if (!Goals.flag(body, "random_teleports", true) || tickCount < teleportReadyAt) return;

        teleportReadyAt = tickCount + Goals.between(random,
            Math.max(1, Goals.ticks(body, "min_random_teleport_time", 0)),
            Math.max(1, Goals.ticks(body, "max_random_teleport_time", 20)));

        boolean lit = level().getMaxLocalRawBrightness(blockPosition()) > 7;
        if (random.nextDouble() > Goals.number(body,
            lit ? "light_teleport_chance" : "dark_teleport_chance", 0.01)) {
            return;
        }

        net.minecraft.world.phys.Vec3 cube =
            at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits.blocks(
                body.get("random_teleport_cube"),
                new net.minecraft.world.phys.Vec3(32, 16, 32));
        double x = getX() + (random.nextDouble() - 0.5) * cube.x;
        double y = getY() + (random.nextDouble() - 0.5) * cube.y;
        double z = getZ() + (random.nextDouble() - 0.5) * cube.z;
        Platform.placeAt(this, x, y, z, getYRot(), getXRot());
    }

    private int teleportReadyAt;

    /** Where the first seat a `minecraft:rideable` component names sits, or null when it names none. */
    private net.minecraft.world.phys.Vec3 seat() {
        com.google.gson.JsonObject seat = firstSeat();
        return seat == null ? null
            : at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits.blocks(seat.get("position"), null);
    }

    /** The first seat a `minecraft:rideable` component names, or null when it names none. */
    private com.google.gson.JsonObject firstSeat() {
        com.google.gson.JsonObject body = liveComponent("minecraft:rideable");
        com.google.gson.JsonArray seats = body == null ? null : body.getAsJsonArray("seats");
        if (seats == null || seats.isEmpty() || !seats.get(0).isJsonObject()) return null;

        return seats.get(0).getAsJsonObject();
    }

    /**
     * Works out how far the seat turns each rider from the way this entity faces, which Bedrock
     * evaluates every tick as the seat's `rotate_rider_by`.
     *
     * The expression is asked as the rider, so `query.trade_tier` and `query.has_any_family` answer
     * for whoever sits there. It runs before this entity's own scripts, with the rider already moved
     * onto the seat, and its temporaries are the ones every other expression on the server reads: a
     * pack hands what it learnt about a rider to its mount's scripts that way, keyed by where the two
     * stand.
     */
    private void turnRiders() {
        com.google.gson.JsonObject seat = firstSeat();
        com.google.gson.JsonElement turn = seat == null ? null : seat.get("rotate_rider_by");
        if (turn == null || !turn.isJsonPrimitive()) {
            riderTurns.clear();
            return;
        }
        if (turn != seatTurnSource) {
            seatTurnSource = turn;
            seatTurn = at.ioexception.mcaddon.bedrock.molang.MolangParser.parse(
                turn.getAsJsonPrimitive().isNumber() ? (Object) turn.getAsDouble() : turn.getAsString());
        }

        java.util.Set<java.util.UUID> aboard = new java.util.HashSet<>();
        for (net.minecraft.world.entity.Entity rider : getPassengers()) {
            aboard.add(rider.getUUID());
            positionRider(rider);
            riderTurns.put(rider.getUUID(), seatTurn.asFloat(riderQuestions(rider)));
        }
        riderTurns.keySet().retainAll(aboard);
        riderQuestions.keySet().retainAll(aboard);
    }

    /** What a seat's expression is answered with about one rider, kept while it stays aboard. */
    private at.ioexception.mcaddon.bedrock.molang.MolangEnvironment riderQuestions(
        net.minecraft.world.entity.Entity rider) {
        at.ioexception.mcaddon.bedrock.animation.AnimationPlayer asked = riderQuestions.computeIfAbsent(
            rider.getUUID(), id -> {
                at.ioexception.mcaddon.bedrock.animation.AnimationPlayer fresh =
                    new at.ioexception.mcaddon.bedrock.animation.AnimationPlayer(null, rider);
                fresh.environment().setQueries(new at.ioexception.mcaddon.bedrock.entity.EntityQueries(
                    rider, fresh, at.ioexception.mcaddon.bedrock.entity.EntityQueries.Drawn.NONE,
                    () -> ServerHooks.state() == null ? java.util.Map.of() : ServerHooks.state().properties(id),
                    false));
                if (ServerHooks.state() != null) {
                    fresh.environment().shareTemporaries(ServerHooks.state().temporaries());
                }
                return fresh;
            });
        asked.environment().setLifeTime(
            rider.tickCount / (float) at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits.TICKS_PER_SECOND);
        return asked.environment();
    }

    private com.google.gson.JsonElement seatTurnSource;
    private at.ioexception.mcaddon.bedrock.molang.MolangExpression seatTurn;
    private final java.util.Map<java.util.UUID, Float> riderTurns = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, at.ioexception.mcaddon.bedrock.animation.AnimationPlayer>
        riderQuestions = new java.util.HashMap<>();

    /**
     * Faces each rider the way its seat turns it, and keeps it within `lock_rider_rotation` degrees of
     * that where the seat states a limit.
     */
    @Override
    protected void positionRider(net.minecraft.world.entity.Entity passenger, MoveFunction move) {
        super.positionRider(passenger, move);
        Float turn = riderTurns.get(passenger.getUUID());
        if (turn == null) return;

        float facing = getYRot() + turn;
        if (passenger instanceof net.minecraft.world.entity.LivingEntity living) living.setYBodyRot(facing);

        com.google.gson.JsonObject seat = firstSeat();
        com.google.gson.JsonElement lock = seat == null ? null : seat.get("lock_rider_rotation");
        if (lock == null || !lock.isJsonPrimitive() || !lock.getAsJsonPrimitive().isNumber()) return;

        float off = net.minecraft.util.Mth.wrapDegrees(passenger.getYRot() - facing);
        float kept = net.minecraft.util.Mth.clamp(off, -lock.getAsFloat(), lock.getAsFloat());
        passenger.yRotO += kept - off;
        passenger.setYRot(passenger.getYRot() + kept - off);
        passenger.setYHeadRot(passenger.getYRot());
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
        if (child instanceof AddonEntity young) {
            young.setYoung();
            young.inherit(this, this);
        }
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
        com.google.gson.JsonObject breedable = liveComponent("minecraft:breedable");
        if (breedable == null || !breedsWith(breedable, player.getItemInHand(hand))) return false;
        if (!willingToBreed(breedable, player)) return false;

        if (!player.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
        inLoveUntil = tickCount + LOVE_TICKS;
        for (int i = 0; i < 7; i++) {
            level().addParticle(net.minecraft.core.particles.ParticleTypes.HEART,
                getRandomX(1), getRandomY() + 0.5, getRandomZ(1), 0, 0, 0);
        }
        return true;
    }

    /**
     * Being handed one of the items `minecraft:bribeable` names, which buys the entity's company.
     *
     * Whoever pays becomes the owner a pack's own following behaviours read, which is what makes a
     * bribed entity swim after the player who fed it. The cooldown the component carries is the
     * wait before another bribe can be paid, and the bribe itself lasts the minute Bedrock gives it.
     */
    private boolean bribed(Player player, InteractionHand hand) {
        if (!declares("minecraft:bribeable") || bribeReadyAt > tickCount) return false;

        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return false;

        String id = AddonItems.identifierOf(held.getItem());
        boolean wanted = at.ioexception.mcaddon.util.Json.list(
                componentBody("minecraft:bribeable"), "bribe_items").stream()
            .anyMatch(item -> item.isJsonPrimitive() && names(item.getAsString(), id));
        if (!wanted) return false;

        if (!player.getAbilities().instabuild) held.shrink(1);
        bribedUntilTick = tickCount + BRIBE_TICKS;
        bribeReadyAt = tickCount + (int) (number(definition, "minecraft:bribeable",
            "bribe_cooldown", 2) * Goals.TICKS_PER_SECOND);
        setOwner(player);
        return true;
    }

    /** How long a bribe buys, in ticks of the entity's own age. */
    private static final int BRIBE_TICKS = 1200;

    private int bribedUntilTick;
    private int bribeReadyAt;

    /** Whether one of the items the pack named as breeding food is what was offered. */
    private static boolean breedsWith(com.google.gson.JsonObject breedable,
        net.minecraft.world.item.ItemStack offered) {
        com.google.gson.JsonArray items = breedable.getAsJsonArray("breed_items");
        if (items == null || offered.isEmpty()) return false;

        String id = AddonItems.identifierOf(offered.getItem());
        for (com.google.gson.JsonElement item : items) {
            if (item.isJsonPrimitive() && names(item.getAsString(), id)) return true;
        }
        return false;
    }

    /**
     * Whether food may put the entity in the mood at all, which every condition
     * `minecraft:breedable` states has to allow.
     *
     * `require_tame` is yes unless a pack says otherwise, so an entity nobody owns refuses the food
     * of a pack that never mentions it, and Mojang's own breedable entities each write it out.
     */
    private boolean willingToBreed(com.google.gson.JsonObject breedable, Player feeder) {
        if (tickCount < breedReadyAt) return false;
        if (Goals.flag(breedable, "require_tame", true) && ownerUuid() == null) return false;
        if (Goals.flag(breedable, "require_full_health", false) && getHealth() < getMaxHealth()) {
            return false;
        }
        if (!Goals.flag(breedable, "allow_sitting", false)
            && getPose() == net.minecraft.world.entity.Pose.SITTING) {
            return false;
        }
        return Goals.filter(breedable, "love_filters")
                .matches(new Filter.Context(this, feeder, feeder, ServerHooks.state()))
            && environmentAllowsBreeding(breedable);
    }

    /** `environment_requirements`: enough of the named blocks within the radius, for each entry. */
    private boolean environmentAllowsBreeding(com.google.gson.JsonObject breedable) {
        for (com.google.gson.JsonElement element
            : at.ioexception.mcaddon.util.Json.list(breedable, "environment_requirements")) {
            if (!element.isJsonObject()) continue;

            com.google.gson.JsonObject requirement = element.getAsJsonObject();
            java.util.Set<net.minecraft.world.level.block.Block> wanted = Goals.blocks(requirement, "blocks");
            int radius = (int) Math.max(0, Math.min(BREEDING_SEARCH_LIMIT, Goals.number(requirement, "radius", 0)));
            long found = net.minecraft.core.BlockPos.betweenClosedStream(
                    blockPosition().offset(-radius, -radius, -radius), blockPosition().offset(radius, radius, radius))
                .filter(position -> wanted.contains(level().getBlockState(position).getBlock()))
                .count();
            if (found < Goals.number(requirement, "count", 1)) return false;
        }
        return true;
    }

    /** The widest radius Bedrock lets `environment_requirements` search. */
    private static final int BREEDING_SEARCH_LIMIT = 16;

    /** How long food keeps an entity in the mood, which is the thirty seconds Java's animals get. */
    private static final int LOVE_TICKS = 600;

    /** When this entity may be put in the mood again after it last bred, in ticks of its own age. */
    private int breedReadyAt;

    /**
     * Whether `breeds_with` names the kind the other entity is.
     *
     * Mojang writes it as a map from each mate to the event breeding with it raises, and the
     * schema as a list of objects naming `mate_type`; either names who this entity pairs with.
     */
    public boolean breedsWith(AddonEntity other) {
        return breedingEntry(other) != null;
    }

    /**
     * Breeds with the mate, and answers whether a child is born where the two stand.
     *
     * The event `breeds_with` names for the mate is raised, which is how a pack makes its entity
     * pregnant, and `causes_pregnancy` is that event carrying the child instead of one being born.
     * The entity then rests for `breed_cooldown` seconds before food works on it again.
     */
    public boolean conceive(AddonEntity mate) {
        com.google.gson.JsonObject breedable = liveComponent("minecraft:breedable");
        com.google.gson.JsonElement entry = breedingEntry(mate);
        restAfterBreeding();

        String event = entry == null || !entry.isJsonObject() ? null
            : entry.getAsJsonObject().has("breed_event")
                ? Goals.event(entry.getAsJsonObject(), "breed_event")
                : Goals.text(entry.getAsJsonObject(), "event");
        fire(event);
        return !Goals.flag(breedable, "causes_pregnancy", false);
    }

    /** Leaves the mood and waits out `breed_cooldown`, whose seconds Bedrock defaults to sixty. */
    public void restAfterBreeding() {
        inLoveUntil = 0;
        breedReadyAt = tickCount + Goals.ticks(liveComponent("minecraft:breedable"), "breed_cooldown", 60);
    }

    /** The `breeds_with` entry naming the other entity's kind, or null where none does. */
    private com.google.gson.JsonElement breedingEntry(AddonEntity other) {
        com.google.gson.JsonObject breedable = liveComponent("minecraft:breedable");
        com.google.gson.JsonElement listed = breedable == null ? null : breedable.get("breeds_with");
        if (listed == null) return null;

        String kind = BuiltInRegistries.ENTITY_TYPE.getKey(other.getType()).toString();
        if (listed.isJsonObject() && !listed.getAsJsonObject().has("mate_type")) {
            return listed.getAsJsonObject().get(kind);
        }
        return at.ioexception.mcaddon.util.Json.list(breedable, "breeds_with").stream()
            .filter(com.google.gson.JsonElement::isJsonObject)
            .filter(entry -> kind.equals(Goals.text(entry.getAsJsonObject(), "mate_type")))
            .findFirst()
            .orElse(null);
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

    /**
     * What a pack says is born, or another of the same kind when it says nothing.
     *
     * A pack names the child in three places: `minecraft:offspring` names it outright, its
     * `offspring_pairs` name one per pair of parents, and `minecraft:breedable` names one per mate.
     * All three are the same statement, so whichever the pack wrote is taken.
     */
    public net.minecraft.world.entity.EntityType<?> offspringType() {
        com.google.gson.JsonObject component = definition.components().get("minecraft:offspring");
        String named = component == null ? null
            : at.ioexception.mcaddon.util.Json.text(component, "identifier", null);

        if (named == null) named = firstNamed(component, "offspring_pairs", "baby");
        if (named == null) {
            named = firstNamed(definition.components().get("minecraft:breedable"),
                "breeds_with", "baby_type");
        }
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
        if (!declaresNow("minecraft:behavior.sleep")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        long time = Platform.dayTime(level) % 24000L;
        boolean night = time >= 13000L && time < 23000L;
        setPose(night ? net.minecraft.world.entity.Pose.SLEEPING : net.minecraft.world.entity.Pose.STANDING);
        if (night) {
            getNavigation().stop();
            setDeltaMovement(0, getDeltaMovement().y, 0);
            lastRestedDay = Platform.dayTime(level) / 24000L;
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
        if (isPersistenceRequired() || !(level() instanceof net.minecraft.server.level.ServerLevel)) {
            return;
        }

        com.google.gson.JsonObject body = liveComponent("minecraft:despawn");
        if (body == null) return;

        Despawn rules = Despawn.from(body);
        if (rules.filtered()) {
            if (rules.filters().matches(new Filter.Context(this, null, null, ServerHooks.state()))) {
                discard();
            }
            return;
        }

        // With no filters Bedrock's standard rules decide, which are two distances in blocks and an
        // idle wait in seconds, the same pair of questions Java asks of a vanilla mob.
        Player nearest = level().getNearestPlayer(this, -1);
        if (nearest == null) return;
        if (rules.despawnsAt(Math.sqrt(distanceToSqr(nearest)), noActionTime, random.nextDouble())) {
            if (rules.removeChildren()) {
                getPassengers().forEach(net.minecraft.world.entity.Entity::discard);
            }
            discard();
        }
    }

    /**
     * Drinking away what is hurting it, which both drinking behaviours amount to.
     *
     * Bedrock's milk clears every effect and its potion drinking picks one to apply; what a pack
     * uses either for is an entity that does not stay poisoned, so the harmful ones are cleared.
     */
    private void drink() {
        boolean drinks = declaresNow("minecraft:behavior.drink_milk")
            || declaresNow("minecraft:behavior.drink_potion");
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
        if (!declaresNow("minecraft:behavior.take_flower")) return;

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
        if (!declaresNow("minecraft:behavior.celebrate_survive")) return;
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
        if (!declaresNow("minecraft:managed_wandering_trader") || isPersistenceRequired()) return;

        // Bedrock's own manager gives a trader two game days before it wanders off.
        if (tickCount > 48000) discard();
    }

    /** How long this entity stays willing to breed, counted in its own ticks. */
    private int inLoveUntil;

    /** Marks a newly born one as a baby, which a pack's render controller reads as `q.is_baby`. */
    void setYoung() {
        setValue("is_baby", true);
    }

    /**
     * Takes somebody into the set of people `minecraft:trust` says this entity will defend.
     *
     * Bedrock brings the component in through a component group at the moment somebody earns it, so
     * whoever the event named is the one trusted. An entity may trust several at once, which is the
     * whole difference between this and belonging to one owner.
     */
    public void trust(java.util.UUID player) {
        trusted.add(player);
        setValue("is_trusting", true);
    }

    /**
     * Whoever this entity defends: its owner, or the nearest of the players it has been told to trust.
     *
     * A pack's defending behaviour asks one entity, and Bedrock's trust holds several at once, so
     * the closest of them is the one whose fight the entity joins.
     */
    public net.minecraft.world.entity.LivingEntity guarded(double within) {
        net.minecraft.world.entity.LivingEntity owner = getOwner();
        if (owner != null || trusted.isEmpty()) return owner;

        double reach = within > 0 ? within : getAttributeValue(Attributes.FOLLOW_RANGE);
        return level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(reach)).stream()
            .filter(player -> trusted.contains(player.getUUID()))
            .min(java.util.Comparator.comparingDouble(this::distanceToSqr))
            .map(net.minecraft.world.entity.LivingEntity.class::cast)
            .orElse(null);
    }

    /** The players `minecraft:trust` has added, which is a set rather than the one owner. */
    private final java.util.Set<java.util.UUID> trusted = new java.util.LinkedHashSet<>();

    /**
     * Bedrock says an entity is angry; Java asks the entity, so the flag is kept here.
     *
     * Being angry is a state something put the entity into, so a pack writes `minecraft:angry` into
     * the group it switches on and never at the top: all twenty uses in Mojang's own behaviour pack
     * are inside a group. An entity counting `minecraft:anger_level` answers off its count instead.
     */
    public boolean isAngry() {
        return anger == null ? declaresNow("minecraft:angry") : anger.angry();
    }

    /** When `minecraft:angry` runs out, in ticks of this entity's own age. Negative until set. */
    public int calmAtTick() {
        return calmAtTick;
    }

    public void setCalmAtTick(int tick) {
        calmAtTick = tick;
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

    /**
     * Writes one of the entity's own values while the entity is still being built.
     *
     * `setValue` is the wrong call there twice over. It tells every client watching, and nothing is
     * watching an entity that is still in its constructor; and the state store belongs to a running
     * server, which a client joining a dedicated one has none of, while that client builds this same
     * entity out of the packet the server sent it. What a client should hold arrives over the
     * property sync instead, so here the write is simply left out.
     */
    private void setInitialValue(String name, Object value) {
        if (isClientSide() || ServerHooks.state() == null) return;

        ServerHooks.state().setProperty(getUUID(), name, value);
    }

    /** One of those values as it stands, or null when nothing has set it. */
    public Object value(String name) {
        return isClientSide() || ServerHooks.state() == null
            ? null : ServerHooks.state().property(getUUID(), name);
    }

    /** Who this entity belongs to, cached from the add-on state the taming wrote it into. */
    private java.util.UUID owner;
    private boolean ownerRead;

    /** What this entity last swallowed, which is what a loot table's `entity_killed` asks about. */
    private String killed;

    /**
     * Writes down what the entity just killed, so a table rolled over the body can ask what it was.
     *
     * Bedrock asks this of one table only, the one `minecraft:behavior.eat_mob` names, and that
     * table is rolled as the meal goes down rather than when anything dies. The answer therefore
     * belongs to the moment and is kept on the entity rather than in the add-on state.
     */
    public void setKilled(net.minecraft.world.entity.Entity victim) {
        killed = victim == null ? null
            : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(victim.getType()).toString();
    }

    /** The identifier of what this entity last killed, or null while it has killed nothing. */
    public String killed() {
        return killed;
    }

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

    /** Belongs to whoever the entity it was before belonged to. */
    public void keepOwnerOf(AddonEntity before) {
        java.util.UUID id = before.ownerUuid();
        if (id == null || ServerHooks.state() == null) return;

        owner = id;
        ownerRead = true;
        ServerHooks.state().setProperty(getUUID(), "owner", id.toString());
    }

    /** Takes over the trading experience of the entity it was before, and the tiers that opens. */
    public void keepTradeLevelOf(AddonEntity before) {
        tradeExperience = before.tradeExperience;
    }

    /** Leaves what the entity carries in its inventory on the ground where it stands. */
    public void dropInventory() {
        if (carried == null) return;

        net.minecraft.world.Containers.dropContents(level(), blockPosition(), carried);
        carried.clearContent();
    }

    /** Makes one of the noises the entity's `sounds.json` names, by Bedrock's name for the moment. */
    public void makeNoise(String moment) {
        net.minecraft.sounds.SoundEvent made = moment == null ? null : noise(moment);
        if (made != null) playSound(made, getSoundVolume(), getVoicePitch());
    }

    /**
     * Starts the wait a delayed `minecraft:transformation` states, and answers whether there is one.
     *
     * `begin_transform_sound` is made as it starts. The wait ends with the transformation unless the
     * group carrying the component has gone by then, which is how a cure is called off.
     */
    public boolean startTransforming(com.google.gson.JsonObject body) {
        makeNoise(at.ioexception.mcaddon.util.Json.text(body, "begin_transform_sound", null));
        transforming = Transformation.tryFrom(body, this);
        return transforming != null;
    }

    private Transformation transforming;

    private void keepTransforming() {
        if (transforming == null) return;
        if (liveComponent("minecraft:transformation") == null) {
            transforming = null;
            return;
        }
        if (!transforming.tick(this)) return;

        com.google.gson.JsonObject body = transforming.body();
        transforming = null;
        EntityEvents.transform(this, body, ServerHooks.state());
    }

    /** The owner's id, or null while nobody owns this entity. */
    /**
     * Whether the other entity is on this one's side: its owner, or another entity the same
     * player owns, which a pack's targeting leaves alone unless it says otherwise.
     */
    public boolean sides(net.minecraft.world.entity.Entity other) {
        java.util.UUID mine = ownerUuid();
        if (mine == null) return false;

        return mine.equals(other.getUUID())
            || (other instanceof AddonEntity fellow && mine.equals(fellow.ownerUuid()));
    }

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
        return definition != null && declaresNow("minecraft:dimension_bound");
    }

    /**
     * What a lead may tie this entity to.
     *
     * Bedrock lists the kinds in `minecraft:leashable_to`; Java asks nothing, so a pack naming a
     * list is honoured by refusing anything outside it.
     */
    public boolean canBeLeashedTo(net.minecraft.world.entity.Entity holder) {
        com.google.gson.JsonObject component = liveComponent("minecraft:leashable_to");
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

        com.google.gson.JsonObject body = liveComponent("minecraft:leashable");
        String event = leashed
            ? ServerEntityDefinition.event(body, "on_leash")
            : ServerEntityDefinition.event(body, "on_unleash");
        if (event != null) EntityEvents.trigger(this, event, null, ServerHooks.state());
    }

    private boolean wasLeashed;

    /** A lead goes on an entity carrying `minecraft:leashable` and on nothing else, as in Bedrock. */
    // 1.21 and 1.21.1 ask this of `Mob` without the annotation; 1.20.1 asks it with the player.
    //? if >=1.21.2 {
    @Override
    //?}
    public boolean canBeLeashed() {
        return definition == null || declaresNow("minecraft:leashable");
    }

    //? if <1.21 {
    /*@Override
    public boolean canBeLeashed(Player player) {
        return !isLeashed() && canBeLeashed();
    }
    *///?}

    /**
     * The preset of `minecraft:leashable` that applies to the entity holding the lead, which is the
     * first whose `filter` holds with the holder as `other`; an empty one where none does.
     *
     * Its distances are blocks: the lead pulls past `hard_distance`, which Bedrock defaults to seven,
     * and breaks past `max_distance`, which it defaults to the twelve Java breaks at as well.
     */
    private com.google.gson.JsonObject leashPreset() {
        net.minecraft.world.entity.Entity holder = Platform.leashHolder(this);
        for (com.google.gson.JsonElement entry
            : at.ioexception.mcaddon.util.Json.list(liveComponent("minecraft:leashable"), "presets")) {
            if (!entry.isJsonObject()) continue;

            com.google.gson.JsonObject preset = entry.getAsJsonObject();
            if (holder == null && preset.has("filter")) continue;
            if (holder == null || Goals.filter(preset, "filter")
                .matches(new Filter.Context(this, holder, holder, ServerHooks.state()))) {
                return preset;
            }
        }
        return new com.google.gson.JsonObject();
    }

    // Before 26 Java fixes how far a lead stretches and gives an entity no say in it.
    //? if >=26 {
    @Override
    public double leashSnapDistance() {
        return definition == null ? super.leashSnapDistance() : Goals.number(leashPreset(), "max_distance", 12);
    }

    @Override
    public double leashElasticDistance() {
        return definition == null ? super.leashElasticDistance() : Goals.number(leashPreset(), "hard_distance", 7);
    }

    /** `quad_dampened` is four springs, one at each side, which is Java's quad lead. */
    @Override
    public boolean supportQuadLeash() {
        return definition != null && "quad_dampened".equals(Goals.text(leashPreset(), "spring_type"));
    }

    /** `soft_distance` is how far off the holder the entity starts walking back towards it. */
    @Override
    public void closeRangeLeashBehaviour(net.minecraft.world.entity.Entity holder) {
        if (definition == null || distanceTo(holder) > Goals.number(leashPreset(), "soft_distance", 4)) {
            super.closeRangeLeashBehaviour(holder);
        }
    }
    //?}

    /**
     * Whether a player may take the lead off whatever else holds this entity.
     *
     * `can_be_stolen` is Bedrock's answer, and it defaults to yes. From 26 Java lets a player with a
     * lead take a mob off a post or another mob, so a pack saying no refuses that player; before 26
     * Java lets nobody take a mob that is already tied, which is the answer a pack saying no wants.
     */
    //? if >=26 {
    @Override
    public boolean canHaveALeashAttachedTo(net.minecraft.world.entity.Entity holder) {
        boolean stealing = holder instanceof Player && isLeashed() && getLeashHolder() != holder;
        if (stealing && definition != null
            && !Goals.flag(liveComponent("minecraft:leashable"), "can_be_stolen", true)) {
            return false;
        }
        return super.canHaveALeashAttachedTo(holder);
    }
    //?}

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
            if (named.isJsonPrimitive()) wanted.add(AddonBlocks.identifierOf(named.getAsString()));
        }
        if (wanted.isEmpty()) return;

        net.minecraft.world.phys.AABB box = getBoundingBox().inflate(0.2);
        net.minecraft.core.BlockPos.betweenClosedStream(box)
            .filter(at -> wanted.contains(AddonBlocks.identifierOf(level.getBlockState(at))))
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

        String effect = liveText("minecraft:mob_effect", "mob_effect");
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
     * `damage_per_tick` is the damage of one hit rather than a rate: a large magma cube writes six
     * and hits for six, the same six Java deals. What paces it is the half second a hurt entity is
     * invulnerable for, which is also the sensor interval this runs on.
     */
    private void attackAround() {
        if (!declares("minecraft:area_attack")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        double range = number(definition, "minecraft:area_attack", "damage_range", 0.2);
        float damage = (float) number(definition, "minecraft:area_attack", "damage_per_tick", 2);
        Filter filter = Filter.parse(componentBody("minecraft:area_attack").get("entity_filter"));

        for (net.minecraft.world.entity.LivingEntity nearby : level.getEntitiesOfClass(
            net.minecraft.world.entity.LivingEntity.class, getBoundingBox().inflate(range))) {
            if (nearby == this) continue;
            if (!filter.matches(new Filter.Context(this, nearby, nearby, ServerHooks.state()))) continue;

            Platform.hurt(nearby, level, damageSources().mobAttack(this), damage);
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

        String named = liveText("minecraft:trail", "block_type");
        net.minecraft.world.level.block.state.BlockState trail = named == null ? null
            : AddonBlocks.stateOf(named, java.util.Map.of(), AddonEntities::unsupported);
        if (trail == null) return;

        if (!Filter.parse(componentBody("minecraft:trail").get("spawn_filter"))
            .matches(new Filter.Context(this, this, null, ServerHooks.state()))) {
            return;
        }

        net.minecraft.core.BlockPos under = blockPosition().below();
        if (!level.getBlockState(under).canBeReplaced()) return;

        level.setBlockAndUpdate(under, trail);
    }

    /**
     * Ripens the crop the entity is standing on, which is what `minecraft:grows_crop` does.
     *
     * `chance` is per tick of the entity and `charges` is how many crops it may ripen before the
     * component is spent, which is what stops one bee turning a whole field over. Java already knows
     * what growing means for every block that can be given bone meal, so the block decides rather
     * than a table of crops.
     */
    private void growCrops() {
        if (!declaresNow("minecraft:grows_crop")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        if (cropCharges < 0) {
            cropCharges = (int) liveNumber("minecraft:grows_crop", "charges", 10);
        }
        if (cropCharges == 0) return;

        double chance = liveNumber("minecraft:grows_crop", "chance", 0);
        if (random.nextDouble()
            > at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits.chanceOverTicks(
                chance, Sensors.INTERVAL_TICKS)) {
            return;
        }

        net.minecraft.core.BlockPos at = blockPosition();
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(at);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.BonemealableBlock crop)) return;
        if (!Platform.canBonemeal(crop, level, at, state)) return;

        crop.performBonemeal(level, random, at, state);
        cropCharges--;
    }

    /** How many crops the entity may still ripen, counted down from the component's own charges. */
    private int cropCharges = -1;

    /**
     * Fires the event a pack asked for while somebody is looking at the entity.
     *
     * `minecraft:looked_at` is what makes an enderman's stare work, and a pack uses it for anything
     * that should react to being watched. Bedrock measures the angle rather than the distance.
     */
    private void noticeBeingWatched() {
        if (!declares("minecraft:looked_at")) return;

        String event = liveText("minecraft:looked_at", "looked_at_event");
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
        com.google.gson.JsonObject buoyant = liveComponent("minecraft:buoyant");
        boolean floating = declaresNow("minecraft:floats_in_liquid") ? isInWater() || isInLava()
            : buoyant != null
                && ((isInWater() && floatsOn(buoyant, "water")) || (isInLava() && floatsOn(buoyant, "lava")));
        if (!floating) return;

        double lift = Goals.number(buoyant, "base_buoyancy", 1) * BUOYANT_LIFT;
        setDeltaMovement(getDeltaMovement().add(0, lift, 0));
        fallDistance = 0;
    }

    /** The lift a `base_buoyancy` of one gives each tick, in blocks a tick each tick. */
    private static final double BUOYANT_LIFT = 0.03;

    /**
     * Whether `liquid_blocks` names the liquid, still or flowing. A component naming none floats in
     * water, which is where Java floats every mob that floats.
     */
    private static boolean floatsOn(com.google.gson.JsonObject buoyant, String liquid) {
        java.util.List<com.google.gson.JsonElement> named =
            at.ioexception.mcaddon.util.Json.list(buoyant, "liquid_blocks");
        if (named.isEmpty()) return liquid.equals("water");

        return named.stream()
            .filter(com.google.gson.JsonElement::isJsonPrimitive)
            .map(block -> block.getAsString().replace("minecraft:", "").replace("flowing_", ""))
            .anyMatch(liquid::equals);
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
            if (dryingFor > 0) fire(liveText("minecraft:drying_out_timer", "stopped_drying_out_event"));
            dryingFor = 0;
            return;
        }

        int allowed = (int) (number(definition, "minecraft:drying_out_timer", "total_time", 60) * 20);
        if (dryingFor == 0) fire(liveText("minecraft:drying_out_timer", "dried_out_event"));

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
        blockedBy(target);
        if (hit && target instanceof net.minecraft.world.entity.LivingEntity struck
            && Goals.flag(liveComponent("minecraft:angry"), "broadcast_anger_on_attack", false)) {
            broadcastAnger(struck);
        }
        return hit;
    }

    /**
     * Hands the entity's quarry to the others `minecraft:angry` names, which is how Bedrock spreads
     * anger: each of them takes the quarry as its own target, and its own `on_target_acquired` is
     * what makes it angry in turn, the way a wolf pack turns on whoever hurt one of them.
     *
     * The others are the same definition within `broadcast_range` blocks, or the families
     * `broadcast_targets` lists, and `broadcast_filters` is asked about each with this entity as
     * `self` and the one told as `other`. One already after something keeps what it is after.
     */
    private void broadcastAnger(net.minecraft.world.entity.LivingEntity quarry) {
        com.google.gson.JsonObject angry = liveComponent("minecraft:angry");
        if (angry == null || quarry == null || !quarry.isAlive()) return;

        double range = Goals.number(angry, "broadcast_range", Goals.number(angry, "broadcastRange", 20));
        java.util.Set<String> families = new java.util.HashSet<>();
        at.ioexception.mcaddon.util.Json.list(angry, "broadcast_targets").stream()
            .filter(com.google.gson.JsonElement::isJsonPrimitive)
            .forEach(family -> families.add(family.getAsString()));
        Filter allowed = Goals.filter(angry, "broadcast_filters");

        level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, getBoundingBox().inflate(range),
                other -> other != this && other != quarry && other.getTarget() == null
                    && (families.isEmpty() ? other.getType() == getType()
                        : !java.util.Collections.disjoint(
                            at.ioexception.mcaddon.bedrock.behaviour.Families.of(other), families))
                    && allowed.matches(new Filter.Context(this, other, other, ServerHooks.state())))
            .forEach(other -> other.setTarget(quarry));
    }

    /** Whether `minecraft:angry` is live and says its anger spreads, in either spelling of the flag. */
    private boolean spreadsAnger() {
        com.google.gson.JsonObject angry = liveComponent("minecraft:angry");
        return Goals.flag(angry, "broadcast_anger", Goals.flag(angry, "broadcastAnger", false));
    }

    /**
     * While angry and broadcasting, the quarry is handed on, and `angry_sound` is made again after
     * each wait `sound_interval` spans.
     */
    private void beAngry() {
        com.google.gson.JsonObject angry = liveComponent("minecraft:angry");
        if (angry == null) return;
        if (spreadsAnger()) broadcastAnger(getTarget());

        String sound = Goals.text(angry, "angry_sound");
        if (sound == null || tickCount < nextAngrySoundAt) return;

        net.minecraft.sounds.SoundEvent made = noise(sound);
        if (made != null) playSound(made, getSoundVolume(), getVoicePitch());
        com.google.gson.JsonObject interval = angry.has("sound_interval") && angry.get("sound_interval").isJsonObject()
            ? angry.getAsJsonObject("sound_interval") : null;
        double low = Goals.number(interval, "range_min", 0);
        double high = Math.max(low, Goals.number(interval, "range_max", 0));
        nextAngrySoundAt = tickCount + (int) ((low + random.nextDouble() * (high - low))
            * at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits.TICKS_PER_SECOND);
    }

    private int nextAngrySoundAt;

    /**
     * What `minecraft:ravager_blocked` does to an entity whose blow a shield turned away.
     *
     * Bedrock throws the entity back and picks one of the reactions the pack weighted, which is how
     * a ravager comes to stand there stunned. Java answers whether the target was blocking and
     * leaves what follows to whoever swung.
     */
    private void blockedBy(net.minecraft.world.entity.Entity target) {
        if (definition == null || !declares("minecraft:ravager_blocked")) return;
        if (!(target instanceof net.minecraft.world.entity.LivingEntity living) || !living.isBlocking()) {
            return;
        }

        com.google.gson.JsonObject body = componentBody("minecraft:ravager_blocked");
        double thrown = Goals.number(body, "knockback_strength", 3);
        net.minecraft.world.phys.Vec3 away = position().subtract(target.position()).normalize();
        setDeltaMovement(getDeltaMovement().add(away.x * thrown * BLOCKED_KNOCKBACK, 0,
            away.z * thrown * BLOCKED_KNOCKBACK));

        java.util.List<com.google.gson.JsonElement> choices =
            at.ioexception.mcaddon.util.Json.list(body, "reaction_choices");
        double roll = random.nextDouble() * choices.stream()
            .filter(com.google.gson.JsonElement::isJsonObject)
            .mapToDouble(choice -> Goals.number(choice.getAsJsonObject(), "weight", 1)).sum();
        for (com.google.gson.JsonElement element : choices) {
            if (!element.isJsonObject()) continue;

            roll -= Goals.number(element.getAsJsonObject(), "weight", 1);
            if (roll > 0) continue;

            fire(Goals.event(element.getAsJsonObject(), "value"));
            return;
        }
    }

    /** What one point of the blocked knockback Bedrock states is worth as a Java velocity. */
    private static final double BLOCKED_KNOCKBACK = 0.1;

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
     * `minecraft:shooter` names the projectile by its entity id, which is either one of the game's
     * own, aimed on Java's arc for a bow, or one the pack declared, which flies on the arc its own
     * `minecraft:projectile` describes.
     */
    @Override
    public void performRangedAttack(net.minecraft.world.entity.LivingEntity target, float power) {
        if (definition == null || !(level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        String named = liveText("minecraft:shooter", "def");
        net.minecraft.world.entity.EntityType<?> type =
            AddonEntities.typeOf(named == null ? "minecraft:arrow" : named);
        net.minecraft.world.entity.Entity shot = type == null ? null : Platform.createMob(type, level);
        if (shot instanceof AddonEntity own && own.flight != null) {
            shootOwn(level, own, target);
            return;
        }
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

    /**
     * Sends this entity off along an aim, for a pack's own projectile.
     *
     * The shooter may be null, which is a projectile nobody fired: it flies and hits like any
     * other and its damage names no attacker.
     */
    public void launchAsProjectile(net.minecraft.world.entity.LivingEntity shooter,
        net.minecraft.world.phys.Vec3 aim) {
        if (flight != null) flight.launch(shooter, aim, level().getDifficulty().getId());
    }

    /**
     * Fires this pack projectile out of what a living entity holds, along where it is looking.
     *
     * The speed is the `power` the projectile's own component states, scaled by `share`: how far a
     * bow was drawn, or the launch power a throwable item gives. Answers false for an entity whose
     * pack never made it a projectile, which the caller then sends off on its own.
     */
    public boolean fireFrom(net.minecraft.world.entity.LivingEntity shooter, double share) {
        if (flight == null) return false;

        net.minecraft.world.phys.Vec3 from = flight.muzzle(shooter);
        Platform.placeAt(this, from.x, from.y, from.z, shooter.getYRot(), shooter.getXRot());
        launchAsProjectile(shooter, shooter.getLookAngle());
        setDeltaMovement(getDeltaMovement().scale(share));
        return true;
    }

    /** Whoever fired this projectile, or null for one nobody fired and for an entity that is none. */
    public net.minecraft.world.entity.LivingEntity firedBy() {
        return flight == null ? null : flight.shooter();
    }

    /** A projectile the pack declared itself, placed at its own anchor and sent along its own arc. */
    private void shootOwn(net.minecraft.server.level.ServerLevel level, AddonEntity shot,
        net.minecraft.world.entity.LivingEntity target) {
        net.minecraft.world.phys.Vec3 from = shot.flight.muzzle(this);
        Platform.placeAt(shot, from.x, from.y, from.z, getYRot(), getXRot());
        net.minecraft.world.phys.Vec3 aim = shot.flight.aimsAtTarget()
            ? target.getEyePosition().subtract(from) : getLookAngle();
        shot.launchAsProjectile(this, aim);
        level.addFreshEntity(shot);
    }

    /**
     * The goals a pack declared, at the priority and the speed it asked for.
     *
     * Bedrock and Java both order goals by a number where lower wins, and both state a goal's speed
     * as a multiplier of the entity's own movement speed, so each carries straight across. A goal
     * read without them runs in the order the components happened to be written in, at whatever
     * speed the mod chose, which is what makes a panicking mob amble and a strolling one sprint.
     */
    /** The goals running at this moment, by name, so a test can ask how long one ran for. */
    public java.util.List<String> runningGoals() {
        return goalSelector.getAvailableGoals().stream()
            .filter(net.minecraft.world.entity.ai.goal.WrappedGoal::isRunning)
            .map(running -> running.getGoal().toString())
            .toList();
    }

    /** The priorities this entity's goals went in at, which its pack's `priority` fields state. */
    public java.util.Set<Integer> goalPriorities() {
        return goalPriorities;
    }

    private final java.util.Set<Integer> goalPriorities = new java.util.LinkedHashSet<>();

    /** What a `minecraft:behavior.tempt` goal says tempts this entity, by item id. */
    private static java.util.Set<String> tempting(com.google.gson.JsonObject body) {
        java.util.Set<String> items = new java.util.LinkedHashSet<>();
        for (com.google.gson.JsonElement item : at.ioexception.mcaddon.util.Json.list(body, "items")) {
            if (item.isJsonPrimitive()) {
                items.add(item.getAsString());
            } else if (item.isJsonObject()) {
                String named = at.ioexception.mcaddon.util.Json.text(item.getAsJsonObject(), "item", null);
                if (named != null) items.add(named);
            }
        }
        return items;
    }

    private void addGoals() {
        int order = 0;
        int priority = 0;
        java.util.Map<String, com.google.gson.JsonObject> sites = new java.util.LinkedHashMap<>();
        sites.put(null, new com.google.gson.JsonObject());
        definition.components().forEach((component, body) -> sites.get(null).add(component, body));
        sites.putAll(definition.componentGroups());

        for (java.util.Map.Entry<String, com.google.gson.JsonObject> site : sites.entrySet()) {
            for (String behaviour : site.getValue().keySet()) {
                if (!definition.behaviours().contains(behaviour)) continue;

                com.google.gson.JsonElement declared = site.getValue().get(behaviour);
                priority = addBehaviour(behaviour, declared.isJsonObject()
                    ? declared.getAsJsonObject() : new com.google.gson.JsonObject(), ++order, site.getKey());
            }
        }

        // Bedrock spells answering the worst attacker both as a behaviour and as a plain component,
        // and the two ask for the same fight.
        if (declares("minecraft:wither_target_highest_damage")) {
            targetSelector.addGoal(priority + 1, WitherGoal.answeringTheWorstAttacker(this,
                componentBody("minecraft:wither_target_highest_damage")));
        }
    }

    /** What a pack's goals are added to, so a goal a group brings can be wrapped on the way in. */
    private interface Selector {
        void add(int priority, net.minecraft.world.entity.ai.goal.Goal goal);
    }

    /**
     * Adds the goal one declaration of a behaviour asks for, read from that declaration's own body,
     * and answers the priority it went in at. One that states none goes in at its place in the order.
     *
     * A goal declared at the top level always runs; one a group declares runs while the group is on.
     * A behaviour written in two groups is two goals, each with the settings its own group gave it.
     */
    private int addBehaviour(String behaviour, com.google.gson.JsonObject goal, int order, String group) {
        int priority = (int) Goals.number(goal, "priority", order);
        goalPriorities.add(priority);
        Selector goals = (at, added) -> goalSelector.addGoal(at,
            group == null ? added : new GroupGoal(this, group, added));
        Selector targets = (at, added) -> targetSelector.addGoal(at,
            group == null ? added : new GroupGoal(this, group, added));
        double speed = Goals.number(goal, "speed_multiplier", 1);

        switch (behaviour) {
            case "minecraft:behavior.float" -> goals.add(priority, new FloatGoal(this));
            case "minecraft:behavior.panic" -> goals.add(priority, new PanicGoal(this, speed));
            case "minecraft:behavior.swell" ->
                goals.add(priority, new SwellGoal(this, goal));
            case "minecraft:behavior.beg" ->
                goals.add(priority, new BegGoal(this, goal));

            // One squid moves five ways, and Bedrock names each of them separately.
            case "minecraft:behavior.squid_idle" ->
                goals.add(priority, SquidGoal.idling(this));
            case "minecraft:behavior.squid_dive" ->
                goals.add(priority, SquidGoal.diving(this));
            case "minecraft:behavior.squid_flee" ->
                goals.add(priority, SquidGoal.fleeing(this));
            case "minecraft:behavior.squid_move_away_from_ground" ->
                goals.add(priority, SquidGoal.leavingGround(this));
            case "minecraft:behavior.squid_out_of_water" ->
                goals.add(priority, SquidGoal.flopping(this));
            // A slime turns, hops, floats and closes on a target, and Bedrock names each of the
            // four separately.
            case "minecraft:behavior.slime_random_direction" ->
                goals.add(priority, SlimeGoal.turning(this, goal));
            case "minecraft:behavior.slime_keep_on_jumping" ->
                goals.add(priority, SlimeGoal.hopping(this, goal));
            case "minecraft:behavior.slime_float" ->
                goals.add(priority, SlimeGoal.floating(this, goal));
            case "minecraft:behavior.slime_attack" ->
                goals.add(priority, SlimeGoal.attacking(this, goal));

            case "minecraft:behavior.random_stroll" -> goals.add(priority, StrollGoal.walking(this, goal));
            case "minecraft:behavior.move_outdoors", "minecraft:behavior.explore_outskirts" -> {
                // `interval` is the one in N chance of choosing the goal, which Java counts the
                // same way.
                WaterAvoidingRandomStrollGoal stroll = new WaterAvoidingRandomStrollGoal(this, speed);
                stroll.setInterval((int) Goals.number(goal, "interval", 120));
                goals.add(priority, stroll);
            }
            case "minecraft:behavior.look_at_player" -> goals.add(priority, LookGoal.atPlayers(this, goal));
            case "minecraft:behavior.look_at_trading_player" ->
                goals.add(priority, LookGoal.atTradingPlayer(this, goal));
            case "minecraft:behavior.random_look_around",
                 "minecraft:behavior.random_look_around_and_sit" ->
                goals.add(priority, new RandomLookAroundGoal(this));

            case "minecraft:behavior.look_at_entity" -> goals.add(priority, LookGoal.atEntities(this, goal));

            // Bedrock names the kind of wandering; Java has one stroll goal and a speed.
            case "minecraft:behavior.move_indoors",
                 "minecraft:behavior.move_towards_dwelling_restriction",
                 "minecraft:behavior.move_towards_home_restriction",
                 "minecraft:behavior.move_towards_restriction",
                 "minecraft:behavior.go_home" ->
                goals.add(priority, new MoveTowardsRestrictionGoal(this, speed));

            case "minecraft:behavior.move_to_village" ->
                goals.add(priority, new MoveBackToVillageGoal(this, 0.6, false));
            // Java's goal asks separately whether the route may run at night and whether the mob
            // can get through a door. Bedrock states neither, so the walk is allowed at any
            // hour and doors are left to the navigator.
            case "minecraft:behavior.move_through_village" ->
                goals.add(priority,
                    new MoveThroughVillageGoal(this, 0.6, false, 4, () -> false));
            case "minecraft:behavior.mingle" ->
                goals.add(priority, new StrollThroughVillageGoal(this, 60));
            case "minecraft:behavior.move_towards_target" ->
                goals.add(priority, new MoveTowardsTargetGoal(this, 0.9, 32f));

            case "minecraft:behavior.random_fly", "minecraft:behavior.random_hover",
                 "minecraft:behavior.hover", "minecraft:behavior.float_wander" ->
                goals.add(priority, new WaterAvoidingRandomFlyingGoal(this, 1.0));
            case "minecraft:behavior.random_swim", "minecraft:behavior.swim_wander" ->
                goals.add(priority, StrollGoal.swimming(this, goal));
            case "minecraft:behavior.move_to_water", "minecraft:behavior.move_to_liquid" ->
                goals.add(priority, new TryFindWaterGoal(this));
            case "minecraft:behavior.swim_up_for_breath" ->
                goals.add(priority, new BreathAirGoal(this));

            case "minecraft:behavior.eat_block" ->
                goals.add(priority, new EatBlockGoal(this));
            // Bedrock's find_cover and flee_sun both end with the mob standing out of the light.
            case "minecraft:behavior.flee_sun", "minecraft:behavior.find_cover" ->
                goals.add(priority, new FleeSunGoal(this, 1.0));
            case "minecraft:behavior.restrict_sun" ->
                goals.add(priority, new RestrictSunGoal(this));
            // Java asks per difficulty whether a door may be broken; Bedrock states the ability
            // once and leaves the difficulty out of it.
            case "minecraft:behavior.break_door", "minecraft:annotation.break_door" ->
                goals.add(priority, new BreakDoorGoal(this, difficulty -> true));
            case "minecraft:behavior.follow_mob" ->
                goals.add(priority, new FollowMobGoal(this, 1.0, 3f, 7f));
            case "minecraft:behavior.leap_at_target",
                 "minecraft:behavior.stalk_and_pounce_on_target" ->
                goals.add(priority, new LeapAtTargetGoal(this, 0.4f));
            case "minecraft:behavior.play", "minecraft:behavior.stroll_towards_village" ->
                goals.add(priority, new WaterAvoidingRandomStrollGoal(this, speed));
            case "minecraft:behavior.follow_owner" ->
                goals.add(priority, new FollowOwnerGoal(this, goal));
            // Java steers a mount from its rider: a mob riding another plans with the mount's
            // navigator and switches the mount's own movement goals off, so the chase a rider's
            // attack goal runs is the mount's, at the reach that goal keeps.
            case "minecraft:behavior.mount_pathing" -> { }

            case "minecraft:behavior.pickup_items" ->
                goals.add(priority, new PickupItemsGoal(this, goal));

            case "minecraft:behavior.avoid_mob_type" ->
                goals.add(priority, new AvoidMobsGoal(this, goal));
            // Hiding and playing are both a Bedrock villager moving somewhere for a reason Java
            // writes into its own villager. What carries across is the moving.
            case "minecraft:behavior.hide" -> {
                TargetSelection avoided = TargetSelection.from(goal, Monster.class, 8);
                goals.add(priority, new AvoidEntityGoal<>(this, avoided.kind(),
                    (float) avoided.maxDistance(),
                    Goals.number(goal, "walk_speed_modifier", 1),
                    Goals.number(goal, "sprint_speed_modifier", 1.2)));
            }
            case "minecraft:behavior.avoid_block" ->
                goals.add(priority, new AvoidBlockGoal(this, goal));

            // Each of these three walks to what a memory sensor wrote down rather than to
            // anything the goal itself was given, which is why none of them carries a target.
            case "minecraft:memory_behavior.follow_target" ->
                goals.add(priority, MemoryGoal.following(this));
            case "minecraft:memory_behavior.flee_threat" ->
                goals.add(priority, MemoryGoal.fleeing(this));
            case "minecraft:memory_behavior.move_to_position" ->
                goals.add(priority, MemoryGoal.movingToPosition(this));

            case "minecraft:behavior.tempt" -> goals.add(priority, new TemptedGoal(this, goal, tempting(goal)));
            case "minecraft:behavior.float_tempt" ->
                goals.add(priority, Platform.temptGoal(this, speed, tempting(goal)));
            case "minecraft:behavior.open_door", "minecraft:annotation.open_door" ->
                goals.add(priority,
                    new OpenDoorGoal(this, Goals.flag(goal, "close_door_after", true)));

            case "minecraft:behavior.melee_attack", "minecraft:behavior.melee_box_attack",
                 "minecraft:behavior.delayed_attack", "minecraft:behavior.stomp_attack" ->
                goals.add(priority,
                    new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(this, speed,
                        Goals.flag(goal, "track_target", false)));
            // A charge is a melee attack run at, which Java expresses as the speed the goal
            // closes the distance with.
            case "minecraft:behavior.charge_attack" ->
                goals.add(priority,
                    new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(this, 1.4, false));
            case "minecraft:behavior.nearest_attackable_target",
                 "minecraft:behavior.nearest_prioritized_attackable_target" ->
                targets.add(priority, new HuntGoal(this, goal, TargetSelection.from(goal, Player.class,
                    Goals.number(goal, "within_radius", 0))));
            case "minecraft:behavior.hurt_by_target", "minecraft:behavior.target_when_pushed" ->
                targets.add(priority, new RetaliateGoal(this, goal,
                    TargetSelection.from(goal, net.minecraft.world.entity.LivingEntity.class, 0)));

            // Bedrock states the gap between shots in seconds and the reach in blocks; Java's
            // goal counts the gap in ticks and asks the entity itself to release the projectile.
            case "minecraft:behavior.ranged_attack" -> goals.add(priority,
                new net.minecraft.world.entity.ai.goal.RangedAttackGoal(this,
                    number(definition, behaviour, "speed_multiplier", 1),
                    (int) (number(definition, behaviour, "attack_interval_min", 1) * 20),
                    (int) (number(definition, behaviour, "attack_interval_max", 1) * 20),
                    (float) number(definition, behaviour, "attack_radius", 16)));
            case "minecraft:behavior.ocelotattack" -> goals.add(priority,
                new net.minecraft.world.entity.ai.goal.OcelotAttackGoal(this));

            case "minecraft:behavior.follow_parent" -> goals.add(priority,
                FollowKinGoal.parent(this, goal));
            case "minecraft:behavior.follow_caravan" -> goals.add(priority,
                FollowKinGoal.caravan(this, goal));
            case "minecraft:behavior.follow_target_leader" -> goals.add(priority,
                FollowKinGoal.leader(this, goal));
            case "minecraft:behavior.follow_target_captain" -> goals.add(priority,
                FollowKinGoal.captain(this, goal));

            case "minecraft:behavior.move_to_block" -> goals.add(priority,
                MoveToMatchingBlockGoal.named(this, goal));
            case "minecraft:behavior.move_to_land" -> goals.add(priority,
                MoveToMatchingBlockGoal.land(this, goal));
            case "minecraft:behavior.move_to_lava" -> goals.add(priority,
                MoveToMatchingBlockGoal.lava(this, goal));
            case "minecraft:behavior.move_to_random_block" -> goals.add(priority,
                MoveToMatchingBlockGoal.anywhere(this, goal));

            case "minecraft:behavior.equip_item" -> goals.add(priority,
                new EquipItemGoal(this, componentBody("minecraft:equip_item")));
            case "minecraft:behavior.hold_ground" ->
                goals.add(priority, HoldGroundGoal.standing(this));
            case "minecraft:behavior.look_at_target" ->
                goals.add(priority, HoldGroundGoal.watching(this));
            // Java writes door handling as two goals under one base, and opening is the half
            // Bedrock's own door interaction does.
            case "minecraft:behavior.door_interact" ->
                goals.add(priority, new OpenDoorGoal(this, true));

            case "minecraft:behavior.raid_garden" -> goals.add(priority,
                MoveToMatchingBlockGoal.raid(this, goal));
            case "minecraft:behavior.harvest_farm_block" -> goals.add(priority,
                MoveToMatchingBlockGoal.harvest(this, goal));
            case "minecraft:behavior.fertilize_farm_block" -> goals.add(priority,
                MoveToMatchingBlockGoal.fertilize(this, goal));
            // Java's own goal for this is the one its zombie uses on the same block.
            case "minecraft:behavior.stomp_turtle_egg" -> goals.add(priority,
                new net.minecraft.world.entity.ai.goal.RemoveBlockGoal(
                    net.minecraft.world.level.block.Blocks.TURTLE_EGG, this,
                    number(definition, behaviour, "speed_multiplier", 1),
                    (int) number(definition, behaviour, "search_height", 2)));

            case "minecraft:behavior.timer_flag_1", "minecraft:behavior.timer_flag_2",
                 "minecraft:behavior.timer_flag_3" ->
                goals.add(priority, new TimerFlagGoal(this, goal,
                    behaviour.substring("minecraft:behavior.".length())));
            case "minecraft:behavior.random_sitting" ->
                goals.add(priority, RestGoal.sitting(this, goal));
            case "minecraft:behavior.lay_down" ->
                goals.add(priority, RestGoal.lyingDown(this, goal));
            case "minecraft:behavior.knockback_roar" ->
                goals.add(priority, new KnockbackRoarGoal(this, goal));
            case "minecraft:behavior.eat_carried_item" ->
                goals.add(priority, new EatCarriedItemGoal(this, goal));
            case "minecraft:behavior.move_around_target" ->
                goals.add(priority, CircleTargetGoal.walking(this, goal));
            case "minecraft:behavior.jump_around_target" ->
                goals.add(priority, CircleTargetGoal.jumping(this, goal));

            // What the entity does because it belongs to somebody. Each of these reads back the
            // owner that taming stored, so an untamed entity carrying one simply never starts it.
            case "minecraft:behavior.owner_hurt_target" -> targets.add(priority,
                OwnerTargetGoal.whatTheOwnerHurt(this, goal));
            case "minecraft:behavior.owner_hurt_by_target" -> targets.add(priority,
                OwnerTargetGoal.whoHurtTheOwner(this, goal));
            case "minecraft:behavior.defend_trusted_target" -> targets.add(priority,
                OwnerTargetGoal.defendingTheOwner(this, goal));
            case "minecraft:behavior.teleport_to_owner" -> goals.add(priority,
                new TeleportToOwnerGoal(this, goal));
            case "minecraft:behavior.go_and_give_items_to_owner" -> goals.add(priority,
                new GiveItemsToOwnerGoal(this, goal));
            case "minecraft:behavior.pet_sleep_with_owner" -> goals.add(priority,
                PetRestGoal.besideSleepingOwner(this, goal));
            case "minecraft:behavior.stay_while_sitting" -> goals.add(priority,
                PetRestGoal.whileSitting(this));
            case "minecraft:behavior.find_mount" -> goals.add(priority,
                new FindMountGoal(this, goal));

            case "minecraft:behavior.roar" -> goals.add(priority,
                PoseHoldGoal.roaring(this, goal));
            case "minecraft:behavior.croak" -> goals.add(priority,
                PoseHoldGoal.croaking(this, goal));
            case "minecraft:behavior.sniff" -> goals.add(priority,
                PoseHoldGoal.sniffing(this, goal));
            case "minecraft:behavior.emerge" -> goals.add(priority,
                PoseHoldGoal.emerging(this, goal));
            case "minecraft:behavior.dig" -> goals.add(priority,
                PoseHoldGoal.digging(this, goal));
            case "minecraft:behavior.random_search_and_dig" -> goals.add(priority,
                MoveToMatchingBlockGoal.searchingAndDigging(this, goal));

            // What a Bedrock dolphin does that nothing else does.
            case "minecraft:behavior.random_breach" -> goals.add(priority,
                DolphinGoal.breaching(this, goal));
            case "minecraft:behavior.find_underwater_treasure" -> goals.add(priority,
                DolphinGoal.seekingTreasure(this, goal));
            case "minecraft:behavior.swim_with_entity" -> goals.add(priority,
                DolphinGoal.escorting(this, goal));

            case "minecraft:behavior.play_dead" -> goals.add(priority,
                new PlayDeadGoal(this, goal));
            case "minecraft:behavior.scared" -> goals.add(priority,
                new ScaredGoal(this, goal));
            case "minecraft:behavior.celebrate" -> goals.add(priority,
                new CelebrateGoal(this, goal));
            case "minecraft:behavior.sneeze" -> goals.add(priority,
                new SneezeGoal(this, goal));
            case "minecraft:behavior.roll" -> goals.add(priority,
                new RollGoal(this, goal));
            case "minecraft:behavior.swim_idle" -> goals.add(priority,
                new SwimIdleGoal(this, goal));
            case "minecraft:behavior.restrict_open_door" -> goals.add(priority,
                new RestrictOpenDoorGoal(this));
            case "minecraft:behavior.rise_to_liquid_level" -> goals.add(priority,
                new RiseToLiquidLevelGoal(this, goal));
            case "minecraft:behavior.move_to_poi" -> goals.add(priority,
                new MoveToPoiGoal(this, goal));
            case "minecraft:behavior.jump_to_block" -> goals.add(priority,
                new JumpToBlockGoal(this, goal));
            case "minecraft:behavior.lay_egg" -> goals.add(priority,
                new LayEggGoal(this, goal));
            case "minecraft:behavior.eat_mob" -> goals.add(priority,
                new EatMobGoal(this, goal));
            case "minecraft:behavior.fire_at_target" -> goals.add(priority,
                new FireAtTargetGoal(this, goal));
            case "minecraft:behavior.charge_held_item" -> goals.add(priority,
                new ChargeHeldItemGoal(this, goal));
            case "minecraft:behavior.circle_around_anchor" -> goals.add(priority,
                new CircleAroundAnchorGoal(this, goal));
            case "minecraft:behavior.controlled_by_player" -> goals.add(priority,
                new ControlledByPlayerGoal(this, goal));

            case "minecraft:behavior.take_block" -> goals.add(priority,
                CarryBlockGoal.taking(this, goal));
            case "minecraft:behavior.place_block" -> goals.add(priority,
                CarryBlockGoal.placing(this, goal));
            case "minecraft:behavior.enderman_take_block" -> goals.add(priority,
                CarryBlockGoal.endermanTaking(this, goal));
            case "minecraft:behavior.enderman_leave_block" -> goals.add(priority,
                CarryBlockGoal.endermanLeaving(this, goal));

            case "minecraft:behavior.ram_attack" -> goals.add(priority,
                ChargeAttackGoal.ramming(this, goal));
            case "minecraft:behavior.swoop_attack" -> goals.add(priority,
                ChargeAttackGoal.swooping(this, goal));
            case "minecraft:behavior.aquatic_charge_attack" -> goals.add(priority,
                ChargeAttackGoal.chargingUnderwater(this, goal));

            case "minecraft:behavior.breed" -> goals.add(priority,
                new BreedGoal(this, goal));
            case "minecraft:behavior.trade_with_player" -> goals.add(priority,
                TradeGoal.whileTrading(this, goal));
            case "minecraft:behavior.trade_interest" -> goals.add(priority,
                TradeGoal.showingWares(this, goal));

            // A villager's working life: the shift at a station, the handing round of what was
            // gathered, and the pair of behaviours that make another villager.
            case "minecraft:behavior.work" -> goals.add(priority,
                WorkGoal.working(this, goal));
            case "minecraft:behavior.work_composter" -> goals.add(priority,
                WorkGoal.composting(this, goal));
            case "minecraft:behavior.share_items" -> goals.add(priority,
                ShareItemsGoal.sharing(this, goal));
            case "minecraft:behavior.transport_items" -> goals.add(priority,
                ShareItemsGoal.transporting(this, goal));
            case "minecraft:behavior.drop_item_for" -> goals.add(priority,
                ShareItemsGoal.dropping(this, goal));
            case "minecraft:behavior.offer_flower" -> goals.add(priority,
                ShareItemsGoal.offering(this, goal));
            case "minecraft:behavior.inspect_bookshelf" -> goals.add(priority,
                MoveToMatchingBlockGoal.bookshelf(this, goal));
            // Bedrock's villager courtship is its own pair of behaviours; what they come to is
            // the same walk towards a willing partner that every other pack animal does.
            case "minecraft:behavior.make_love", "minecraft:behavior.receive_love" ->
                goals.add(priority, new BreedGoal(this, goal));
            // Napping is lying down, and snacking is eating what is already in hand.
            case "minecraft:behavior.nap" -> goals.add(priority,
                RestGoal.lyingDown(this, goal));
            case "minecraft:behavior.snacking" -> goals.add(priority,
                new EatCarriedItemGoal(this, goal));

            case "minecraft:behavior.admire_item" -> goals.add(priority,
                BarterGoal.admiring(this, goal));
            case "minecraft:behavior.barter" -> goals.add(priority,
                BarterGoal.bartering(this, goal));

            // A wind-up held at range: a guardian's beam and a warden's boom.
            case "minecraft:behavior.guardian_attack" -> goals.add(priority,
                BeamAttackGoal.beam(this, goal));
            case "minecraft:behavior.sonic_boom" -> goals.add(priority,
                BeamAttackGoal.sonicBoom(this, goal));
            case "minecraft:behavior.investigate_suspicious_location" ->
                goals.add(priority, new InvestigateGoal(this, goal));

            case "minecraft:behavior.silverfish_merge_with_stone" ->
                goals.add(priority, SilverfishGoal.merging(this));
            case "minecraft:behavior.silverfish_wake_up_friends" ->
                goals.add(priority, SilverfishGoal.wakingFriends(this));

            case "minecraft:behavior.vex_random_move" ->
                goals.add(priority, new VexMoveGoal(this));
            case "minecraft:behavior.vex_copy_owner_target" -> targets.add(priority,
                OwnerTargetGoal.whatTheOwnerIsAfter(this, goal));

            case "minecraft:behavior.wither_random_attack_pos_goal" ->
                goals.add(priority,
                    WitherGoal.loosingShots(this, goal));
            case "minecraft:behavior.wither_target_highest_damage" ->
                targets.add(priority,
                    WitherGoal.answeringTheWorstAttacker(this, goal));

            case "minecraft:behavior.run_around_like_crazy" -> goals.add(priority,
                new BuckRiderGoal(this, goal));
            case "minecraft:behavior.ocelot_sit_on_block" -> goals.add(priority,
                MoveToMatchingBlockGoal.sitOnBlock(this, goal));
            case "minecraft:behavior.skeleton_horse_trap" -> goals.add(priority,
                new HorseTrapGoal(this, goal));
            case "minecraft:behavior.defend_village_target" -> targets.add(priority,
                new DefendVillageGoal(this, goal));
            case "minecraft:behavior.use_kinetic_weapon" -> goals.add(priority,
                new KineticWeaponGoal(this, goal));

            case "minecraft:behavior.stay_near_noteblock" -> goals.add(priority,
                NoteblockGoal.stayingNear(this, goal));
            case "minecraft:behavior.go_and_give_items_to_noteblock" ->
                goals.add(priority,
                    NoteblockGoal.deliveringItems(this, goal));

            // A dragon's flight and its three attacks, anchored on the place the entity is kept
            // near rather than on the podium Java writes each of these phases around.
            case "minecraft:behavior.dragonholdingpattern" ->
                goals.add(priority, DragonFlightGoal.circling(this));
            case "minecraft:behavior.dragontakeoff" ->
                goals.add(priority, DragonFlightGoal.takingOff(this));
            case "minecraft:behavior.dragonlanding" ->
                goals.add(priority, DragonFlightGoal.landing(this));
            case "minecraft:behavior.dragonscanning" ->
                goals.add(priority, DragonFlightGoal.scanning(this));
            case "minecraft:behavior.dragonchargeplayer" -> goals.add(priority,
                DragonAttackGoal.charging(this, goal));
            case "minecraft:behavior.dragonstrafeplayer" -> goals.add(priority,
                DragonAttackGoal.strafing(this, goal));
            case "minecraft:behavior.dragonflaming" -> goals.add(priority,
                DragonAttackGoal.flaming(this, goal));

            case "minecraft:behavior.send_event" -> goals.add(priority,
                SpellGoal.sendingEvents(this, goal));
            case "minecraft:behavior.summon_entity" -> goals.add(priority,
                SpellGoal.summoning(this, goal));

            // A behaviour this switch does not name may still be one the entity carries out on
            // its own clock rather than through a goal, so the register decides, not the switch.
            default -> {
                if (!AddonEntities.implemented(behaviour)) AddonEntities.unsupported(behaviour);
            }
        }
        return priority;
    }

    // A spawn rule describes natural spawning, so a spawner block and a spawn egg are left alone.
    // The reason a spawn carries was renamed on the way to 26; the question asked of it is the same.
    //? if >=1.21.2 {
    @Override
    public boolean checkSpawnRules(net.minecraft.world.level.LevelAccessor level,
        net.minecraft.world.entity.EntitySpawnReason reason) {
        return super.checkSpawnRules(level, reason)
            && (reason != net.minecraft.world.entity.EntitySpawnReason.NATURAL || spawnRuleAllows());
    }
    //?} else {
    /*@Override
    public boolean checkSpawnRules(net.minecraft.world.level.LevelAccessor level,
        net.minecraft.world.entity.MobSpawnType reason) {
        return super.checkSpawnRules(level, reason)
            && (reason != net.minecraft.world.entity.MobSpawnType.NATURAL || spawnRuleAllows());
    }
    *///?}

    /**
     * Whether the pack's own spawn rule allows the spot the game is trying.
     *
     * The conditions a rule lists are alternatives, so the first one that holds decides, and the
     * event and the persistence it asks for come from that one. The event waits for the first tick:
     * an entity the game then refuses for want of room never ticks, and nothing is written for it.
     */
    private boolean spawnRuleAllows() {
        java.util.List<SpawnRuleDefinition.Condition> conditions = AddonSpawns.conditionsOf(getType());
        for (SpawnRuleDefinition.Condition condition : conditions) {
            if (!condition.allows(level(), blockPosition(), getType())) continue;

            spawnRuleEvent = condition.spawnEvent();
            if (condition.persistent()) setPersistenceRequired();
            return true;
        }
        return conditions.isEmpty();
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

        // `minecraft:area_attack` is the whole of hurting whatever stands against the entity, so
        // declaring it is what makes the entity able to damage the mobs around it.
        if (declares("minecraft:area_attack")) setValue("can_damage_nearby_mobs", true);

        if (declares("minecraft:heartbeat")) {
            setValue("heartbeat_interval", number(definition, "minecraft:heartbeat", "interval", 1));
        }

        // `minecraft:walk_animation_speed` scales how fast a pack's own walk animation runs, which
        // its controllers read back off the entity's speed, so the number goes with the speed.
        if (declares("minecraft:walk_animation_speed")) {
            setValue("walk_animation_speed",
                number(definition, "minecraft:walk_animation_speed", "value", 1));
        }
        if (declares("minecraft:out_of_control")) setValue("out_of_control", true);
    }

    /**
     * Puts on whatever `minecraft:equipment` rolls, and sets how likely each piece is to drop.
     *
     * The component names a loot table rather than the items, so what the entity wears is decided
     * once as it comes into the world. Which slot a piece belongs in is Java's own answer, so a
     * table handing out a helmet and a sword dresses the entity in both.
     */
    private void wearWhatThePackRolled() {
        String table = liveText("minecraft:equipment", "table");
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
     * Turns the entity no further in a tick than its `minecraft:movement.*` component's `max_turn`.
     *
     * Bedrock defaults the turn to thirty degrees a tick for every way of moving it describes, where
     * Java's move controls swing a mob round by up to ninety, so a pack's entity that never states a
     * turn rate still turns at a third of Java's pace. A rider steering the entity turns it as fast
     * as they look round, which Bedrock leaves to the rider too.
     */
    @Override
    public void aiStep() {
        float facing = getYRot();
        super.aiStep();
        if (definition == null || isClientSide() || getControllingPassenger() instanceof Player) return;

        float limit = maxTurn();
        float turned = net.minecraft.util.Mth.wrapDegrees(getYRot() - facing);
        if (limit >= 0 && Math.abs(turned) > limit) setYRot(facing + Math.signum(turned) * limit);
    }

    /** Bedrock's turn rate for a movement component that states none, in degrees a tick. */
    private static final double DEFAULT_MAX_TURN = 30;

    /**
     * The `max_turn` of the `minecraft:movement.*` component the entity carries now, or below nought
     * where it carries none and Java's own move control decides.
     */
    private float maxTurn() {
        for (String component : java.util.List.of("minecraft:movement.basic", "minecraft:movement.generic",
            "minecraft:movement.amphibious", "minecraft:movement.sway", "minecraft:movement.jump",
            "minecraft:movement.skip", "minecraft:movement.dolphin", "minecraft:movement.fly",
            "minecraft:movement.glide", "minecraft:movement.hover")) {
            com.google.gson.JsonObject live = liveComponent(component);
            if (live != null) return (float) Goals.number(live, "max_turn", DEFAULT_MAX_TURN);
        }
        return -1;
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

        turnRiders();
        animateState();
        everyTick();
        if (spawnRuleEvent != null) {
            String event = spawnRuleEvent;
            spawnRuleEvent = null;
            EntityEvents.trigger(this, event, null, ServerHooks.state());
        }
        if ((tickCount + getId()) % Sensors.INTERVAL_TICKS == 0) {
            Sensors.tick(this, ServerHooks.state());
            rememberSomethingNearby();
            resupply();
            teleportAbout();
            rest();
            drink();
            leashChanged(isLeashed());
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
            beAngry();
            noticeKinAnger();
            reflectProjectiles();
            drawFromHopper();
            coolDownAnger();
            rollGenes();
            loseSleep();
            climbAboard();
            joinTheRaid();
            callARaid();
            listenForVibrations();
        }
    }

    /** The behaviour pack's own state machine, built the first tick an entity that has one runs. */
    private at.ioexception.mcaddon.server.ServerAnimator animator;

    /**
     * Runs the clips and controllers this entity's behaviour pack declares.
     *
     * These carry no bones. What a moment on one of their timelines holds is an event of the
     * entity's own, a command run as the entity, or a Molang variable the controllers read back.
     */
    private void animateState() {
        if (definition.script().isEmpty()) return;

        if (animator == null) {
            at.ioexception.mcaddon.bedrock.BedrockAssets clips =
                at.ioexception.mcaddon.registry.AddonEntities.clipsOf(getType());
            if (clips == null) return;
            animator = new at.ioexception.mcaddon.server.ServerAnimator(this, clips, definition.script());
        }
        animator.tick();
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
        if (rising) fire(liveText("minecraft:on_friendly_anger", "event"));
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
        if (!declaresNow("minecraft:item_hopper") || carried == null) return;
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

    /** A pack's first generation has no parents to take an allele from, so it rolls its own. */
    private void rollGenes() {
        inherit(null, null);
    }

    /**
     * Rolls what `minecraft:genetics` says this entity was born with, off the pair that bred it.
     *
     * The variant the two alleles land in names an event, which fires on the entity itself; that is
     * how a pack makes one goat in fifty a screaming one without a second entity type. Null parents
     * roll both ends fresh, and the roll happens once however the entity came to be.
     */
    void inherit(AddonEntity first, AddonEntity second) {
        if (genetics == null || rolledGenes) return;

        rolledGenes = true;
        genetics.rollInto(this, first, second, random).forEach(this::fire);
    }

    private boolean rolledGenes;

    /**
     * Sends the sky after an entity that has not slept, which is what `minecraft:insomnia` is for.
     *
     * Bedrock counts the days since the entity last rested and draws phantoms to it once the count
     * passes what the pack allows. `minecraft:behavior.sleep` is the only thing that puts the count
     * back, so an entity whose pack gave it no way to lie down keeps the company for good.
     */
    private void loseSleep() {
        if (!declares("minecraft:insomnia")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        long now = Platform.dayTime(level);
        if (lastRestedDay < 0) {
            lastRestedDay = now / 24000L;
            return;
        }

        long night = now % 24000L;
        double allowed = number(definition, "minecraft:insomnia", "days_until_insomnia", 3);
        if (night < 13000L || night >= 23000L) return;
        if (now / 24000L - lastRestedDay < allowed || random.nextInt(PHANTOM_CHANCE) != 0) return;

        // 26.2 moved the vanilla entity types out of the class that builds them.
        //? if >=26.2 {
        net.minecraft.world.entity.Entity phantom =
            Platform.createMob(net.minecraft.world.entity.EntityTypes.PHANTOM, level);
        //?} else {
        /*net.minecraft.world.entity.Entity phantom =
            Platform.createMob(net.minecraft.world.entity.EntityType.PHANTOM, level);
        *///?}
        if (phantom == null) return;

        Platform.placeAt(phantom, getX(), getY() + PHANTOM_HEIGHT + random.nextInt(PHANTOM_HEIGHT),
            getZ(), getYRot(), 0f);
        level.addFreshEntity(phantom);
    }

    /** The day the entity last lay down, counted the way the world counts its own. */
    private long lastRestedDay = -1;

    /** One chance in this many, per sensor tick of a sleepless night, of another phantom. */
    private static final int PHANTOM_CHANCE = 400;

    /** How far above a sleepless entity a phantom comes in, which is where Java starts its own. */
    private static final int PHANTOM_HEIGHT = 20;

    /**
     * Climbs onto whatever is walking past, which is what `minecraft:wants_jockey` asks for.
     *
     * Bedrock puts the entity on the first mob near it that will carry one, which is how a pack
     * describes something that rides rather than walks. A mob of the entity's own kind is left
     * alone, or a crowd would spend its time riding itself.
     */
    private void climbAboard() {
        if (!declares("minecraft:wants_jockey") || isPassenger()) return;

        for (net.minecraft.world.entity.Mob mount : level().getEntitiesOfClass(
            net.minecraft.world.entity.Mob.class, getBoundingBox().inflate(JOCKEY_RANGE))) {
            if (mount == this || mount.getType() == getType()) continue;
            if (!mount.getPassengers().isEmpty()) continue;
            if (startRiding(mount)) return;
        }
    }

    /** How far a jockey looks for something to ride. */
    private static final double JOCKEY_RANGE = 4;

    /**
     * Walks towards a raid being fought nearby, which is as far as joining one reaches.
     *
     * Java counts a raider by its class rather than by what a pack declared, so a pack's entity is
     * never one of the waves the game itself is counting down. What it can do is turn up, which is
     * what a player sees of `minecraft:can_join_raid`.
     */
    private void joinTheRaid() {
        if (!declares("minecraft:can_join_raid") || getTarget() != null) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        net.minecraft.core.BlockPos centre = Platform.raidCentre(level, blockPosition());
        if (centre == null || centre.distSqr(blockPosition()) < RAID_ARRIVED * RAID_ARRIVED) return;

        getNavigation().moveTo(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5, 1);
    }

    /** How close to the middle of a raid counts as having got there. */
    private static final double RAID_ARRIVED = 8;

    /**
     * Starts the raid `minecraft:raid_trigger` names, through the omen Java raises one from.
     *
     * Java begins a raid when a player carrying bad omen reaches a village rather than on anybody
     * else's say-so, so the nearest player is given the omen and the pack's own event fires beside
     * it. The component is a pack saying this entity brings a raid with it.
     */
    private void callARaid() {
        if (!declares("minecraft:raid_trigger")) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel level)) return;

        Player nearest = level.getNearestPlayer(this, RAID_TRIGGER_RANGE);
        if (nearest == null || Platform.hasEffect(nearest, BAD_OMEN)) return;

        Platform.addEffect(nearest, BAD_OMEN, BAD_OMEN_TICKS, 0, true);
        fire(Goals.event(componentBody("minecraft:raid_trigger"), "triggered_event"));
    }

    /** How far the entity reaches for somebody to hand the omen to. */
    private static final double RAID_TRIGGER_RANGE = 16;

    /** The omen a raid follows, and how long Java's own bottle of it lasts. */
    private static final Identifier BAD_OMEN = Platform.id("minecraft", "bad_omen");

    private static final int BAD_OMEN_TICKS = 6000;

    /**
     * Notices something moving nearby, which is what a listener hears as a vibration.
     *
     * Java raises vibrations through a listener registered on the level, which belongs to the two
     * mobs written to carry one. What any entity can hear is the thing those vibrations come from:
     * another entity moving within earshot. `minecraft:suspect_tracking` keeps where it came from,
     * which the behaviour that investigates a suspicious place then walks to, and
     * `minecraft:vibration_listener` publishes how long ago it was for the pack to draw.
     */
    private void listenForVibrations() {
        boolean listens = declares("minecraft:vibration_listener");
        boolean remembers = declares("minecraft:suspect_tracking");
        if (!listens && !remembers) return;

        net.minecraft.world.entity.LivingEntity heard = level().getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class,
                getBoundingBox().inflate(VIBRATION_RANGE)).stream()
            .filter(other -> other != this)
            .filter(other -> other.getDeltaMovement().horizontalDistanceSqr() > VIBRATION_SPEED)
            .min(java.util.Comparator.comparingDouble(this::distanceToSqr))
            .orElse(null);
        if (heard != null) {
            heardAt = tickCount;
            if (remembers) disturbance = heard.blockPosition();
        }
        if (!listens || heardAt < 0) return;

        double since = (tickCount - heardAt) / (double) Goals.TICKS_PER_SECOND;
        if (since <= VIBRATION_MEMORY) setValue("time_since_last_vibration_detection", since);
    }

    /** What a pack's memory sensors wrote, or null for an entity that declared none of them. */
    public Memories memories() {
        return memories;
    }

    /**
     * `minecraft:memory_sensor.in_range_of_entity` writes down the nearest entity it can see.
     *
     * Mojang publishes the name and no fields, so how far it looks is the entity's own follow
     * range, which is the distance every other component on a Bedrock entity measures sight by.
     */
    private void rememberSomethingNearby() {
        if (memories == null || !declares("minecraft:memory_sensor.in_range_of_entity")) return;

        double range = getAttributeValue(Attributes.FOLLOW_RANGE);
        level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                getBoundingBox().inflate(range)).stream()
            .filter(other -> other != this)
            .min(java.util.Comparator.comparingDouble(this::distanceToSqr))
            .ifPresent(seen -> memories.remember(seen, tickCount));
    }

    /**
     * Offers one game event to every entity near enough to remember where it happened.
     *
     * Called for every event anywhere in a world, so the first thing asked is whether any installed
     * pack declared the sensor at all; with none the whole feed costs a set lookup.
     */
    public static void rememberGameEvent(net.minecraft.server.level.ServerLevel level,
        net.minecraft.world.phys.Vec3 where) {
        if (!AddonEntities.anyoneDeclares("minecraft:memory_sensor.game_event")) return;

        net.minecraft.world.phys.AABB earshot = net.minecraft.world.phys.AABB.ofSize(where,
            GAME_EVENT_EARSHOT, GAME_EVENT_EARSHOT, GAME_EVENT_EARSHOT);
        for (AddonEntity heard : level.getEntitiesOfClass(AddonEntity.class, earshot)) {
            if (heard.memories != null && heard.declares("minecraft:memory_sensor.game_event")) {
                heard.memories.remember(net.minecraft.core.BlockPos.containing(where),
                    heard.tickCount);
            }
        }
    }

    /** How far a game event carries, which is the range Java gives its own sculk sensor. */
    private static final double GAME_EVENT_EARSHOT = 16;

    /** The entity's own age at which it last heard something, or negative while it has heard nothing. */
    private int heardAt = -1;

    /** How far a listener hears, which is the range Java gives its own sculk sensor. */
    private static final double VIBRATION_RANGE = 8;

    /** How fast something has to be going along the ground before it is worth hearing. */
    private static final double VIBRATION_SPEED = 0.003;

    /** Seconds after which a listener stops telling the drawing side about the last thing it heard. */
    private static final double VIBRATION_MEMORY = 10;

    /**
     * The components that have to be answered on the entity's own clock rather than the sensors'.
     *
     * Growing up, floating and how the body is held all read as a stutter when they are decided
     * every tenth tick, so these are the few that pay for being asked every one.
     */
    private void everyTick() {
        holdBreath();
        burnFuse();
        keepTransforming();
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
        slideTheOldWay();
        floatUpwards();
        jumpForRider();
        dash();
        peek();
        wearOffBribe();
        beatTheAir();
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
        if (rails != null) rails.tick();
        if (flight != null) flight.tick();
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

        int interval = (int) (number(definition, "minecraft:heartbeat", "interval", 1)
            * Goals.TICKS_PER_SECOND);
        if (interval <= 0 || tickCount % interval != 0) return;

        playSound(sound(liveText("minecraft:heartbeat", "sound_event"), SoundEvents.WARDEN_HEARTBEAT),
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

        String inside = AddonBlocks.identifierOf(level().getBlockState(blockPosition()));
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

        playSound(sound(liveText("minecraft:celebrate_hunt", "celebrate_sound"),
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

        String name = AddonBlocks.identifierOf(state);
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
                .anyMatch(listedName -> AddonBlocks.identifierOf(listedName).equals(name));
            if (listed) fire(Goals.event(entry, "on_block_broken"));
        }
    }

    /**
     * A block descriptor names itself either bare or under `name`, in both shapes a pack writes,
     * and is answered under the id a block in the world reports itself by.
     */
    private static String blockNameOf(com.google.gson.JsonObject entry) {
        com.google.gson.JsonElement block = entry.get("block");
        String named = block == null ? null
            : block.isJsonPrimitive() ? block.getAsString()
            : block.isJsonObject()
                ? at.ioexception.mcaddon.util.Json.text(block.getAsJsonObject(), "name", null) : null;
        return named == null ? null : AddonBlocks.identifierOf(named);
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
        if (caught) fire(liveText("minecraft:on_ignite", "event"));
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
        fire(liveText(grounded ? "minecraft:on_start_landing" : "minecraft:on_start_takeoff", "event"));
    }

    /**
     * Moves the entity at the speed the pack said its medium allows.
     *
     * Bedrock states a speed per medium as its own component and Java has one movement speed, so the
     * attribute is moved as the entity enters and leaves, and put back on land.
     */
    private void swimAtItsOwnSpeed() {
        if (!declaresNow("minecraft:lava_movement") && !declaresNow("minecraft:underwater_movement")
            && !declaresNow("minecraft:water_movement")) {
            return;
        }

        String component = null;
        if (isInLava() && declaresNow("minecraft:lava_movement")) {
            component = "minecraft:lava_movement";
        } else if (isInWater() && declaresNow("minecraft:underwater_movement")) {
            component = "minecraft:underwater_movement";
        } else if (isInWater() && declaresNow("minecraft:water_movement")) {
            component = "minecraft:water_movement";
        }

        net.minecraft.world.entity.ai.attributes.AttributeInstance speed =
            getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;

        double wanted = component == null ? definition.movementSpeed()
            : liveNumber(component, "value", definition.movementSpeed());
        if (speed.getBaseValue() != wanted) speed.setBaseValue(wanted);
    }

    /**
     * `minecraft:air_drag_modifier` is how quickly the entity loses the speed it left the ground with.
     *
     * `minecraft:uses_uniform_air_drag` asks for that same drag on the rise and the fall. Java drags
     * a mob along the ground plane and leaves the vertical to gravity alone, so the share it leaves
     * a mob going sideways is applied upwards too.
     */
    private void dragThroughAir() {
        boolean uniform = declaresNow("minecraft:uses_uniform_air_drag");
        if (!declaresNow("minecraft:air_drag_modifier") && !uniform) return;
        if (onGround()) return;

        double kept = liveNumber("minecraft:air_drag_modifier", "value", 1);
        setDeltaMovement(getDeltaMovement().multiply(kept, uniform ? kept * AIR_DRAG : 1, kept));
    }

    /** The share of its speed Java leaves a mob moving through air along the ground plane. */
    private static final double AIR_DRAG = 0.91;

    /**
     * `minecraft:friction_modifier` is how much of the ground's grip this entity feels.
     *
     * Java takes friction from the block rather than from whatever is standing on it, so what the
     * modifier changes here is the speed the entity keeps between two ticks: above one it stops
     * sooner than the ground alone would stop it, and below one it slides further.
     */
    private void rubAlongTheGround() {
        if (!declaresNow("minecraft:friction_modifier") || !onGround()) return;

        double modifier = liveNumber("minecraft:friction_modifier", "value", 1);
        if (modifier == 1 || modifier < 0) return;

        double kept = Math.max(0, Math.min(1, 1 - (modifier - 1) * FRICTION_PER_STEP));
        setDeltaMovement(getDeltaMovement().multiply(kept, 1, kept));
    }

    /** How much of its speed an entity loses per tick for each point of friction above one. */
    private static final double FRICTION_PER_STEP = 0.2;

    /**
     * `minecraft:uses_legacy_friction` takes the ground's own grip out of how the entity slows down.
     *
     * Java slows a mob by the friction of the block under it, so ice carries it further than grass
     * does. An entity on Bedrock's older rules feels the same friction wherever it stands, which is
     * put back by correcting each step by the difference between the two.
     */
    private void slideTheOldWay() {
        if (!declares("minecraft:uses_legacy_friction") || !onGround()) return;

        float ground = level().getBlockState(blockPosition().below()).getBlock().getFriction();
        if (ground <= 0 || ground == LEGACY_FRICTION) return;

        double corrected = LEGACY_FRICTION / ground;
        setDeltaMovement(getDeltaMovement().multiply(corrected, 1, corrected));
    }

    /** The one friction every block had before a block carried its own, which grass still has. */
    private static final float LEGACY_FRICTION = 0.6f;

    /**
     * Rises the way `minecraft:balloon` describes, up to the height the pack allows.
     *
     * The component describes the balloon rather than what is tied to it, so what it carries is a
     * mass and a ceiling. Java has gravity and no balloon, so the mass decides how hard the lift
     * pulls against it and the ceiling is where the entity stops climbing.
     */
    private void floatUpwards() {
        if (!declares("minecraft:balloon")) return;

        double ceiling = number(definition, "minecraft:balloon", "max_height", 0);
        if (ceiling > 0 && getY() >= ceiling) {
            setDeltaMovement(getDeltaMovement().x, 0, getDeltaMovement().z);
            return;
        }

        double mass = Math.max(0.01, number(definition, "minecraft:balloon", "mass", 1));
        setDeltaMovement(getDeltaMovement().add(0, BALLOON_LIFT / mass, 0));
        fallDistance = 0;
    }

    /** How hard a balloon of mass one pulls upwards in one tick. */
    private static final double BALLOON_LIFT = 0.05;

    /**
     * Leaves the ground when the rider of a `minecraft:can_power_jump` mount asks for it.
     *
     * Java lets a player jump a horse and nothing else, and reads the mount's jump strength for how
     * high. A pack's mount is neither a horse nor something a player may jump, so the rider's own
     * jump is read and the mount jumps for them.
     */
    private void jumpForRider() {
        if (!declaresNow("minecraft:can_power_jump") || !onGround()) return;
        if (!(getControllingPassenger() instanceof Player rider)) return;
        if (!((at.ioexception.mcaddon.mixin.JumpingAccessor) rider).mcaddon$jumping()) return;

        jumpFromGround();
    }

    /**
     * How high a jump carries, which `minecraft:can_power_jump` takes off the jump strength.
     *
     * Java gives an ordinary mob a fixed jump and reads the attribute for a horse alone. A pack
     * saying its mount can power jump is describing the second, so the attribute decides.
     */
    @Override
    protected float getJumpPower() {
        if (definition == null) return super.getJumpPower();
        if (declaresNow("minecraft:can_power_jump")) return (float) getAttributeValue(Attributes.JUMP_STRENGTH);

        // `jump_power` is the jump's starting speed in blocks a tick, the 0.42 Java jumps every mob
        // at unless the pack says otherwise.
        com.google.gson.JsonObject jump = liveComponent("minecraft:jump.static");
        if (jump == null || !jump.has("jump_power")) return super.getJumpPower();
        return (float) Goals.number(jump, "jump_power", JAVA_JUMP) * getBlockJumpFactor() + getJumpBoostPower();
    }

    /** The speed in blocks a tick Java starts every ordinary mob's jump at. */
    private static final double JAVA_JUMP = 0.42;

    /**
     * The lunge `minecraft:dash_action` gives a mount, on the tick its rider asks for one.
     *
     * Bedrock states the wait between two dashes in seconds, the forward momentum per second and the
     * upward one per tick. How far through that wait the entity is is what a pack's own controllers
     * draw the recovery from, so it is published while it runs down rather than only at its ends.
     */
    private void dash() {
        if (!declaresNow("minecraft:dash_action")) return;

        int wait = (int) (liveNumber("minecraft:dash_action", "cooldown_time", 1)
            * Goals.TICKS_PER_SECOND);
        if (dashReadyAt > tickCount) {
            if (tickCount % Goals.TICKS_PER_SECOND == 0 && wait > 0) {
                setValue("dash_cooldown_progress", (double) (wait - (dashReadyAt - tickCount)) / wait);
            }
            return;
        }
        if (!Boolean.TRUE.equals(value("can_dash"))) {
            setValue("can_dash", true);
            setValue("dash_cooldown_progress", 1d);
        }
        if (!onGround() || !(getControllingPassenger() instanceof Player rider)) return;
        if (!((at.ioexception.mcaddon.mixin.JumpingAccessor) rider).mcaddon$jumping()) return;

        double forward = liveNumber("minecraft:dash_action", "horizontal_momentum", 20)
            / Goals.TICKS_PER_SECOND;
        double facing = Math.toRadians(getYRot());
        setDeltaMovement(getDeltaMovement().add(-Math.sin(facing) * forward,
            liveNumber("minecraft:dash_action", "vertical_momentum", 0.6),
            Math.cos(facing) * forward));
        dashReadyAt = tickCount + wait;
        setValue("can_dash", false);
    }

    /** The entity's own age at which it may dash again, which a spent dash pushes forward. */
    private int dashReadyAt;

    /**
     * Opens and closes the way a shell does, which is what `minecraft:peek` describes.
     *
     * Bedrock names an event for closing and two for opening, the second of which is the entity
     * opening because something worth watching turned up. How far open it is drawn is what a pack's
     * own controllers read back, so the share is published as it changes.
     */
    private void peek() {
        if (!declares("minecraft:peek")) return;

        boolean open = getTarget() != null;
        if (open == peeking) return;

        peeking = open;
        setValue("peek_amount", open ? 1d : 0d);
        com.google.gson.JsonObject body = componentBody("minecraft:peek");
        String opening = Goals.event(body, "on_target_open");
        fire(open ? (opening == null ? Goals.event(body, "on_open") : opening)
            : Goals.event(body, "on_close"));
    }

    private boolean peeking;

    /** A pack's controllers read a paid bribe back as `q.is_bribed`, for as long as it lasts. */
    private void wearOffBribe() {
        if (!declares("minecraft:bribeable")) return;

        boolean bribed = bribedUntilTick > tickCount;
        if (bribed != Boolean.TRUE.equals(value("is_bribed"))) setValue("is_bribed", bribed);
    }

    /**
     * Beats a wing loudly enough to be heard, which `emit_flap` is the pack asking for.
     *
     * Java raises this event from the few mobs written to fly rather than from anything that leaves
     * the ground, so an entity whose pack asked for it raises its own while it is climbing.
     */
    private void beatTheAir() {
        if (!declares("minecraft:game_event_movement_tracking") || onGround()) return;
        if (tickCount % FLAP_INTERVAL != 0 || getDeltaMovement().y <= 0) return;
        if (!Goals.flag(componentBody("minecraft:game_event_movement_tracking"), "emit_flap", false)) {
            return;
        }

        gameEvent(net.minecraft.world.level.gameevent.GameEvent.FLAP);
    }

    /** Ticks between two wing beats loud enough to raise an event. */
    private static final int FLAP_INTERVAL = 10;

    /**
     * Leaves the ground towards wherever the entity is being moved.
     *
     * `minecraft:jump.dynamic` keeps two sets of jump numbers and picks between them by the speed
     * the entity is being moved at, which is what makes something skipping along clear more ground
     * per hop than one ambling. Bedrock's own default height is a tenth of a block, so a pack's
     * number is read against that and a hop is scaled by what it asked for.
     */
    void hop(double speed) {
        com.google.gson.JsonObject dynamic = declares("minecraft:jump.dynamic")
            ? componentBody("minecraft:jump.dynamic") : null;
        if (dynamic == null) {
            Goals.hop(this, speed);
            return;
        }

        com.google.gson.JsonObject data = dynamic.getAsJsonObject(
            speed > 1 ? "fast_skip_data" : "regular_skip_data");
        Goals.hop(this, speed * Goals.number(data, "distance_scale", 1),
            Goals.HOP_HEIGHT * Goals.number(data, "height", BEDROCK_HOP_HEIGHT) / BEDROCK_HOP_HEIGHT);
    }

    /** The height Bedrock gives a skip that says nothing about its own, which a pack scales from. */
    private static final double BEDROCK_HOP_HEIGHT = 0.1;

    /** `minecraft:rotation_axis_aligned` keeps the entity square to the world rather than turning freely. */
    private void faceTheGrid() {
        if (!declaresNow("minecraft:rotation_axis_aligned")) return;

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
     * middle of whatever it can see. Both numbers default to nothing, so a component with an empty
     * body schools no one, which is what a pack writing one means.
     *
     * `innner_cohesion_threshold` carries the third `n` every published pack writes it with.
     */
    private void flock() {
        if (!declares("minecraft:flocking")) return;

        double influence = number(definition, "minecraft:flocking", "influence_radius", 0);
        if (influence <= 0) return;

        double innerRadius =
            number(definition, "minecraft:flocking", "innner_cohesion_threshold", 0);
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

        double pull = number(definition, "minecraft:flocking", "goal_weight", 0) * FLOCK_PULL;
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

        boolean boosted = boostedUntilTick > tickCount;
        if (boosted != boosting) {
            boosting = boosted;
            // The speed itself rather than the movement it produced: scaling a velocity that the
            // next tick builds on again makes a mount that never stops getting faster.
            Platform.scaleSpeed(this, boosted
                ? number(definition, "minecraft:boostable", "speed_multiplier", 1.35) : 1);
        }
        if (boosted) return;
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
        if (definition != null && !raisesMovement(Platform.movementEvent(event))) return;

        super.gameEvent(event, cause);
    }
    //?} else {
    /*@Override
    public void gameEvent(net.minecraft.world.level.gameevent.GameEvent event,
        net.minecraft.world.entity.Entity cause) {
        if (definition != null && declares("minecraft:vibration_damper")) return;
        if (definition != null && !raisesMovement(Platform.movementEvent(event))) return;

        super.gameEvent(event, cause);
    }
    *///?}

    /**
     * Whether `minecraft:game_event_movement_tracking` lets this movement be heard.
     *
     * The component is three switches, each naming one of the events an entity raises as it moves,
     * and a pack turning one off is asking for that movement to make no sound anything listens for.
     * Every other event, and every entity whose pack declared no tracking, passes through.
     */
    private boolean raisesMovement(String switched) {
        if (switched == null || !declares("minecraft:game_event_movement_tracking")) return true;

        return Goals.flag(componentBody("minecraft:game_event_movement_tracking"), switched, true);
    }

    /**
     * How far the entity walks between two step sounds.
     *
     * `minecraft:movement_sound_distance_offset` is what a pack gives something with more legs than
     * Java expects, or a longer stride, and Java counts a whole block per step.
     */
    @Override
    protected float nextStep() {
        if (definition == null || !declares("minecraft:movement_sound_distance_offset")) {
            return super.nextStep();
        }

        return moveDist + (float) number(definition,
            "minecraft:movement_sound_distance_offset", "value", 1);
    }

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
        String named = liveText("minecraft:addrider", "entity_type");
        net.minecraft.world.entity.EntityType<?> type = named == null
            ? null : AddonEntities.typeOf(named);
        net.minecraft.world.entity.Entity rider = type == null ? null : Platform.createMob(type, level);
        if (rider == null) return;

        Platform.placeAt(rider, getX(), getY(), getZ(), getYRot(), 0f);
        level.addFreshEntity(rider);
        rider.startRiding(this);

        String event = liveText("minecraft:addrider", "spawn_event");
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

        setValue("is_baby", false);
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

        String id = AddonItems.identifierOf(held.getItem());
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

        String id = AddonItems.identifierOf(held.getItem());
        for (String component
            : new String[] {"minecraft:tameable", "minecraft:tamemount", "minecraft:trusting"}) {
            if (!declaresNow(component) || !foodOf(component, id)) continue;

            if (!player.getAbilities().instabuild) held.shrink(1);
            if (random.nextDouble() > liveNumber(component, "probability", 0.33)) return true;

            boolean trusting = component.endsWith("trusting");
            if (!trusting && at.ioexception.mcaddon.server.WorldEvents.tamingRefused(this, player)) {
                return true;
            }
            ServerHooks.state().setProperty(getUUID(), "is_tamed", true);
            setOwner(player);
            fire(namedEvent(component, trusting ? "trust_event" : "tame_event"));
            if (!trusting) at.ioexception.mcaddon.server.WorldEvents.tamed(this, player);
            return true;
        }
        return false;
    }

    /** Whether the item offered is one of the foods a taming or trusting component named. */
    private boolean foodOf(String component, String offered) {
        com.google.gson.JsonObject body = liveComponent(component);
        if (body == null) return false;

        for (String key : new String[] {"tame_items", "feed_items", "trust_items", "items"}) {
            for (com.google.gson.JsonElement element
                : at.ioexception.mcaddon.util.Json.list(body, key)) {
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
        com.google.gson.JsonObject body = liveComponent(component);
        com.google.gson.JsonObject wrapper = body == null ? null : body.getAsJsonObject(key);
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

        String id = AddonItems.identifierOf(held.getItem());
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
            equipmentSlot(liveText("minecraft:entity_armor_equipment_slot_mapping", "slot"));
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

        String id = AddonItems.identifierOf(held.getItem());
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
        if (!declaresNow("minecraft:sittable")
            || !player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
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
    private static boolean names(String bedrock, String identifier) {
        return bedrock != null && AddonItems.identifierOf(bedrock).equals(identifier);
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
        if (declaresNow("minecraft:body_rotation_blocked")) {
            setYBodyRot(yBodyRotO);
        } else if (declaresNow("minecraft:body_rotation_always_follows_head")) {
            setYBodyRot(getYHeadRot());
        }
    }

    /** Throws the entity back up off the ground by as much as `minecraft:bounciness` asks. */
    private void bounce() {
        if (!declaresNow("minecraft:bounciness") || !onGround()) return;

        double fell = -getDeltaMovement().y;
        if (fell <= 0.1) return;

        double kept = liveNumber("minecraft:bounciness", "bounciness_value", 0.9);
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
        if (Goals.flag(liveComponent("minecraft:angry"), "broadcast_anger_on_being_attacked", false)) {
            broadcastAnger(getLastHurtByMob());
        }
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
        String event = liveText(component, "event");
        if (event != null) EntityEvents.trigger(this, event, other, ServerHooks.state());
    }

    /**
     * `minecraft:behavior.dragondeath` is the long death the ender dragon dies.
     *
     * A goal gets no tick at zero health, and the dragon's own death is not a goal either:
     * `EnderDragon` overrides `LivingEntity.tickDeath`, which every living entity carries. An
     * entity a pack gave this behaviour rises and spins for the dragon's two hundred ticks,
     * throwing the same beams, instead of falling over in twenty.
     */
    @Override
    protected void tickDeath() {
        if (definition == null || !declaresNow("minecraft:behavior.dragondeath")) {
            super.tickDeath();
            return;
        }

        deathTime++;
        setYRot(getYRot() + DRAGON_DEATH_SPIN);
        setPos(getX(), getY() + DRAGON_DEATH_RISE, getZ());
        if (level() instanceof net.minecraft.server.level.ServerLevel level
            && deathTime % DRAGON_DEATH_BEAM_INTERVAL == 0) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER,
                getRandomX(1), getRandomY(), getRandomZ(1), 1, 0, 0, 0, 0);
        }
        if (deathTime >= DRAGON_DEATH_TICKS) {
            remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        }
    }

    /** How long the ender dragon takes to die, which is ten times what an ordinary mob takes. */
    private static final int DRAGON_DEATH_TICKS = 200;

    private static final int DRAGON_DEATH_BEAM_INTERVAL = 5;

    private static final float DRAGON_DEATH_SPIN = 20f;

    private static final double DRAGON_DEATH_RISE = 0.03;

    /**
     * What a pack asked for as the entity dies: its own event, what it leaves behind, and the bang.
     *
     * `minecraft:spawn_on_death` and `minecraft:explode` both happen here because both are a thing
     * Bedrock does at the moment of death rather than a behaviour the entity carries out.
     */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        // What the kill is worth is settled before the death itself, because dropping the
        // experience is part of what dying does and it reads this number on the way past.
        if (definition != null) xpReward = experienceReward(source.getEntity() instanceof Player);

        super.die(source);
        if (definition == null || !(level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        String event = liveText("minecraft:on_death", "event");
        if (event != null) EntityEvents.trigger(this, event, source.getEntity(), ServerHooks.state());
        // `broadcast_anger_when_dying` is yes unless the pack says otherwise, for a mob that spreads
        // its anger at all.
        if (spreadsAnger() && Goals.flag(liveComponent("minecraft:angry"), "broadcast_anger_when_dying", true)
            && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity killer) {
            broadcastAnger(killer);
        }

        // What a mob was hauling belongs on the ground once it is dead, the same as a chest's does,
        // unless `private` says the inventory goes with it.
        if (carried != null && !Goals.flag(liveComponent("minecraft:inventory"), "private", false)) {
            net.minecraft.world.Containers.dropContents(level, blockPosition(), carried);
        }
        spawnOnDeath(level);
        explode(level);
    }

    /** `minecraft:spawn_on_death` leaves another entity where this one fell. */
    private void spawnOnDeath(net.minecraft.server.level.ServerLevel level) {
        String named = liveText("minecraft:spawn_on_death", "id");
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
        if (!declaresNow("minecraft:explode")) return;

        boolean breaks = liveNumber("minecraft:explode", "breaks_blocks", 1) != 0;
        boolean burns = liveNumber("minecraft:explode", "causes_fire", 0) != 0;
        level.explode(this, getX(), getY(), getZ(),
            (float) liveNumber("minecraft:explode", "power", 3), burns,
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
            ? liveText("minecraft:on_target_acquired", "event")
            : liveText("minecraft:on_target_escape", "event");
        if (event != null) {
            EntityEvents.trigger(this, event, target == null ? had : target, ServerHooks.state());
        }
    }

    /**
     * How big the entity is drawn and how big a box it collides in.
     *
     * Two components multiply here: `minecraft:scale` sizes the whole entity, and
     * `minecraft:scale_by_age` moves it between two sizes while the entity grows up. Java's own
     * halving of anything young is left out, because a Bedrock pack sizes its young through the
     * `minecraft:scale` it writes beside `minecraft:is_baby`.
     *
     * 1.20.1 has one scale for the whole entity; from 1.21 the part that grows with age is asked
     * for on its own, and the whole scale is final from 26.
     */
    //? if >=1.21 {
    @Override
    public float getAgeScale() {
        return declaredScale() * (age == null ? 1f : age.scale());
    }
    //?} else {
    /*@Override
    public float getScale() {
        return declaredScale() * (age == null ? 1f : age.scale());
    }
    *///?}

    /**
     * How big the entity is drawn, against the size its geometry was written at.
     *
     * Bedrock documents `minecraft:scale` as the visual size, so the top level scale the type was
     * built with counts here as well as whatever a group or growing up adds over it.
     */
    public float visualScale() {
        float baked = definition == null ? 1f : definition.scale();
        //? if >=1.21 {
        return baked * getScale() * getAgeScale();
        //?} else {
        /*return baked * getScale();
        *///?}
    }

    /**
     * The box the entity collides in, which `minecraft:collision_box` states as a width and a height.
     *
     * A scale multiplies both axes together and this is the only place either can move on its own,
     * which is what a pack asks for when its entity changes shape rather than size: a shell closed is
     * square and open is tall. The entity type was built from whatever the top level said and cannot
     * move once the registries are frozen, so a box a component group brings reaches the entity here.
     *
     * The scale that grows with age is what Java applies over the type's own box, so an answer of
     * this mod's own has to apply it again over the pack's.
     */
    //? if >=1.21 {
    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(
        net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions declared = declaredBox();
        return declared == null ? super.getDefaultDimensions(pose) : declared.scale(getAgeScale());
    }
    //?} else {
    /*@Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(
        net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions declared = declaredBox();
        return declared == null || pose == net.minecraft.world.entity.Pose.SLEEPING
            ? super.getDimensions(pose) : declared.scale(getScale());
    }
    *///?}

    /**
     * The box the entity carries a `minecraft:collision_box` for, unscaled, or null while it has none.
     *
     * Null through the whole of the mob constructor, because the definition is assigned after it and
     * Java asks for the box while it runs. The type's own is the right answer there: it was built
     * from the same numbers.
     */
    private net.minecraft.world.entity.EntityDimensions declaredBox() {
        com.google.gson.JsonObject box = definition == null
            ? null : liveComponent("minecraft:collision_box");
        if (box == null) return null;

        return net.minecraft.world.entity.EntityDimensions.scalable(
            (float) number(box, "width", definition.width()) * definition.scale(),
            (float) number(box, "height", definition.height()) * definition.scale());
    }

    /**
     * `minecraft:custom_hit_test` widens what a melee swing and an arrow may land on.
     *
     * Bedrock lists boxes that take hits and leaves the collision box to
     * `minecraft:collision_box`. Java asks an entity the same question once,
     * `Entity.getPickRadius`, which `ProjectileUtil.getEntityHitResult` inflates the entity's box
     * by before the ray is tested, and that is the ray a player's reach and a fired arrow both use.
     * The answer is therefore how far past the collision box the widest declared hitbox reaches,
     * so a model hit where it looks hit still walks through a gap its collision box fits.
     */
    @Override
    public float getPickRadius() {
        if (definition == null || !declares("minecraft:custom_hit_test")) return super.getPickRadius();

        double widest = 0;
        for (com.google.gson.JsonElement element
            : at.ioexception.mcaddon.util.Json.list(componentBody("minecraft:custom_hit_test"),
                "hitboxes")) {
            if (!element.isJsonObject()) continue;

            com.google.gson.JsonObject box = element.getAsJsonObject();
            widest = Math.max(widest, at.ioexception.mcaddon.util.Json.number(box, "width", 0) / 2
                - getBbWidth() / 2);
        }
        return (float) Math.max(widest, super.getPickRadius());
    }

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
     * Bedrock counts the gap between hits in seconds, in fractions of one as readily as in whole
     * ones, and this runs on the sensor interval, so a gap shorter than that interval becomes it.
     */
    private void hurtOverTime() {
        if (!declaresNow("minecraft:damage_over_time")
            || !(level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        double seconds = liveNumber("minecraft:damage_over_time", "time_between_hurt", 0);
        int interval = Math.max(Sensors.INTERVAL_TICKS,
            (int) Math.round(seconds * Goals.TICKS_PER_SECOND));
        if (tickCount % interval >= Sensors.INTERVAL_TICKS) return;

        Platform.hurt(this, level, damageSources().magic(),
            (float) liveNumber("minecraft:damage_over_time", "damage_per_hurt", 1));
    }

    /** Bedrock states the gap between idle sounds in seconds; Java counts it in ticks. */
    @Override
    public int getAmbientSoundInterval() {
        com.google.gson.JsonObject interval = definition == null
            ? null : liveComponent("minecraft:ambient_sound_interval");
        if (interval == null) return super.getAmbientSoundInterval();

        // `value` is the shortest gap in seconds and `range` the most Bedrock adds to it at random,
        // and Java asks for the gap again each time the entity has spoken.
        double seconds = Goals.number(interval, "value", 8)
            + random.nextDouble() * Goals.number(interval, "range", 16);
        return (int) (seconds * at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits.TICKS_PER_SECOND);
    }

    @Override
    protected float getSoundVolume() {
        if (definition != null && declares("minecraft:sound_volume")) {
            return (float) number(definition, "minecraft:sound_volume", "value", 1);
        }
        at.ioexception.mcaddon.bedrock.EntitySounds noises = AddonEntities.soundsOf(getType());
        return noises == null || noises.volume() < 0
            ? super.getSoundVolume() : (float) noises.volume();
    }

    /** The moment `sounds.json` names for this entity, or null where it names none. */
    private net.minecraft.sounds.SoundEvent noise(String moment) {
        at.ioexception.mcaddon.bedrock.EntitySounds noises = AddonEntities.soundsOf(getType());
        return noises == null ? null : sound(noises.event(moment), null);
    }

    /** Public so a test can ask which idle sound the entity would make where it stands. */
    @Override
    public net.minecraft.sounds.SoundEvent getAmbientSound() {
        String chosen = chosenAmbientMoment();
        if (chosen != null) return noise(chosen);

        net.minecraft.sounds.SoundEvent named = noise(ambientMoment());
        if (named == null) named = noise("ambient");
        return named == null ? super.getAmbientSound() : named;
    }

    /**
     * The idle moment `minecraft:ambient_sound_interval` names, or null where it names none.
     *
     * Its `event_names` are tried in order and the first whose condition holds is the sound, with
     * `event_name` behind them. A moment the pack's `sounds.json` has no sound for is silence, which
     * is how Mojang's own entities write a group that keeps an entity quiet.
     */
    private String chosenAmbientMoment() {
        if (definition == null || declaresNow("minecraft:uses_legacy_ambient_sounds")) return null;

        com.google.gson.JsonObject interval = liveComponent("minecraft:ambient_sound_interval");
        if (interval == null) return null;
        for (com.google.gson.JsonElement entry : at.ioexception.mcaddon.util.Json.list(interval, "event_names")) {
            if (!entry.isJsonObject()) continue;

            com.google.gson.JsonObject named = entry.getAsJsonObject();
            String moment = Goals.text(named, "event_name");
            String condition = Goals.text(named, "condition");
            if (moment != null && (condition == null || holds(condition))) return moment;
        }
        return at.ioexception.mcaddon.util.Json.text(interval, "event_name", "ambient");
    }

    /**
     * Whether a Molang condition a component states holds for the entity now.
     *
     * It is answered with the queries the entity's own scripts get, which is what lets a pack write
     * the `query.is_baby` it writes in an animation controller into a component as well.
     */
    public boolean holds(String condition) {
        if (questions == null) {
            questions = new at.ioexception.mcaddon.bedrock.animation.AnimationPlayer(null, this);
            questions.environment().setQueries(new at.ioexception.mcaddon.bedrock.entity.EntityQueries(
                this, questions, at.ioexception.mcaddon.bedrock.entity.EntityQueries.Drawn.NONE,
                () -> ServerHooks.state() == null
                    ? java.util.Map.of() : ServerHooks.state().properties(getUUID()), false));
        }
        questions.environment().setLifeTime(
            tickCount / (float) at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits.TICKS_PER_SECOND);
        return at.ioexception.mcaddon.bedrock.molang.MolangParser.parse(condition)
            .evaluate(questions.environment()).truthy();
    }

    private at.ioexception.mcaddon.bedrock.animation.AnimationPlayer questions;

    /**
     * Which of its idle sounds the entity makes, which `minecraft:uses_legacy_ambient_sounds` picks
     * by where the entity is and how old.
     *
     * Mojang names four moments: in the air, with the head under water, young, and otherwise. A
     * swimmer is never on the ground, so the water is asked about before the air; the other order
     * would never play the water moment Mojang's own swimmers name in `sounds.json`. A moment the
     * pack names no sound for falls back to the plain one.
     */
    private String ambientMoment() {
        if (definition == null || !declaresNow("minecraft:uses_legacy_ambient_sounds")) {
            return "ambient";
        }
        if (isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) return "ambient.in.water";
        if (!onGround()) return "ambient.in.air";
        return isBaby() ? "ambient.baby" : "ambient";
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(net.minecraft.world.damagesource.DamageSource source) {
        net.minecraft.sounds.SoundEvent named = noise("hurt");
        return named == null ? super.getHurtSound(source) : named;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        net.minecraft.sounds.SoundEvent named = noise("death");
        return named == null ? super.getDeathSound() : named;
    }

    /**
     * The step this entity makes, which Bedrock lets a mob state and Java takes off the block.
     *
     * A mob naming no step of its own keeps the one the block it is walking on makes, which is what
     * every Java mob without a step sound does.
     */
    @Override
    protected void playStepSound(net.minecraft.core.BlockPos position,
        net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.sounds.SoundEvent named = noise("step");
        if (named == null) {
            super.playStepSound(position, state);
            return;
        }
        playSound(named, 0.15f, 1f);
    }

    /**
     * The pitch a sound of this entity is played at, drawn from the range `sounds.json` names.
     *
     * Java varies a mob's voice about a fixed point and Bedrock states the two ends of the range,
     * so a pack naming one gets a pitch from inside it and a pack naming none keeps Java's own.
     */
    @Override
    public float getVoicePitch() {
        at.ioexception.mcaddon.bedrock.EntitySounds noises = AddonEntities.soundsOf(getType());
        Float named = noises == null ? null : noises.voicePitch(random.nextDouble());
        return named == null ? super.getVoicePitch() : named;
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
        // `filters` on `minecraft:angry` names who the entity leaves alone while angry, which is
        // how a piglin in a rage still spares the other piglins.
        com.google.gson.JsonObject angry = definition == null ? null : liveComponent("minecraft:angry");
        if (angry != null && angry.has("filters") && !Goals.filter(angry, "filters")
            .matches(new Filter.Context(this, target, target, ServerHooks.state()))) {
            return false;
        }
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
        com.google.gson.JsonObject component = liveComponent("minecraft:mob_effect_immunity");
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
     * Whether fire and lava leave the entity alone, which `minecraft:fire_immune` states.
     *
     * Java asks the entity rather than its type every time something would burn it, so a group
     * bringing the component in makes the entity immune while it is on and lets it burn once it is
     * gone. Bedrock writes it inside a group for the entities that are only sometimes safe.
     */
    @Override
    public boolean fireImmune() {
        return definition != null && declaresNow("minecraft:fire_immune") || super.fireImmune();
    }

    /**
     * Whether powder snow freezes the entity, which `minecraft:freezing_immune` says it never does.
     *
     * Java asks the entity each tick it stands in powder snow, and answers from a tag for its own
     * mobs, so a group bringing the component in thaws the entity for as long as it is on.
     */
    @Override
    public boolean canFreeze() {
        return !(definition != null && declaresNow("minecraft:freezing_immune")) && super.canFreeze();
    }

    /**
     * The cold hurting five times over, which `minecraft:freezing_vulnerable` asks for.
     *
     * Java multiplies a freezing blow by five for the mobs its `freeze_hurts_extra_types` tag names,
     * the blaze, the magma cube and the strider, which are the three Mojang writes the component on.
     */
    //? if >=1.21.2 {
    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level,
        net.minecraft.world.damagesource.DamageSource source, float amount) {
        return super.hurtServer(level, source, coldScaled(source, amount));
    }
    //?} else {
    /*@Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return super.hurt(source, coldScaled(source, amount));
    }
    *///?}

    private float coldScaled(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean vulnerable = definition != null && declaresNow("minecraft:freezing_vulnerable")
            && source.is(net.minecraft.tags.DamageTypeTags.IS_FREEZING);
        return vulnerable ? amount * FREEZE_VULNERABILITY : amount;
    }

    /** What Java multiplies a freezing blow by for the mobs the cold hurts more. */
    private static final float FREEZE_VULNERABILITY = 5f;

    /** Whether powder snow bears the entity's weight, which `minecraft:can_stand_on_powder_snow` says. */
    public boolean standsOnPowderSnow() {
        return definition != null && declaresNow("minecraft:can_stand_on_powder_snow");
    }

    /**
     * Inside a block that holds back whatever is in it, unless the entity is immune to that block.
     *
     * Java slows an entity inside a cobweb, a sweet berry bush or powder snow through this one call,
     * and lets a spider through a cobweb by refusing it, which is what
     * `minecraft:block_movement_slowdown_immunity` asks for the blocks it lists.
     */
    @Override
    public void makeStuckInBlock(net.minecraft.world.level.block.state.BlockState state,
        net.minecraft.world.phys.Vec3 multiplier) {
        if (definition == null || !slowedNeverBy(state)) super.makeStuckInBlock(state, multiplier);
    }

    private boolean slowedNeverBy(net.minecraft.world.level.block.state.BlockState state) {
        com.google.gson.JsonObject immunity =
            liveComponent("minecraft:block_movement_slowdown_immunity");
        if (immunity == null) return false;

        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return at.ioexception.mcaddon.util.Json.list(immunity, "blocks").stream()
            .map(AddonEntity::slowingBlockName)
            .anyMatch(id::equals);
    }

    /** A block descriptor's name in Java's spelling, which turns Bedrock's `web` into the cobweb. */
    private static String slowingBlockName(com.google.gson.JsonElement descriptor) {
        String named = descriptor.isJsonPrimitive() ? descriptor.getAsString()
            : descriptor.isJsonObject()
                ? at.ioexception.mcaddon.util.Json.text(descriptor.getAsJsonObject(), "name", null)
                : null;
        return named == null ? null : at.ioexception.mcaddon.convert.BedrockBlockNames.java(named);
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
        if (declaresNow("minecraft:is_collidable")
            && liveNumber("minecraft:is_collidable", "value", 1) == 0) {
            return false;
        }
        return !declaresNow("minecraft:pushable")
            || liveNumber("minecraft:pushable", "is_pushable", 1) != 0;
    }

    /**
     * Pushes this entity and the one bumping into it apart, as the `minecraft:pushable_by_entity`
     * preset for the other entity says.
     *
     * Java pushes every pair apart at five hundredths of a block a tick, which is the strength
     * Bedrock's `default` mode starts from too; a preset scales that strength and each side's share
     * of it, keeps the pair apart only between its two distances, or turns the push off. `ball` is
     * a kick along the other entity's own movement, at a speed taken from that movement.
     */
    @Override
    public void push(net.minecraft.world.entity.Entity other) {
        com.google.gson.JsonObject preset = definition == null ? null : pushPreset(other);
        if (preset == null) {
            super.push(other);
            return;
        }
        String mode = at.ioexception.mcaddon.util.Json.text(preset, "push_mode", "default");
        if (mode.equals("none") || isPassengerOfSameVehicle(other) || other.noPhysics || noPhysics) return;

        double dx = other.getX() - getX();
        double dz = other.getZ() - getZ();
        double apart = Math.sqrt(dx * dx + dz * dz);
        if (apart < Goals.number(preset, "min_distance", 0.01)
            || apart > Goals.number(preset, "max_distance", Double.MAX_VALUE)) {
            return;
        }
        if (mode.equals("ball")) {
            kickedBy(other, preset);
            return;
        }

        double strength = Goals.number(preset, "strength_multiplier", 0.05) / Math.max(1, apart);
        double xa = dx / Math.max(apart, 0.01) * strength;
        double za = dz / Math.max(apart, 0.01) * strength;
        double self = Goals.number(preset, "push_scale_self", 1);
        double back = Goals.number(preset, "push_scale_other", 1);
        if (!isVehicle() && isPushable()) push(-xa * self, 0, -za * self);
        if (!other.isVehicle() && other.isPushable()) other.push(xa * back, 0, za * back);
    }

    /** `ball`: sent along the way the other entity was going, faster the faster it went. */
    private void kickedBy(net.minecraft.world.entity.Entity other, com.google.gson.JsonObject preset) {
        net.minecraft.world.phys.Vec3 going = other.getDeltaMovement().multiply(1, 0, 1);
        if (going.lengthSqr() < 1.0e-6) going = position().subtract(other.position()).multiply(1, 0, 1);
        if (going.lengthSqr() < 1.0e-6) return;

        double speed = Math.max(Goals.number(preset, "min_kick_speed", 0),
            Math.min(Goals.number(preset, "max_kick_speed", 0.5),
                other.getDeltaMovement().horizontalDistance() * Goals.number(preset, "kick_speed_scale", 2)))
            * Goals.number(preset, "push_scale_self", 1);
        net.minecraft.world.phys.Vec3 kick = going.normalize().scale(speed);
        double lift = onGround() ? speed * Goals.number(preset, "vertical_kick_multiplier", 0.3) : 0;
        push(kick.x, lift, kick.z);
        if (Goals.flag(preset, "play_sound", false) && tickCount >= nextPushSoundAt
            && speed >= Goals.number(preset, "play_sound_impulse_threshold", 0.2)) {
            makeNoise("pushed_by_player");
            nextPushSoundAt = tickCount + Goals.ticks(preset, "play_sound_cooldown_in_seconds", 0.2);
        }
    }

    private int nextPushSoundAt;

    /**
     * The first `minecraft:pushable_by_entity` preset whose `filters` hold with the other entity as
     * `other`, or null where the component states none and Java's own push applies.
     */
    private com.google.gson.JsonObject pushPreset(net.minecraft.world.entity.Entity other) {
        com.google.gson.JsonObject component = liveComponent("minecraft:pushable_by_entity");
        java.util.List<com.google.gson.JsonElement> presets =
            at.ioexception.mcaddon.util.Json.list(component, "presets");
        if (presets.isEmpty()) return null;

        Filter.Context context = new Filter.Context(this, other, other, ServerHooks.state());
        return presets.stream()
            .filter(com.google.gson.JsonElement::isJsonObject)
            .map(com.google.gson.JsonElement::getAsJsonObject)
            .filter(preset -> Goals.filter(preset, "filters").matches(context))
            .findFirst()
            .orElseGet(com.google.gson.JsonObject::new);
    }

    /**
     * Whether a name tag may name the entity, which needs `minecraft:nameable` and its
     * `allow_name_tag_renaming`, yes unless the pack says otherwise.
     */
    public boolean takesNameTags() {
        if (definition == null) return true;

        com.google.gson.JsonObject nameable = liveComponent("minecraft:nameable");
        return nameable != null && Goals.flag(nameable, "allow_name_tag_renaming", true);
    }

    /**
     * Raises the event `minecraft:nameable` names for the name the entity was just given.
     *
     * The first of `name_actions` whose `name_filter` is the name raises its `on_named`, and any
     * other name raises `default_trigger`, which is how Mojang's vindicator turns into Johnny and back.
     * `always_show` keeps the name over the entity's head whether or not anyone looks at it.
     */
    @Override
    public void setCustomName(net.minecraft.network.chat.Component name) {
        super.setCustomName(name);
        com.google.gson.JsonObject nameable = definition == null || isClientSide()
            ? null : liveComponent("minecraft:nameable");
        if (nameable == null || name == null) return;

        if (Goals.flag(nameable, "always_show", false)) setCustomNameVisible(true);
        String given = name.getString();
        String event = at.ioexception.mcaddon.util.Json.list(nameable, "name_actions").stream()
            .filter(com.google.gson.JsonElement::isJsonObject)
            .map(com.google.gson.JsonElement::getAsJsonObject)
            .filter(action -> at.ioexception.mcaddon.util.Json.list(action, "name_filter").stream()
                .anyMatch(filter -> filter.isJsonPrimitive() && filter.getAsString().equals(given)))
            .map(action -> Goals.event(action, "on_named"))
            .findFirst()
            .orElseGet(() -> Goals.event(nameable, "default_trigger"));
        fire(event);
    }

    /** `is_pushable_by_piston` is the half of `minecraft:pushable` a moving piston asks about. */
    @Override
    public net.minecraft.world.level.material.PushReaction getPistonPushReaction() {
        if (definition != null && declaresNow("minecraft:pushable")
            && liveNumber("minecraft:pushable", "is_pushable_by_piston", 1) == 0) {
            return net.minecraft.world.level.material.PushReaction.IGNORE;
        }
        return super.getPistonPushReaction();
    }

    /** True when one of the pack's interactions applied, which is what consumes the click. */
    private boolean interact(Player player) {
        com.google.gson.JsonObject component = liveComponent("minecraft:interact");
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

    /**
     * Whether the entity is young, which a pack's render controllers read as `query.is_baby`.
     *
     * Bedrock says it two ways. `minecraft:ageable` counts a wait and the entity is young until it
     * is over; `minecraft:is_baby` is the flag for one that is young and stays that way, and a pack
     * writing it is choosing the proportions its geometry was drawn for. The flag is read off the
     * definition rather than off any state, so the drawing side answers it the same way the server
     * does without being told.
     */
    @Override
    public boolean isBaby() {
        return declaresNow("minecraft:is_baby") || (age != null && age.baby());
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
        if (definition != null && declaresNow("minecraft:not_pickable_from_inside")
            && player.getVehicle() == this) {
            return InteractionResult.PASS;
        }
        // `crouching_skip_interact` is a rideable entity asking to be mounted rather than used when
        // the player is crouching, which is the opposite of what Java does for its own mounts.
        com.google.gson.JsonObject rideable = definition == null
            ? null : liveComponent("minecraft:rideable");
        if (rideable != null && player.isCrouching()
            && ServerEntityDefinition.flag(rideable, "crouching_skip_interact", true)) {
            return super.mobInteract(player, hand);
        }
        if (definition != null && declaresNow("minecraft:bucketable")) {
            java.util.Optional<InteractionResult> scooped = Platform.scoop(player, hand, this);
            if (scooped.isPresent()) return scooped.get();
        }
        if (!isClientSide() && bredBySpawnEgg(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && fed(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && bribed(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && healed(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && tamedBy(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && grownByHand(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && equippedBy(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && givenTo(player, hand)) return InteractionResult.SUCCESS;
        if (!isClientSide() && satDown(player)) return InteractionResult.SUCCESS;
        if (!isClientSide() && interact(player)) return InteractionResult.SUCCESS;

        if (trades() == null) return super.mobInteract(player, hand);
        // A trader answers the click on the client as well, as Java's villager does, so the client
        // does not go on to offer the other hand and ask a second time. A trader already trading is
        // left alone, which is what keeps any second request from reopening the screen under the
        // first and ending the trade it opened.
        if (isClientSide()) return InteractionResult.SUCCESS;
        if (getTradingPlayer() != null) return super.mobInteract(player, hand);

        setTradingPlayer(player);
        openTradingScreen(player, getDisplayName(), 1);
        return InteractionResult.SUCCESS;
    }

    // `minecraft:bucketable`: Java's own bucket carries the entity away and lets it go again.

    private boolean fromBucket;

    @Override
    public boolean fromBucket() {
        return fromBucket;
    }

    /** An entity let out of a bucket stays, the way Java's fish do. */
    @Override
    public void setFromBucket(boolean fromBucket) {
        this.fromBucket = fromBucket;
        if (fromBucket) setPersistenceRequired();
    }

    /**
     * What the bucket keeps of the entity: what Java keeps of any mob it carries, and which of the
     * pack's component groups were on, since those are what the pack changed about this one.
     */
    @Override
    public void saveToBucketTag(ItemStack bucket) {
        Platform.saveBucketDefaults(this, bucket);
        Platform.putBucketText(bucket, BUCKET_GROUPS, String.join(",", activeGroups));
    }

    @Override
    public void loadFromBucketTag(net.minecraft.nbt.CompoundTag kept) {
        Platform.loadBucketDefaults(this, kept);
        String groups = Platform.bucketText(kept, BUCKET_GROUPS);
        if (groups == null) return;
        takeGroups(groups.isEmpty() ? java.util.Set.of() : java.util.Set.of(groups.split(",")));
    }

    @Override
    public ItemStack getBucketItemStack() {
        net.minecraft.world.item.Item bucket = AddonEntities.bucketOf(getType());
        return bucket == null ? ItemStack.EMPTY : new ItemStack(bucket);
    }

    @Override
    public SoundEvent getPickupSound() {
        return SoundEvents.BUCKET_FILL_FISH;
    }

    /** Where in the bucket the groups that were on are written. */
    private static final String BUCKET_GROUPS = "mcaddon:groups";

    /** Puts the entity in exactly these of its component groups, with what each one brings. */
    private void takeGroups(java.util.Set<String> groups) {
        if (definition == null) return;

        activeGroups.clear();
        for (String group : definition.componentGroups().keySet()) {
            boolean on = groups.contains(group);
            setValue(GROUP + group, on);
            if (on) activeGroups.add(group);
        }
        refreshAge();
        refreshComponents();
        publishMarkers();
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
     * Built once per tier the trader has reached, because a Bedrock group picks a random few of the
     * trades it lists and rebuilding would hand the player a different shop each time they opened it.
     *
     * A tier states the experience its trader needs before it trades at all, so the shop grows as
     * the trader earns it.
     */
    @Override
    public MerchantOffers getOffers() {
        TradeTable trades = trades();
        int reached = trades == null ? 0 : trades.reached(tradeExperience);
        if (offers != null && (offersOverridden || reached == offeredTiers && trades == offered)) {
            return offers;
        }

        offered = trades;
        offeredTiers = reached;
        offers = new MerchantOffers();
        shown = new java.util.ArrayList<>();
        if (trades != null) {
            trades.offers().stream().filter(offer -> offer.tier() < reached).forEach(this::add);
        }
        return offers;
    }

    private void add(TradeTable.Offer offer) {
        TradeTable.Stacks stacks = offer.build(level().getRandom(), this);
        if (stacks == null) return;

        offers.add(Platform.offer(stacks.cost(), stacks.secondCost(), stacks.result(),
            offer.maxUses(), offer.rewardExperience() ? offer.experience() : 0,
            offer.priceMultiplier()));
        shown.add(offer);
    }

    @Override
    public void overrideOffers(MerchantOffers replacement) {
        offers = replacement;
        offersOverridden = true;
    }

    /**
     * A trade's `trader_exp` counts towards the next tier whether or not the player was rewarded.
     *
     * `reward_exp` decides only whether the player gets orbs, which is what the offer's own value
     * holds, so the amount the trader earns is read back off the Bedrock trade instead.
     */
    @Override
    public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
        int index = offers == null ? -1 : offers.indexOf(offer);
        tradeExperience += index >= 0 && index < shown.size()
            ? shown.get(index).experience() : offer.getXp();
        publishTradeTier(trades());
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return tradeExperience;
    }

    /**
     * Left alone, as Java's own traders leave it.
     *
     * Taking a trade's result calls this with the experience plus that trade's share, which is how a
     * client's copy of a merchant keeps its bar up to date. The trader on the server has already
     * counted the trade in `notifyTrade`, so taking it here as well would count every trade twice.
     */
    @Override
    public void overrideXp(int experience) {
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
