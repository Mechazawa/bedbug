package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What `minecraft:offspring` passes from two parents to the child they bred.
 *
 * A child takes its owner from a tamed parent unless `inherit_tamed` says no, its variant and mark
 * variant from one parent or the other unless a mutation keeps the one it was born with, each
 * property `property_inheritance` names from one parent or the other, and its colour from one parent
 * or, under `combine_parent_colors`, from the dye the two parents' colours make together. Each
 * attribute `parent_centric_attribute_blending` names is blended out of both parents' values the
 * way Java breeds a horse, inside the range the child's own component states.
 */
public final class Offspring {
    /**
     * The dyes Java's crafting makes out of two others, as Bedrock's colour numbers, which count the
     * sixteen dyes in the same order as Java's own. Two colours with no recipe leave the child one
     * parent's colour.
     */
    private static final Map<Long, Integer> MIXED = Map.of(
        pair(14, 4), 1,     // red and yellow make orange
        pair(10, 6), 2,     // purple and pink make magenta
        pair(11, 0), 3,     // blue and white make light blue
        pair(13, 0), 5,     // green and white make lime
        pair(14, 0), 6,     // red and white make pink
        pair(15, 0), 7,     // black and white make grey
        pair(7, 0), 8,      // grey and white make light grey
        pair(11, 13), 9,    // blue and green make cyan
        pair(11, 14), 10);  // blue and red make purple

    private final boolean inheritsOwner;
    private final List<String> inherited;
    private final List<String> blended;
    private final boolean combinesColours;
    private final double colourMutation;
    private final Trait variant;
    private final Trait markVariant;

    /** How one of the two variant numbers is passed on, and what a mutation replaces it with. */
    private record Trait(String value, double mutation, int[] interval, double denial, int[] denied) {
        void passOn(AddonEntity child, AddonEntity first, AddonEntity second, RandomSource random) {
            Object fromFirst = first.value(value);
            Object fromSecond = second.value(value);
            if (!(fromFirst instanceof Number) && !(fromSecond instanceof Number)) return;

            if (random.nextDouble() < mutation) {
                if (interval != null) child.setValue(value, (double) Goals.between(random, interval[0], interval[1]));
                return;
            }
            if (denied != null && fromFirst instanceof Number one && fromSecond instanceof Number other
                && one.doubleValue() == other.doubleValue() && random.nextDouble() < denial) {
                child.setValue(value, (double) Goals.between(random, denied[0], denied[1]));
                return;
            }
            child.setValue(value, either(fromFirst, fromSecond, random));
        }
    }

    private Offspring(JsonObject component) {
        this.inheritsOwner = Goals.flag(component, "inherit_tamed", true);
        this.inherited = names(component.get("property_inheritance"));
        this.blended = names(component.get("parent_centric_attribute_blending"));
        this.combinesColours = Goals.flag(component, "combine_parent_colors", false);

        JsonObject mutation = Goals.object(component, "mutation_factor");
        this.colourMutation = Goals.number(mutation, "color", 0);
        boolean random = "random".equals(Goals.text(component, "mutation_strategy"));

        JsonObject denial = Goals.object(component, "deny_parents_variant");
        this.variant = new Trait("variant", Goals.number(mutation, "variant", 0),
            random ? interval(component, "random_variant_mutation_interval") : null,
            Goals.number(denial, "chance", 0),
            denial == null ? null : new int[] {
                (int) Goals.number(denial, "min_variant", 0), (int) Goals.number(denial, "max_variant", 0)});
        this.markVariant = new Trait("mark_variant", Goals.number(mutation, "extra_variant", 0),
            random ? interval(component, "random_extra_variant_mutation_interval") : null, 0, null);
    }

    /** Null for a component that is not there, which leaves a child as it was born. */
    public static Offspring tryFrom(JsonObject component) {
        return component == null ? null : new Offspring(component);
    }

