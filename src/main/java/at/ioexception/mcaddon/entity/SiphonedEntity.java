package at.ioexception.mcaddon.entity;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * An add-on entity whose `minecraft:inventory` some group marks `can_be_siphoned_from`.
 *
 * Java's hopper takes from and fills an entity that is a `Container`, which is a question of the
 * entity's class, so such an entity type is built from this class. Its slots are open to a hopper
 * only while the live inventory says so, in both directions, as Bedrock's chest minecart is; at
 * other moments it is a container of no slots.
 */
public class SiphonedEntity extends AddonEntity implements Container {
    private static final Container SHUT = new SimpleContainer(0);

    public SiphonedEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    private Container open() {
        return inventory() != null
            && Goals.flag(liveComponent("minecraft:inventory"), "can_be_siphoned_from", false) ? inventory() : SHUT;
    }

    @Override
    public int getContainerSize() {
        return open().getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return open().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return open().getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        return open().removeItem(slot, count);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return open().removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        open().setItem(slot, stack);
    }

    @Override
    public void setChanged() {
        open().setChanged();
    }

    @Override
    public void clearContent() {
        open().clearContent();
    }
}
