package at.ioexception.mcaddon.entity;

import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The screen a player opens onto an entity's `minecraft:inventory`: Java's chest grid in as many rows
 * as the slots need, up to six. Java's horse, hopper and minecart menus are built for their own
 * entity classes, so every `container_type` that opens draws this grid. The cells past the last slot
 * hold nothing and take nothing.
 */
public final class StockMenu extends ChestMenu {
    private static final int COLUMNS = 9;
    private static final MenuType<?>[] GRIDS = {MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
        MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6};

    private final AddonEntity owner;

    private StockMenu(int id, net.minecraft.world.entity.player.Inventory inventory, AddonEntity owner,
        Container stock, int rows) {
        super(GRIDS[rows - 1], id, inventory, new Padded(owner, stock, rows * COLUMNS), rows);
        this.owner = owner;
        for (int cell = stock.getContainerSize(); cell < rows * COLUMNS; cell++) {
            Slot padding = slots.get(cell);
            Slot shut = new Slot(padding.container, cell, padding.x, padding.y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }
            };
            shut.index = cell;
            slots.set(cell, shut);
        }
    }

    static MenuProvider of(AddonEntity owner, Container stock) {
        int rows = Math.clamp((stock.getContainerSize() + COLUMNS - 1) / COLUMNS, 1, GRIDS.length);
        return new SimpleMenuProvider((id, inventory, player) -> new StockMenu(id, inventory, owner, stock, rows),
            owner.getDisplayName());
    }

    boolean belongsTo(AddonEntity entity) {
        return owner == entity;
    }

    /** The entity's slots padded out to whole rows, open while the entity is alive and near. */
    private record Padded(AddonEntity owner, Container stock, int size) implements Container {
        private static final double REACH = 8;

        @Override
        public int getContainerSize() {
            return size;
        }

        @Override
        public boolean isEmpty() {
            return stock.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot < stock.getContainerSize() ? stock.getItem(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return slot < stock.getContainerSize() ? stock.removeItem(slot, count) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return slot < stock.getContainerSize() ? stock.removeItemNoUpdate(slot) : ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (slot < stock.getContainerSize()) stock.setItem(slot, stack);
        }

        @Override
        public void setChanged() {
            stock.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return owner.isAlive() && player.distanceToSqr(owner) < REACH * REACH;
        }

        @Override
        public void clearContent() {
            stock.clearContent();
        }
    }
}