    /** Hands the child what it takes from its two parents, once it is in the world. */
    public void passOn(AddonEntity child, AddonEntity first, AddonEntity second, RandomSource random) {
        if (inheritsOwner) {
            AddonEntity tamed = first.ownerUuid() != null ? first : second.ownerUuid() != null ? second : null;
            if (tamed != null) {
                child.keepOwnerOf(tamed);
                ServerHooks.state().setProperty(child.getUUID(), "is_tamed", true);
            }
        }
        variant.passOn(child, first, second, random);
        markVariant.passOn(child, first, second, random);
        inherited.forEach(name -> {
            Object chosen = either(first.value(name), second.value(name), random);
            if (chosen != null) child.setValue(name, chosen);
        });
        passOnColour(child, first, second, random);
        blended.forEach(attribute -> blend(child, first, second, attribute, random));
    }

    /**
     * One attribute blended out of both parents, which is Java's own horse breeding: around the
     * parents' average, spread by how far apart they are plus three tenths of the range, and folded
     * back inside the range at either end. The range is the one the first parent's component states
     * for the value; a component stating one number leaves the child the parents' average.
     */
    private static void blend(AddonEntity child, AddonEntity first, AddonEntity second, String name,
        RandomSource random) {
        AttributeInstance own = instance(child, name);
        AttributeInstance fromFirst = instance(first, name);
        AttributeInstance fromSecond = instance(second, name);
        if (own == null || fromFirst == null || fromSecond == null) return;

        JsonObject range = Goals.object(first.liveComponent(name), "value");
        double low = Goals.number(range, "range_min", 0);
        double high = Goals.number(range, "range_max", 0);

        double value = (fromFirst.getBaseValue() + fromSecond.getBaseValue()) / 2;
        if (high > low) {
            double firstValue = Mth.clamp(fromFirst.getBaseValue(), low, high);
            double secondValue = Mth.clamp(fromSecond.getBaseValue(), low, high);
            double spread = Math.abs(firstValue - secondValue) + 2 * HORSE_MARGIN * (high - low);
            double quality = (random.nextDouble() + random.nextDouble() + random.nextDouble()) / 3 - 0.5;
            value = (firstValue + secondValue) / 2 + spread * quality;
            if (value > high) value = 2 * high - value;
            if (value < low) value = 2 * low - value;
        }
        own.setBaseValue(value);
        if (name.equals("minecraft:health")) child.setHealth(child.getMaxHealth());
    }

    /** The share of an attribute's range Java's horse breeding spreads a child by on either side. */
    private static final double HORSE_MARGIN = 0.15;

    /** The Java attribute each blendable component holds its value in. */
    private static AttributeInstance instance(AddonEntity entity, String name) {
        return switch (name) {
            case "minecraft:health" -> entity.getAttribute(Attributes.MAX_HEALTH);
            case "minecraft:movement" -> entity.getAttribute(Attributes.MOVEMENT_SPEED);
            case "minecraft:horse.jump_strength" -> entity.getAttribute(Attributes.JUMP_STRENGTH);
            default -> null;
        };
    }

    private void passOnColour(AddonEntity child, AddonEntity first, AddonEntity second, RandomSource random) {
        if (!(first.value("color") instanceof Number one) || !(second.value("color") instanceof Number other)) return;
        if (random.nextDouble() < colourMutation) return;

        Integer mixed = combinesColours ? MIXED.get(pair(one.intValue(), other.intValue())) : null;
        child.setValue("color", mixed != null ? (double) mixed : either(one, other, random));
    }

    /** One parent's value or the other's, at even odds, or whichever of the two is there at all. */
    private static Object either(Object first, Object second, RandomSource random) {
        if (first == null) return second;
        if (second == null) return first;
        return random.nextBoolean() ? first : second;
    }

    /** The same key for two colours whichever order they come in. */
    private static long pair(int one, int other) {
        return ((long) Math.min(one, other) << 32) | Math.max(one, other);
    }

    /** The property names a pack lists, as the keys of an object or as a list of names. */
    private static List<String> names(JsonElement listed) {
        List<String> names = new ArrayList<>();
        if (listed == null) return names;
        if (listed.isJsonObject()) names.addAll(listed.getAsJsonObject().keySet());
        if (listed.isJsonArray()) {
            listed.getAsJsonArray().forEach(name -> {
                if (name.isJsonPrimitive()) names.add(name.getAsString());
            });
        }
        return names;
    }

    private static int[] interval(JsonObject component, String key) {
        return new int[] {(int) Goals.low(component, key, 0), (int) Goals.high(component, key, 0)};
    }
}
