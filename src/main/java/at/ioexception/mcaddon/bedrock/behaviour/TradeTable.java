package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A villager trade table, flattened into the offers Java understands.
 *
 * Bedrock nests trades in tiers and groups, and a group offers only some of what it lists. Java has
 * no tiers of its own, so each offer remembers the tier it came from and a trader shows the offers
 * of every tier its earned experience has reached.
 */
public record TradeTable(List<Offer> offers, List<Integer> tierExperience) {

    /** One trade: what it takes, what it gives back, and how often it can be used. */
    public record Offer(TradeItem cost, TradeItem secondCost, TradeItem result, int maxUses,
        int experience, boolean rewardExperience, float priceMultiplier, int tier) {
    }

    /** One side of a trade: which item, how many, and what is run over the stack it becomes. */
    public record TradeItem(String item, int count, List<JsonElement> functions) {

        /** The stack this describes, with every function it names run over it. */
        public ItemStack stack(RandomSource random, RegistryAccess registries) {
            Identifier id = item == null ? null : Identifier.tryParse(item);
            ItemStack stack = id == null ? ItemStack.EMPTY
                : BuiltInRegistries.ITEM.getOptional(id)
                    .map(found -> new ItemStack(found, Math.max(1, count)))
                    .orElse(ItemStack.EMPTY);

            for (JsonElement element : functions) {
                if (element.isJsonObject()) {
                    stack = applyFunction(element.getAsJsonObject(), stack, random, registries);
                }
            }
            return stack;
        }
    }

    /** Every field of a trade table this reads, qualified by the level carrying it. */
    private static final Set<String> HANDLES = Set.of(
        "table.tiers", "tier.groups", "tier.trades", "tier.total_exp_required",
        "group.num_to_select", "group.trades",
        "trade.wants", "trade.gives", "trade.max_uses", "trade.trader_exp", "trade.reward_exp",
        "trade.weight", "item.item", "item.quantity", "item.choice", "item.functions",
        "item.price_multiplier");

    /**
     * Runs one function over the stack a trade is about to offer.
     *
     * `random_dye` and the two aux value functions paint a Bedrock item data value, which Java has
     * no room for on a stack. `exploration_map` needs a structure found in the world, and
     * `enchant_book_for_trading` prices a book against a villager's own level; both describe a trade
     * Java builds in its own code rather than from a table.
     */
    private static ItemStack applyFunction(JsonObject function, ItemStack stack, RandomSource random,
        RegistryAccess registries) {
        String name = Json.text(function, "function", "");
        int colon = name.indexOf(':');
        return switch (colon < 0 ? name : name.substring(colon + 1)) {
            case "set_count" -> {
                stack.setCount(Math.max(1, (int) Json.number(function, "count", stack.getCount())));
                yield stack;
            }
            case "enchant_with_levels" -> Platform.enchant(random, stack,
                (int) between(function.get("levels")), Json.bool(function, "treasure", false),
                registries);
            case "enchant_randomly" -> Platform.enchant(random, stack, 5 + random.nextInt(26),
                false, registries);
            default -> {
                AddonEntities.unsupported("trade function " + name);
                yield stack;
            }
        };
    }

    public static TradeTable read(BedrockPack pack, String path) {
        Path file = pack.file(path);
        if (!Files.isRegularFile(file)) {
            McAddon.LOGGER.warn("{} names a trade table it does not ship: {}", pack.displayName(), path);
            return new TradeTable(List.of(), List.of());
        }

        try {
            return readTiers(Json.parseObject(file));
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read {}: {}", path, error.toString());
            return new TradeTable(List.of(), List.of());
        }
    }

    public int tiers() {
        return tierExperience.size();
    }

    /** How many tiers a trader holding this much experience has reached, and so may offer. */
    public int reached(int experience) {
        int count = 0;
        for (Integer required : tierExperience) {
            if (experience >= required) count++;
        }
        return Math.max(1, count);
    }

    private static TradeTable readTiers(JsonObject root) {
        List<Offer> offers = new ArrayList<>();
        List<Integer> required = new ArrayList<>();

        for (JsonElement element : Json.list(root, "tiers")) {
            if (!element.isJsonObject()) continue;

            JsonObject tier = element.getAsJsonObject();
            int index = required.size();
            required.add((int) Json.number(tier, "total_exp_required", 0));

            for (JsonElement group : Json.list(tier, "groups")) {
                if (group.isJsonObject()) offers.addAll(readGroup(group.getAsJsonObject(), index));
            }
            for (JsonElement trade : Json.list(tier, "trades")) {
                if (trade.isJsonObject()) offers.addAll(readTrade(trade.getAsJsonObject(), index));
            }
        }
        return new TradeTable(offers, required);
    }

