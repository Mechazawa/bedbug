package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * `minecraft:behavior.snacking`: a panda sitting down with the bamboo it is holding.
 *
 * Only the `items` the pack names are snacked on. Each tick of snacking ends it at the
 * `snacking_stop_chance`, which eats what was held, and the next snack waits somewhere between
 * `snacking_cooldown_min` and `snacking_cooldown` seconds.
 */
public final class SnackGoal extends Goal {
    private final Mob mob;
    private final Set<Item> items;
    private final double stopChance;
    private final int cooldownLow;
    private final int cooldownHigh;
    private int readyAt;
    private boolean eaten;

    public SnackGoal(Mob mob, JsonObject component) {
        this.mob = mob;
        this.items = Json.list(component, "items").stream()
            .map(item -> item.isJsonObject() ? Json.text(item.getAsJsonObject(), "name", null)
                : item.isJsonPrimitive() ? item.getAsString() : null)
            .filter(Objects::nonNull)
            .map(AddonItems::named)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        this.stopChance = Goals.number(component, "snacking_stop_chance", 0.0017);
        this.cooldownLow = Goals.ticks(component, "snacking_cooldown_min", 0.5);
        this.cooldownHigh = Goals.ticks(component, "snacking_cooldown", 7.5);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.tickCount >= readyAt && items.contains(mob.getMainHandItem().getItem());
    }

    @Override
    public boolean canContinueToUse() {
        return !eaten && items.contains(mob.getMainHandItem().getItem());
    }

    @Override
    public void start() {
        eaten = false;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (mob.getRandom().nextDouble() >= stopChance) return;

        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        readyAt = mob.tickCount + Goals.between(mob.getRandom(), cooldownLow, cooldownHigh);
        eaten = true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
