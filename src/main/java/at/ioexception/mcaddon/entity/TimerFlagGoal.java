package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Holds the entity still for a while and then fires the pack's own event.
 *
 * Bedrock's three timer flags are the same goal three times over, so a pack can run three of them
 * at different priorities. Each names a duration, a cooldown and the event to raise at the end,
 * which is how a pack times an animation without a script.
 */
public final class TimerFlagGoal extends Goal {
    private final AddonEntity mob;
    private final int duration;
    private final int cooldownLow;
    private final int cooldownHigh;
    private final String endEvent;
    private int held;
    private int untilNext;

    public TimerFlagGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.duration = (int) (number(component, "duration", 0.5) * 20);
        JsonObject onEnd = component.getAsJsonObject("on_end");
        this.endEvent = onEnd == null ? null : Json.text(onEnd, "event", null);

        JsonElement cooldown = component.get("cooldown_range");
        boolean pair = cooldown != null && cooldown.isJsonArray() && cooldown.getAsJsonArray().size() >= 2;
        this.cooldownLow = (int) ((pair ? cooldown.getAsJsonArray().get(0).getAsDouble() : 10) * 20);
        this.cooldownHigh = (int) ((pair ? cooldown.getAsJsonArray().get(1).getAsDouble() : 10) * 20);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return untilNext-- <= 0;
    }

    @Override
    public boolean canContinueToUse() {
        return held < duration;
    }

    @Override
    public void start() {
        held = 0;
    }

    @Override
    public void tick() {
        held++;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        untilNext = cooldownLow + mob.getRandom().nextInt(Math.max(1, cooldownHigh - cooldownLow + 1));
        if (endEvent != null) EntityEvents.trigger(mob, endEvent, null, ServerHooks.state());
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
