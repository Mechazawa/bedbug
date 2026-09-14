package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.mixin.FoodDataAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The numbers a behaviour pack writes on the player, which Bedrock reads nowhere else.
 *
 * A pack states them in its own `entities/player.json`, so they arrive as components of a definition
 * that redescribes an entity the game holds in code. The five attributes are a starting state: Java
 * builds them into `FoodData` and into `Player.experienceLevel` when the player is made, so this
 * writes them at a first join and at every respawn. The ten exhaustion prices are constants Java
 * spends at the call sites of `Player.causeFoodExhaustion`, which is why each is fetched back at the
 * call site rather than applied once.
 *
 * A field the pack leaves out stays Java's own, so a pack that writes half a component keeps the
 * half it did not write.
 */
public final class PlayerDefinition {
    /** The identifier a pack has to write these on, because Bedrock ignores them anywhere else. */
    public static final String PLAYER = "minecraft:player";

    public static final Set<String> COMPONENTS = Set.of(
        "minecraft:player.hunger", "minecraft:player.saturation", "minecraft:player.exhaustion",
        "minecraft:player.level", "minecraft:player.experience", "minecraft:exhaustion_values");

    /**
     * One moment that costs a player exhaustion, and what Java spends on it.
     *
     * The Java price is what the call site pushes before calling `causeFoodExhaustion`, so a price
     * the pack states is met by scaling what arrives. `walk` is the exception: Java spends nothing,
     * so scaling can never raise it and the distance is charged outright.
     *
     * `lunge` is the price of one enchantment rather than of a moment, and Java's own charges 4 per
     * level through `ApplyExhaustion`, which is the same shape and the same number Bedrock states.
     */
    public enum Action {
        ATTACK("attack", 0.1F),
        DAMAGE("damage", 0.1F),
        HEAL("heal", 6.0F),
        JUMP("jump", 0.05F),
        LUNGE("lunge", 4.0F),
        MINE("mine", 0.005F),
        SPRINT("sprint", 0.1F),
        SPRINT_JUMP("sprint_jump", 0.2F),
        SWIM("swim", 0.01F),
        WALK("walk", 0.0F);

        private final String key;
        private final float javaPrice;

        Action(String key, float javaPrice) {
            this.key = key;
            this.javaPrice = javaPrice;
        }

        public String key() {
            return key;
        }

        public float javaPrice() {
            return javaPrice;
        }
    }

    /** A `value` and a `max`, either of which the pack may leave to Java. */
    public record Attribute(Float value, Float max) {
        static Attribute of(JsonObject body) {
            return new Attribute(number(body, "value"), number(body, "max"));
        }

        private static Float number(JsonObject body, String key) {
            JsonElement declared = body == null ? null : body.get(key);
            return declared != null && declared.isJsonPrimitive() ? declared.getAsFloat() : null;
        }
    }

    private static final Attribute NOTHING = new Attribute(null, null);

    /** The one a pack installed, which every call site reaches for and most worlds never have. */
    private static PlayerDefinition declared;

    private final Attribute hunger;
    private final Attribute saturation;
    private final Attribute exhaustion;
    private final Attribute level;
    private final Attribute experience;
    private final Map<Action, Float> prices;
    private final Set<String> taken;
    private final boolean lunge;

    private PlayerDefinition(Map<String, JsonObject> components) {
        hunger = attribute(components, "minecraft:player.hunger");
        saturation = attribute(components, "minecraft:player.saturation");
        exhaustion = attribute(components, "minecraft:player.exhaustion");
        level = attribute(components, "minecraft:player.level");
        experience = attribute(components, "minecraft:player.experience");
        JsonObject values = components.get("minecraft:exhaustion_values");
        prices = prices(values);
        lunge = values != null && values.has("lunge");
        taken = new LinkedHashSet<>();
        COMPONENTS.stream().filter(components::containsKey).forEach(taken::add);
    }

    /** Null unless the definition is the player's and writes at least one of the six components. */
    public static PlayerDefinition tryFrom(ServerEntityDefinition definition) {
        if (!PLAYER.equals(definition.identifier())) return null;

        Map<String, JsonObject> components = definition.components();
        return COMPONENTS.stream().anyMatch(components::containsKey)
            ? new PlayerDefinition(components) : null;
    }

    /** What every call site reads, null in a world whose packs write no player definition. */
    public static PlayerDefinition declared() {
        return declared;
    }

    /** Makes this the definition every call site reads. */
    public void install() {
        declared = this;
    }

    /** The components of the six this took, which the override report leaves out. */
    public Set<String> taken() {
        return taken;
    }

    public Attribute hunger() {
        return hunger;
    }

    public Attribute saturation() {
        return saturation;
    }

    public Attribute exhaustion() {
        return exhaustion;
    }

    public Attribute level() {
        return level;
    }

    public Attribute experience() {
        return experience;
    }

    /**
     * Whether this game has the lunge enchantment, which is the only thing `lunge` prices.
     *
     * 26 added `minecraft:lunge` and the `ApplyExhaustion` effect it spends its exhaustion through.
     * Before that there is no such enchantment and no moment to charge the price at.
     */
    //? if >=26 {
    private static final boolean LUNGE_ENCHANTMENT = true;
    //?} else {
    /*private static final boolean LUNGE_ENCHANTMENT = false;
    *///?}

