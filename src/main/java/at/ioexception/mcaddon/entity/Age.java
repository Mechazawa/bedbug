package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
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
    private final String grownEvent;
    private final float startScale;
    private final float endScale;
    private int grown;

    private Age(int duration, Map<String, Integer> feedItems, String grownEvent,
        float startScale, float endScale) {
        this.duration = duration;
        this.feedItems = feedItems;
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
        return new Age(
            (int) (number(ageable, "duration", 1200) * 20),
            feedItems(ageable),
            growUp == null ? null : Json.text(growUp, "event", null),
            (float) number(scaling, "start_scale", 0.5),
            (float) number(scaling, "end_scale", 1));
    }

    /**
     * The foods that bring the day forward, and how many seconds each is worth.
     *
     * A pack writes an entry either as the item on its own or as the item with its own growth, and
     * an entry without one is worth a tenth of the wait, which is what Bedrock gives it.
     */
    private static Map<String, Integer> feedItems(JsonObject ageable) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (JsonElement element : Json.list(ageable, "feed_items")) {
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
        if (!baby()) return false;

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

        String id = BuiltInRegistries.ITEM.getKey(offered.getItem()).toString();
        for (Map.Entry<String, Integer> item : feedItems.entrySet()) {
            String named = item.getKey();
            if (!named.equals(id) && !("minecraft:" + named).equals(id)) continue;

            grown += item.getValue() > 0 ? item.getValue() * 20 : Math.max(1, duration / 10);
            return true;
        }
        return false;
    }

    private static double number(JsonObject body, String key, double fallback) {
        return body == null || !body.has(key) || !body.get(key).isJsonPrimitive()
            ? fallback : body.get(key).getAsDouble();
    }
}
