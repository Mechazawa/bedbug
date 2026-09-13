package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.List;

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
    private ItemEntity wanted;

    public EquipItemGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
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
                && slotFor(item.getItem()) != EquipmentSlot.MAINHAND
                && mob.getItemBySlot(slotFor(item.getItem())).isEmpty());
        return dropped.stream().min(java.util.Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }
}
