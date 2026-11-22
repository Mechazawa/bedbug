package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** `minecraft:inventory`'s fields, read back off `tp:packer`, whose groups each carry one inventory. */
public final class PackerGameTests {
    private static final String PACKER = "tp:packer";
    private static final BlockPos STAND = new BlockPos(2, 0, 2);

    /**
     * A `container_type` of `container` opens Java's chest grid on a use: eight slots fill one row,
     * the first holds what the entity carries and the ninth cell takes nothing.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aContainerInventoryOpensOnUse(GameTestHelper helper) {
        AddonEntity packer = packer(helper, "tp:packer_chest", STAND);
        if (packer == null) {
            helper.succeed();
            return;
        }

        packer.inventory().setItem(0, new ItemStack(Items.APPLE));
        HagglerGameTests.Customer customer = HagglerGameTests.Customer.join(helper);
        packer.mobInteract(customer.player(), InteractionHand.MAIN_HAND);
        AbstractContainerMenu menu = customer.player().containerMenu;
        customer.leave();
        helper.assertTrue(menu instanceof ChestMenu, PACKER + " opened " + menu + " and not its slots.");
        helper.assertTrue(menu.getSlot(0).getItem().is(Items.APPLE), PACKER + "'s apple is not in its first slot.");
        helper.assertTrue(menu.getSlot(7).mayPlace(new ItemStack(Items.STONE)),
            PACKER + "'s eighth slot takes nothing.");
        helper.assertFalse(menu.getSlot(8).mayPlace(new ItemStack(Items.STONE)),
            PACKER + " has eight slots and its ninth cell takes an item.");
        helper.succeed();
    }

    /**
     * A `horse` inventory opens only to a crouching player, and `additional_slots_per_strength` of
     * three at a strength of two gives six of the sixteen slots `inventory_size` allows.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aSaddlebagGrowsWithStrengthAndOpensToACrouchingPlayer(GameTestHelper helper) {
        AddonEntity packer = packer(helper, "tp:packer_saddlebag", STAND);
        if (packer == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(packer.inventory().getContainerSize() == SADDLEBAG_SLOTS,
            PACKER + " has a strength of two and " + packer.inventory().getContainerSize() + " slots.");
        HagglerGameTests.Customer customer = HagglerGameTests.Customer.join(helper);
        packer.mobInteract(customer.player(), InteractionHand.MAIN_HAND);
        boolean openedStanding = customer.player().containerMenu instanceof ChestMenu;
        customer.player().setPose(Pose.CROUCHING);
        packer.mobInteract(customer.player(), InteractionHand.MAIN_HAND);
        boolean openedCrouching = customer.player().containerMenu instanceof ChestMenu;
        customer.leave();
        helper.assertFalse(openedStanding, PACKER + "'s saddlebags opened to a player standing up.");
        helper.assertTrue(openedCrouching, PACKER + "'s saddlebags did not open to a crouching player.");
        helper.succeed();
    }

    private static final int SADDLEBAG_SLOTS = 6;

    /**
     * `can_be_siphoned_from` lets a hopper under the entity take what it carries; one without it keeps
     * its apple.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void aHopperTakesOnlyFromASiphonedInventory(GameTestHelper helper) {
        BlockPos sealedAt = STAND.east(2);
        helper.setBlock(STAND, Blocks.HOPPER);
        helper.setBlock(sealedAt, Blocks.HOPPER);
        AddonEntity siphoned = packer(helper, "tp:packer_siphon", STAND.above());
        AddonEntity sealed = packer(helper, "tp:packer_seal", sealedAt.above());
        if (siphoned == null || sealed == null) {
            helper.succeed();
            return;
        }

        siphoned.inventory().setItem(0, new ItemStack(Items.APPLE));
        sealed.inventory().setItem(0, new ItemStack(Items.APPLE));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(
                helper.getBlockEntity(STAND, HopperBlockEntity.class).countItem(Items.APPLE) == 1,
                "The hopper under the siphoned " + PACKER + " took nothing."))
            .thenExecute(() -> helper.assertTrue(sealed.inventory().countItem(Items.APPLE) == 1,
                "A hopper took the apple of a " + PACKER + " that is not siphoned."))
            .thenSucceed();
    }

    private static AddonEntity packer(GameTestHelper helper, String event, BlockPos at) {
        AddonEntity packer = BehaviourGameTests.spawn(helper, PACKER, at);
        if (packer == null) return null;

        EntityEvents.trigger(packer, event, null, ServerHooks.state());
        return packer;
    }
}
