package at.ioexception.mcaddon.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks to a dropped item and puts it on, which is what `minecraft:behavior.equip_item` describes.
 *
 * Bedrock separates picking an item up from wearing it: the pickup behaviour puts it in the
 * entity's hand and this one moves it to where it belongs. Java decides the slot from the item, so
 * armour is worn and everything else is held.
 */
public final class EquipItemGoal extends Goal {
    /** Far enough to be worth walking to, close enough that the entity does not cross a field. */
    private static final double REACH = 8;

    private final Mob mob;
    private final Set<Item> excluded;
    private ItemEntity wanted;

    /**
     * `minecraft:equip_item` is the component beside this goal, and its `excluded_items` is the list
     * of things the entity leaves alone however well they would fit.
     */
    public EquipItemGoal(Mob mob, JsonObject component) {
        this.mob = mob;
        this.excluded = excluded(component);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private static Set<Item> excluded(JsonObject component) {
        Set<Item> named = new LinkedHashSet<>();
        if (component == null) return named;

        for (JsonElement element : at.ioexception.mcaddon.util.Json.list(component, "excluded_items")) {
            String name = element.isJsonPrimitive() ? element.getAsString()
                : element.isJsonObject()
                    ? at.ioexception.mcaddon.util.Json.text(element.getAsJsonObject(), "item", null)
                    : null;
            // A Bedrock item name may carry the data value it was written with, which Java has none of.
            if (name == null) continue;

            int aux = name.indexOf(':', name.indexOf(':') + 1);
            Item found = at.ioexception.mcaddon.registry.AddonItems.named(
                aux < 0 ? name : name.substring(0, aux));
            if (found != null) named.add(found);
        }
        return named;
    }

    @Override
    public boolean canUse() {
        equipHeld();
        wanted = nearest();
        return wanted != null;
    }

    @Override
    public boolean canContinueToUse() {
        return wanted != null && wanted.isAlive() && !wanted.isRemoved();
    }

    @Override
    public void stop() {
        wanted = null;
    }

    @Override
    public void tick() {
        if (wanted == null) return;
        if (mob.distanceTo(wanted) > 1.5) {
            mob.getNavigation().moveTo(wanted, 1);
            return;
        }

        ItemStack stack = wanted.getItem().copy();
        wanted.discard();
        wanted = null;
        mob.setItemSlot(slotFor(stack), stack);
    }

    /**
     * Moves what the entity is already holding onto its body when that is where it belongs.
     *
     * A pack pairs this goal with the pickup behaviour, which leaves everything in the main hand, so
     * a helmet picked up off the ground would otherwise be carried rather than worn.
     */
    private void equipHeld() {
        ItemStack held = mob.getMainHandItem();
        EquipmentSlot slot = slotFor(held);
        if (held.isEmpty() || slot == EquipmentSlot.MAINHAND) return;
        if (!mob.getItemBySlot(slot).isEmpty()) return;

        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        mob.setItemSlot(slot, held);
    }

    /** Asked of the entity rather than of the class: 26 moved the question onto the living entity. */
    private EquipmentSlot slotFor(ItemStack stack) {
        return stack.isEmpty() ? EquipmentSlot.MAINHAND : mob.getEquipmentSlotForItem(stack);
    }

    private ItemEntity nearest() {
        List<ItemEntity> dropped = mob.level().getEntitiesOfClass(ItemEntity.class,
            mob.getBoundingBox().inflate(REACH),
            item -> item.isAlive() && !item.hasPickUpDelay()
                && !excluded.contains(item.getItem().getItem())
                && slotFor(item.getItem()) != EquipmentSlot.MAINHAND
                && mob.getItemBySlot(slotFor(item.getItem())).isEmpty());
        return dropped.stream().min(java.util.Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }
}