    /** Fields of a component this took and did nothing with, under the component's own name. */
    public Set<String> unread() {
        return lunge && !LUNGE_ENCHANTMENT ? Set.of("minecraft:exhaustion_values lunge") : Set.of();
    }

    /** What the call sites ask: the pack's price where a pack wrote one, Java's where none did. */
    public static float cost(Action action, float amount) {
        return declared == null ? amount : declared.priced(action, amount);
    }

    /**
     * What a jump costs, told apart by what Java was about to spend on it.
     *
     * `jumpFromGround` charges 0.2 for a jump taken while sprinting and 0.05 for every other one,
     * and those two amounts are the only ones that reach it.
     */
    public static float jumpCost(float amount) {
        return cost(amount > Action.JUMP.javaPrice() ? Action.SPRINT_JUMP : Action.JUMP, amount);
    }

    /**
     * What one tick of movement costs, given the charge Java was about to make and how far the
     * player moved along the ground.
     *
     * A charge of nothing is the walking or the crouching branch, which Java prices at zero, so the
     * distance is the only thing left to charge the pack's price against. Any other charge is the
     * swimming or the sprinting one, told apart the same way the method itself tells them apart:
     * water is tested before the ground is.
     */
    public static float movementCost(Player player, float amount, double flat) {
        if (declared == null) return amount;
        if (amount == 0.0F) return declared.walkPrice(flat);

        boolean water = player.isSwimming() || player.isEyeInFluid(FluidTags.WATER)
            || player.isInWater();
        return declared.priced(water ? Action.SWIM : Action.SPRINT, amount);
    }

    /**
     * What a moment costs, given what Java was about to spend on it.
     *
     * Scaling rather than replacing is what keeps the shape of the call site: the distance walked in
     * a movement charge and the weight of the damage type in a damage charge both ride in on the
     * amount, and a pack changing the price should not flatten either.
     */
    public float priced(Action action, float amount) {
        Float price = prices.get(action);
        return price == null ? amount : amount * price / action.javaPrice();
    }

    /**
     * What walking or crouching costs over a distance, in blocks.
     *
     * Java spends nothing on either, so there is no amount to scale and the distance is the only
     * thing the price can be charged against. Bedrock prices crouching as walking, having no entry
     * of its own for it.
     */
    public float walkPrice(double blocks) {
        Float price = prices.get(Action.WALK);
        return price == null ? 0.0F : price * Math.round((float) blocks * 100.0F) * 0.01F;
    }

    /** The starting state, which Bedrock gives a player when it builds one. */
    public void applyTo(Player player) {
        FoodData food = player.getFoodData();
        if (hunger.value() != null) food.setFoodLevel(Math.round(hunger.value()));
        if (saturation.value() != null) food.setSaturation(saturation.value());
        if (exhaustion.value() != null) {
            ((FoodDataAccessor) food).mcaddon$setExhaustion(exhaustion.value());
        }
        if (level.value() != null) player.experienceLevel = Math.round(level.value());
        if (experience.value() != null) player.experienceProgress = experience.value();
        capped(player);
        // Writing the fields leaves the client showing what it was last sent. Setting the levels to
        // what they already are is what makes the server send the bar again.
        if (player instanceof ServerPlayer sent) sent.setExperienceLevels(sent.experienceLevel);
    }

    /**
     * The ceilings, which Java holds as literals rather than as anything a definition can reach.
     *
     * `FoodData` caps the food bar at 20 and the exhaustion it accumulates at 40, and Java caps
     * neither the level nor the progress towards the next one, so a pack asking for a lower ceiling
     * is answered by holding the value under it wherever it is next looked at.
     */
    public void capped(Player player) {
        FoodData food = player.getFoodData();
        if (hunger.max() != null && food.getFoodLevel() > hunger.max()) {
            food.setFoodLevel(Math.round(hunger.max()));
        }
        if (saturation.max() != null && food.getSaturationLevel() > saturation.max()) {
            food.setSaturation(saturation.max());
        }
        if (exhaustion.max() != null
            && ((FoodDataAccessor) food).mcaddon$exhaustion() > exhaustion.max()) {
            ((FoodDataAccessor) food).mcaddon$setExhaustion(exhaustion.max());
        }
        if (level.max() != null && player.experienceLevel > level.max()) {
            player.experienceLevel = Math.round(level.max());
        }
        if (experience.max() != null && player.experienceProgress > experience.max()) {
            player.experienceProgress = experience.max();
        }
    }

    private static Attribute attribute(Map<String, JsonObject> components, String name) {
        JsonObject body = components.get(name);
        return body == null ? NOTHING : Attribute.of(body);
    }

    private static Map<Action, Float> prices(JsonObject body) {
        Map<Action, Float> prices = new EnumMap<>(Action.class);
        if (body == null) return prices;

        for (Action action : Action.values()) {
            JsonElement price = body.get(action.key());
            if (price != null && price.isJsonPrimitive()) prices.put(action, price.getAsFloat());
        }
        return prices;
    }
}