    /** `num_to_select` is how many of a group's trades a villager actually offers. */
    private static List<Offer> readGroup(JsonObject group, int tier) {
        List<JsonObject> candidates = new ArrayList<>();
        for (JsonElement trade : Json.list(group, "trades")) {
            if (trade.isJsonObject()) candidates.add(trade.getAsJsonObject());
        }

        int wanted = (int) Json.number(group, "num_to_select", candidates.size());
        List<Offer> offers = new ArrayList<>();
        for (JsonObject trade : pick(candidates, wanted)) {
            offers.addAll(readTrade(trade, tier));
        }
        return offers;
    }

    /** Draws `wanted` trades without replacement, each trade's `weight` deciding how likely it is. */
    private static List<JsonObject> pick(List<JsonObject> candidates, int wanted) {
        if (wanted >= candidates.size()) return candidates;

        List<JsonObject> left = new ArrayList<>(candidates);
        List<JsonObject> chosen = new ArrayList<>();
        while (chosen.size() < Math.max(0, wanted) && !left.isEmpty()) {
            double total = left.stream().mapToDouble(trade -> Json.number(trade, "weight", 1)).sum();
            double point = ThreadLocalRandom.current().nextDouble() * total;

            int index = left.size() - 1;
            for (int i = 0; i < left.size(); i++) {
                point -= Json.number(left.get(i), "weight", 1);
                if (point <= 0) {
                    index = i;
                    break;
                }
            }
            chosen.add(left.remove(index));
        }
        return chosen;
    }

    /**
     * One Bedrock trade, as the offers it becomes.
     *
     * A `choice` on what the trade wants lists items any one of which pays for it, and a Java offer
     * names one cost, so the trade becomes one offer per item on that list.
     */
    private static List<Offer> readTrade(JsonObject trade, int tier) {
        List<JsonElement> wants = Json.list(trade, "wants");
        List<JsonElement> gives = Json.list(trade, "gives");
        if (wants.isEmpty() || gives.isEmpty()) return List.of();

        List<TradeItem> costs = alternatives(wants.get(0));
        TradeItem second = wants.size() > 1 ? only(wants.get(1)) : null;
        TradeItem result = only(gives.get(0));
        if (costs.isEmpty() || result == null || result.item() == null) return List.of();

        int maxUses = (int) Json.number(trade, "max_uses", 12);
        int experience = (int) Json.number(trade, "trader_exp", 0);
        boolean reward = Json.bool(trade, "reward_exp", true);
        float multiplier = (float) Json.number(stated(wants.get(0)), "price_multiplier", 0);

        return costs.stream()
            .filter(cost -> cost.item() != null)
            .map(cost -> new Offer(cost, second, result, maxUses, experience, reward, multiplier, tier))
            .toList();
    }

    /** Every item that pays for a trade, which is the `choice` list or the one item named. */
    private static List<TradeItem> alternatives(JsonElement side) {
        JsonObject entry = entryOf(side);
        List<JsonElement> choices = Json.list(entry, "choice");
        if (choices.isEmpty()) return List.of(item(entry, entry));

        return choices.stream().filter(JsonElement::isJsonObject)
            .map(choice -> item(choice.getAsJsonObject(), entry)).toList();
    }

    /** A trade's own side, which is the entry itself unless a `choice` list stands in its place. */
    private static JsonObject stated(JsonElement side) {
        JsonObject entry = entryOf(side);
        List<JsonElement> choices = Json.list(entry, "choice");
        return !choices.isEmpty() && !entry.has("price_multiplier") && choices.get(0).isJsonObject()
            ? choices.get(0).getAsJsonObject() : entry;
    }

    /** The first item of a side, and a report when a choice list there loses its alternatives. */
    private static TradeItem only(JsonElement side) {
        List<TradeItem> found = alternatives(side);
        if (found.size() > 1) AddonEntities.unsupported("trade choice beyond the first cost");
        return found.isEmpty() ? null : found.get(0);
    }

    private static JsonObject entryOf(JsonElement side) {
        return side.isJsonObject() ? side.getAsJsonObject() : new JsonObject();
    }

    /** A choice states its own quantity where it has one, and takes the trade's where it does not. */
    private static TradeItem item(JsonObject entry, JsonObject outer) {
        // A filter decides at trade time whether the item is offered at all, out of the Bedrock
        // filter vocabulary, which a Java offer built once and sent to the client cannot ask again.
        if (entry.has("filters") || outer.has("filters")) AddonEntities.unsupported("trade item filters");

        JsonElement quantity = entry.has("quantity") ? entry.get("quantity") : outer.get("quantity");
        return new TradeItem(Json.text(entry, "item", null), (int) between(quantity),
            Json.list(entry, "functions"));
    }

    /** A quantity or a level is a number or a range the trade picks from when it is offered. */
    private static double between(JsonElement value) {
        if (value == null) return 1;
        if (value.isJsonPrimitive()) return value.getAsDouble();
        if (!value.isJsonObject()) return 1;

        JsonObject range = value.getAsJsonObject();
        double low = Json.number(range, "min", 1);
        double high = Json.number(range, "max", low);
        return high <= low ? low : low + ThreadLocalRandom.current().nextDouble() * (high - low + 1);
    }
}
