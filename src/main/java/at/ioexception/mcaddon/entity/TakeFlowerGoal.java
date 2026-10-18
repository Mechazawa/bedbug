package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * `minecraft:behavior.take_flower`: taking the flower an iron golem holds out.
 *
 * Java's golem offers one and its own villager accepts, and a pack's entity is walked over and made
 * to take it. The goal starts while its `filters` hold and a golem inside `search_area` is offering,
 * waits a random while between `min_wait_time` and `max_wait_time` looking at the golem, then walks
 * to within `min_distance_to_target` and takes the flower.
 */
public final class TakeFlowerGoal extends Goal {
    private final AddonEntity mob;
    private final Filter filters;
    private final double[] searchArea;
    private final int waitLow;
    private final int waitHigh;
    private final double reach;
    private final double speed;
    private final float maxYaw;
    private final float maxPitch;
    private final String takeEvent;
    private Entity golem;
    private int waiting;

    public TakeFlowerGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.filters = Goals.filter(component, "filters");
        this.searchArea = vector(component.get("search_area"), new double[] {6, 2, 6});
        this.waitLow = Goals.ticks(component, "min_wait_time", 4);
        this.waitHigh = Goals.ticks(component, "max_wait_time", 20);
        this.reach = Goals.number(component, "min_distance_to_target", 2);
        this.speed = Goals.number(component, "speed_multiplier", 0.5);
        this.maxYaw = (float) Goals.number(component, "max_head_rotation_y", 30);
        this.maxPitch = (float) Goals.number(component, "max_rotation_x", 30);
        this.takeEvent = Goals.event(component, "on_take_flower");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!filters.matches(new Filter.Context(mob, null, null, ServerHooks.state()))) return false;

        AABB area = mob.getBoundingBox().inflate(searchArea[0], searchArea[1], searchArea[2]);
        golem = Platform.offeredFlower(mob, area);
        return golem != null;
    }

    @Override
    public boolean canContinueToUse() {
        return golem != null && golem.isAlive() && Platform.offersFlower(golem);
    }

    @Override
    public void start() {
        waiting = Goals.between(mob.getRandom(), waitLow, waitHigh);
    }

    @Override
    public void stop() {
        golem = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(golem, maxYaw, maxPitch);
        if (waiting-- > 0) return;

        if (mob.distanceTo(golem) > reach) {
            mob.getNavigation().moveTo(golem, speed);
            return;
        }
        Platform.offerFlower(golem, false);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.POPPY));
        if (takeEvent != null) EntityEvents.trigger(mob, takeEvent, null, ServerHooks.state());
        golem = null;
    }

    /** A vector written as an array of three, or a single number standing for all three. */
    private static double[] vector(JsonElement written, double[] fallback) {
        if (written != null && written.isJsonPrimitive()) {
            double all = written.getAsDouble();
            return new double[] {all, all, all};
        }
        if (written == null || !written.isJsonArray() || written.getAsJsonArray().size() < 3) return fallback;

        JsonArray axes = written.getAsJsonArray();
        return new double[] {axes.get(0).getAsDouble(), axes.get(1).getAsDouble(), axes.get(2).getAsDouble()};
    }
}
