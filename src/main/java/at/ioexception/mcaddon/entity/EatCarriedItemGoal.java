package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * Eats whatever the entity picked up, once it has held it long enough.
 *
 * `minecraft:behavior.eat_carried_item` is the piglin's own goal in Bedrock, and a pack pairs it
 * with the pickup behaviour so its entity does something with what it collects rather than carrying
 * it forever. What the food does to the entity is Java's own answer for that item.
 */
public final class EatCarriedItemGoal extends Goal {
    private final Mob mob;
    private final int delay;
    private int held;

    public EatCarriedItemGoal(Mob mob, JsonObject component) {
        this.mob = mob;
        this.delay = (int) (number(component, "delay_before_eating", 3) * 20);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return !mob.getMainHandItem().isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return held < delay && !mob.getMainHandItem().isEmpty();
    }

    @Override
    public void start() {
        held = 0;
    }

    @Override
    public void tick() {
        held++;
        if (held < delay) return;

        ItemStack food = mob.getMainHandItem();
        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        mob.heal(Math.max(1, food.getCount()));
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
