package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.EnumSet;

/**
 * Stares at whoever is holding food it wants.
 *
 * `minecraft:behavior.beg` names the food and how long the stare lasts. A pack draws the begging
 * off `q.is_begging`, which stands for as long as the player keeps holding something on the list.
 */
public final class BegGoal extends Goal {
    private final AddonEntity mob;
    private final Set<String> liked = new LinkedHashSet<>();
    private final double lookDistance;
    private final int shortestLook;
    private final int longestLook;
    private int lookingFor;
    private Player begged;

    public BegGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.lookDistance = Goals.number(component, "look_distance", 8);
        this.shortestLook = Goals.ticksLow(component, "look_time", 2);
        this.longestLook = Goals.ticksHigh(component, "look_time", 4);
        for (JsonElement element : Json.list(component, "items")) {
            String name = element.isJsonPrimitive() ? element.getAsString()
                : element.isJsonObject() ? Json.text(element.getAsJsonObject(), "item", null) : null;
            if (name != null) liked.add(name);
        }
        setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        begged = mob.level().getNearestPlayer(mob, lookDistance);
        return begged != null && holdingSomethingLiked(begged);
    }

    @Override
    public boolean canContinueToUse() {
        return lookingFor > 0 && begged != null && begged.isAlive()
            && mob.distanceToSqr(begged) <= lookDistance * lookDistance
            && holdingSomethingLiked(begged);
    }

    @Override
    public void start() {
        lookingFor = Goals.between(mob.getRandom(), shortestLook, longestLook);
        mob.setValue("is_begging", true);
    }

    @Override
    public void tick() {
        lookingFor--;
        if (begged != null) mob.getLookControl().setLookAt(begged.getX(), begged.getEyeY(), begged.getZ());
    }

    @Override
    public void stop() {
        begged = null;
        lookingFor = 0;
        mob.setValue("is_begging", false);
    }

    private boolean holdingSomethingLiked(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (named(player.getItemInHand(hand))) return true;
        }
        return false;
    }

    /** A pack writes an item name with the namespace as often as without it, so both are matched. */
    private boolean named(ItemStack stack) {
        if (stack.isEmpty() || liked.isEmpty()) return false;

        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return liked.contains(id.toString()) || liked.contains(id.getPath());
    }
}
