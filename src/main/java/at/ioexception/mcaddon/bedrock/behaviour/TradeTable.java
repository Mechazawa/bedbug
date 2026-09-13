package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A villager trade table, flattened into the offers Java understands.
 *
 * Bedrock nests trades in tiers and groups, and a group offers only some of what it lists. Java has
 * no tiers, so every tier's picks are flattened into one list at the point the entity first trades.
 */
public record TradeTable(List<Offer> offers, int tiers) {
    /** One trade: what it takes, what it gives back, and how often it can be used. */
    public record Offer(String costItem, int costCount, String secondCostItem, int secondCostCount,
        String resultItem, int resultCount, int maxUses, int experience) {
    }

    public static TradeTable read(BedrockPack pack, String path) {
        Path file = pack.file(path);
        if (!Files.isRegularFile(file)) {
            McAddon.LOGGER.warn("{} names a trade table it does not ship: {}", pack.displayName(), path);
            return new TradeTable(List.of(), 0);
        }

        try {
            JsonObject root = Json.parseObject(file);
            return new TradeTable(readTiers(root), Json.list(root, "tiers").size());
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read {}: {}", path, error.toString());
            return new TradeTable(List.of(), 0);
        }
    }

    private static List<Offer> readTiers(JsonObject root) {
        List<Offer> offers = new ArrayList<>();
        for (JsonElement tier : Json.list(root, "tiers")) {
            if (!tier.isJsonObject()) continue;
            for (JsonElement group : Json.list(tier.getAsJsonObject(), "groups")) {
                if (group.isJsonObject()) offers.addAll(readGroup(group.getAsJsonObject()));
            }
            for (JsonElement trade : Json.list(tier.getAsJsonObject(), "trades")) {
                if (trade.isJsonObject()) {
                    Offer offer = readTrade(trade.getAsJsonObject());
                    if (offer != null) offers.add(offer);
                }
            }
        }
        return offers;
    }

    /** `num_to_select` is how many of a group's trades a villager actually offers. */
    private static List<Offer> readGroup(JsonObject group) {
        List<Offer> candidates = new ArrayList<>();
        for (JsonElement trade : Json.list(group, "trades")) {
            if (!trade.isJsonObject()) continue;
            Offer offer = readTrade(trade.getAsJsonObject());
            if (offer != null) candidates.add(offer);
        }

        int wanted = group.has("num_to_select") ? group.get("num_to_select").getAsInt() : candidates.size();
        if (wanted >= candidates.size()) return candidates;

        List<Offer> chosen = new ArrayList<>(candidates);
        java.util.Collections.shuffle(chosen, ThreadLocalRandom.current());
        return chosen.subList(0, Math.max(0, wanted));
    }

    private static Offer readTrade(JsonObject trade) {
        List<JsonElement> wants = Json.list(trade, "wants");
        List<JsonElement> gives = Json.list(trade, "gives");
        if (wants.isEmpty() || gives.isEmpty()) return null;

        JsonObject first = wants.get(0).isJsonObject() ? wants.get(0).getAsJsonObject() : null;
        JsonObject second = wants.size() > 1 && wants.get(1).isJsonObject()
            ? wants.get(1).getAsJsonObject() : null;
        JsonObject result = gives.get(0).isJsonObject() ? gives.get(0).getAsJsonObject() : null;
        if (first == null || result == null) return null;

        String costItem = Json.text(first, "item", null);
        String resultItem = Json.text(result, "item", null);
        if (costItem == null || resultItem == null) return null;

        return new Offer(costItem, quantity(first), second == null ? null : Json.text(second, "item", null),
            second == null ? 0 : quantity(second), resultItem, quantity(result),
            trade.has("max_uses") ? trade.get("max_uses").getAsInt() : 12,
            trade.has("trader_exp") ? trade.get("trader_exp").getAsInt() : 0);
    }

    /** A quantity is a number or a range the trade picks from when it is offered. */
    private static int quantity(JsonObject entry) {
        JsonElement quantity = entry.get("quantity");
        if (quantity == null) return 1;
        if (quantity.isJsonPrimitive()) return quantity.getAsInt();
        if (!quantity.isJsonObject()) return 1;

        JsonObject range = quantity.getAsJsonObject();
        int low = range.has("min") ? range.get("min").getAsInt() : 1;
        int high = range.has("max") ? range.get("max").getAsInt() : low;
        return high <= low ? low : ThreadLocalRandom.current().nextInt(low, high + 1);
    }
}
