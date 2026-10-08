package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How far along a young entity is, for the pack that said it grows up.
 *
 * Bedrock keeps growing up on the entity as `minecraft:ageable`: a duration, the foods that shorten
 * it and the event fired at the end. Java writes the same idea into `Animal`, which a pack's entity
 * is not one of, so the count lives here and the entity asks it what to draw and when to fire.
 */
public final class Age {
    private final int duration;
    private final Map<String, Integer> feedItems;
    private final Map<String, Integer> pauseItems;
    private final Map<String, Integer> resetItems;
    private final String grownEvent;
    private final float startScale;
    private final float endScale;
    private int grown;
    private boolean paused;

    private Age(int duration, Map<String, Integer> feedItems, Map<String, Integer> pauseItems,
        Map<String, Integer> resetItems, String grownEvent, float startScale, float endScale) {
        this.duration = duration;
        this.feedItems = feedItems;
        this.pauseItems = pauseItems;
        this.resetItems = resetItems;
        this.grownEvent = grownEvent;
        this.startScale = startScale;
        this.endScale = endScale;
    }

    /**
     * Null for an entity that carries nothing saying it is young.
     *
     * The components are read off the entity rather than off its definition, because a pack states
     * both of these inside the component group that makes the entity young rather than at the top.
     */
    public static Age tryFrom(AddonEntity entity) {
        JsonObject ageable = entity.liveComponent("minecraft:ageable");
        if (ageable == null) return null;

        JsonObject growUp = ageable.getAsJsonObject("grow_up");
        JsonObject scaling = entity.liveComponent("minecraft:scale_by_age");
        // Both scales default to one, which is what Bedrock documents. A pack that wants its baby
        // drawn small says so with `minecraft:scale` in the group that makes it young, which is
        // what all forty-two of the vanilla entities that age at a different size do.
        return new Age(
            (int) (number(ageable, "duration", 1200) * 20),
            items(ageable, "feed_items"),
            items(ageable, "pause_growth_items"),
            items(ageable, "reset_growth_items"),
            growUp == null ? null : Json.text(growUp, "event", null),
            (float) number(scaling, "start_scale", 1),
            (float) number(scaling, "end_scale", 1));
    }

    /**
     * The foods that bring the day forward, and how many seconds each is worth.
     *
     * A pack writes an entry either as the item on its own or as the item with its own growth, and
     * an entry without one is worth a tenth of the wait, which is what Bedrock gives it.
     */
    private static Map<String, Integer> items(JsonObject ageable, String field) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (JsonElement element : Json.list(ageable, field)) {
            if (element.isJsonPrimitive()) {
                items.put(element.getAsString(), 0);
            } else if (element.isJsonObject()) {
                String item = Json.text(element.getAsJsonObject(), "item", null);
                if (item != null) items.put(item, (int) number(element.getAsJsonObject(), "growth", 0));
            }
        }
        return items;
    }

    public boolean baby() {
        return grown < duration;
    }

    /** The event fired once the entity is grown, or null when the pack named none. */
    public String grownEvent() {
        return grownEvent;
    }

    /** Between the two scales `minecraft:scale_by_age` names, by how much growing is left. */
    public float scale() {
        if (duration <= 0) return endScale;
        float along = Math.min(1f, (float) grown / duration);
        return startScale + (endScale - startScale) * along;
    }

    /** True on the step that finishes the growing, so the pack's event fires once. */
    public boolean advance(int ticks) {
        if (paused || !baby()) return false;

        grown += ticks;
        return !baby();
    }

    /**
     * Being handed one of the foods the pack named, which brings growing up forward.
     *
     * A food with no growth of its own is worth a tenth of the whole wait. False leaves the item in
     * the player's hand, because nothing was eaten.
     */
    public boolean fed(ItemStack offered) {
        if (offered.isEmpty() || !baby()) return false;

        String id = AddonItems.identifierOf(offered.getItem());
        // A pack names three lists beside each other, and only one of them brings the day forward:
        // `pause_growth_items` stops the count where it is and `reset_growth_items` puts it back to
        // the start, which is how a pack keeps an entity young while something is being fed to it.
        if (named(resetItems, id)) {
            grown = 0;
            paused = false;
            return true;
        }
        if (named(pauseItems, id)) {
            paused = true;
            return true;
        }

        for (Map.Entry<String, Integer> item : feedItems.entrySet()) {
            if (!names(item.getKey(), id)) continue;

            grown += item.getValue() > 0 ? item.getValue() * 20 : Math.max(1, duration / 10);
            return true;
        }
        return false;
    }

    private static boolean named(Map<String, Integer> items, String id) {
        return items.keySet().stream().anyMatch(name -> names(name, id));
    }

    /** Whether a pack's item name, in either edition's spelling, is the item reported as `id`. */
    private static boolean names(String written, String id) {
        return AddonItems.identifierOf(written).equals(id);
    }

    private static double number(JsonObject body, String key, double fallback) {
        return body == null || !body.has(key) || !body.get(key).isJsonPrimitive()
            ? fallback : body.get(key).getAsDouble();
    }
}
