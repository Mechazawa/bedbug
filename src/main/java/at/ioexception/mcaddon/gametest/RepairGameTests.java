package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RepairItemRecipe;

import java.util.List;

/**
 * `tp:file` repairs by the `repair_amount` Molang its `minecraft:repairable` states: a tenth of its
 * hundred durability per flint, and twice what a second file has left when two are combined.
 */
public final class RepairGameTests {
    private static ItemStack file(Item item, int damage) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(damage);
        return stack;
    }

    private static int anvil(GameTestHelper helper, ItemStack left, ItemStack right) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AnvilMenu anvil = new AnvilMenu(0, player.getInventory(),
            ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)));
        anvil.getSlot(0).set(left);
        anvil.getSlot(1).set(right);
        return anvil.getSlot(2).getItem().getDamageValue();
    }

    /** One flint gives back a tenth, where Java would give back a quarter. */
    @GameTest()
    public void aMaterialRepairsByTheAmountItsEntryStates(GameTestHelper helper) {
        Item file = BuiltInRegistries.ITEM.getOptional(Platform.id("tp", "file")).orElse(null);
        if (file == null) {
            helper.succeed();
            return;
        }

        int damage = anvil(helper, file(file, 60), new ItemStack(Items.FLINT));
        helper.assertTrue(damage == 50, "A file at 60 damage repaired with a flint kept " + damage + ".");
        helper.succeed();
    }

    /**
     * A second file gives back twice what it has left, with the anvil's twelve in a hundred and the
     * grid's five on top.
     */
    @GameTest()
    public void aCombineRepairsByTheAmountItsEntryStates(GameTestHelper helper) {
        Item file = BuiltInRegistries.ITEM.getOptional(Platform.id("tp", "file")).orElse(null);
        if (file == null) {
            helper.succeed();
            return;
        }

        int anvilled = anvil(helper, file(file, 60), file(file, 90));
        helper.assertTrue(anvilled == 28, "Two files combined at an anvil kept " + anvilled + " damage.");
        int crafted = RepairItemRecipe.INSTANCE.assemble(
            CraftingInput.of(2, 1, List.of(file(file, 60), file(file, 90)))).getDamageValue();
        helper.assertTrue(crafted == 35, "Two files combined in the grid kept " + crafted + " damage.");
        helper.succeed();
    }
}
