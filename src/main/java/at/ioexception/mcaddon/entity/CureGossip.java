package at.ioexception.mcaddon.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The gossip each player who cured a trader left with it: a minor and a major positive value, which
 * `cured_discount` adds to and `max_cured_discount` caps, and a trade prices by as Java's villager
 * prices its reputation.
 *
 * Bedrock writes the values as discounts, so a pack's are negative where Java's are their size. One
 * day of game time after the last decay, every value shrinks by its type's `decayPerDay` in Java's
 * `GossipType` (one for minor, none for major), and a value whose size falls under two is forgotten,
 * as `GossipContainer` decays a villager's gossip.
 */
final class CureGossip {
    /** Bedrock's default `cured_discount`, which are Java's minor and major positive gossip of a cure. */
    private static final int CURE_MINOR = -25;
    private static final int CURE_MAJOR = -20;
    /** How much a major gossip outweighs a minor one, as Java's `GossipType` weighs them. */
    private static final int MAJOR_WEIGHT = 5;
    private static final int[] DECAY_PER_DAY = {1, 0};
    private static final int FORGOTTEN_BELOW = 2;
    static final long DAY = 24000;

    private final Map<UUID, int[]> players = new HashMap<>();
    /** The game time of the last decay, or nothing while the clock has not started. */
    private long decayed;

    /** The player cured the trader whose `minecraft:economy_trade_table` this is. */
    public void cure(UUID player, JsonObject economy, long now) {
        int[] added = pair(economy.get("cured_discount"));
        int[] ceiling = pair(economy.get("max_cured_discount"));
        int[] gossip = players.computeIfAbsent(player, id -> new int[2]);
        for (int kind = 0; kind < 2; kind++) {
            gossip[kind] = ceiling[kind] < 0 ? Math.max(ceiling[kind], gossip[kind] + added[kind])
                : Math.min(ceiling[kind], gossip[kind] + added[kind]);
        }
        if (decayed == 0) decayed = now;
    }

    /**
     * Decays the gossip once a day has passed since the last decay, and says whether anything changed.
     * A clock that has not started starts here, as Java's villager starts its own on its first tick.
     */
    boolean age(long now) {
        if (players.isEmpty()) return false;
        if (decayed == 0) {
            decayed = now;
            return true;
        }
        if (now < decayed + DAY) return false;

        decayed = now;
        players.values().forEach(gossip -> {
            for (int kind = 0; kind < 2; kind++) {
                int size = Math.abs(gossip[kind]) - DECAY_PER_DAY[kind];
                gossip[kind] = size < FORGOTTEN_BELOW ? 0 : Integer.signum(gossip[kind]) * size;
            }
        });
        players.values().removeIf(gossip -> gossip[0] == 0 && gossip[1] == 0);
        return true;
    }

    /** What the player's cure is worth to a price, as Java's reputation, a discount being positive. */
    int reputation(UUID player) {
        int[] gossip = players.get(player);
        return gossip == null ? 0 : -(gossip[0] + MAJOR_WEIGHT * gossip[1]);
    }

    void writeTo(JsonObject saved) {
        JsonObject gossip = new JsonObject();
        players.forEach((player, values) -> {
            JsonArray written = new JsonArray();
            written.add(values[0]);
            written.add(values[1]);
            gossip.add(player.toString(), written);
        });
        saved.add("cured", gossip);
        saved.addProperty("cure_decayed", decayed);
    }

    void readFrom(JsonObject saved) {
        players.clear();
        if (saved.has("cured") && saved.get("cured").isJsonObject()) {
            saved.getAsJsonObject("cured").entrySet().forEach(entry ->
                players.put(UUID.fromString(entry.getKey()), pair(entry.getValue())));
        }
        decayed = saved.has("cure_decayed") ? saved.get("cure_decayed").getAsLong() : 0;
    }

    /** A minor and a major gossip value, written as a pair or as one number for both. */
    private static int[] pair(JsonElement written) {
        if (written != null && written.isJsonArray() && written.getAsJsonArray().size() == 2) {
            return new int[] {written.getAsJsonArray().get(0).getAsInt(), written.getAsJsonArray().get(1).getAsInt()};
        }
        if (written != null && written.isJsonPrimitive()) {
            return new int[] {written.getAsInt(), written.getAsInt()};
        }
        return new int[] {CURE_MINOR, CURE_MAJOR};
    }
}
