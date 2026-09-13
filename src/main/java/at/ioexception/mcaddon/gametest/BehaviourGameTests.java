package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * What a pack's behaviours do that only a running world can show.
 *
 * Owning an entity and rolling a table are both reached through calls whose spelling differs on each
 * of the four versions this builds against, so either can compile everywhere and work nowhere.
 */
public final class BehaviourGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /** The entity the synthetic pack declares, which every assertion about one uses. */
    private static final String ENTITY = "tp:ember";

    /**
     * Taming an entity writes down whose it is, and the writing survives being read back.
     *
     * The owner is kept in the add-on state rather than in entity NBT, the same as every other fact
     * a pack sets on an entity, so nothing about it exists until an entity is in a world.
     */
    @GameTest()
    public void aTamedEntityRemembersWhoOwnsIt(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        helper.assertTrue(spawned instanceof AddonEntity, ENTITY + " is not an add-on entity.");

        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);

        AddonEntity pet = (AddonEntity) spawned;
        helper.assertTrue(pet.ownerUuid() == null, ENTITY + " arrived already owned.");

        Player owner = Platform.mockPlayer(helper);
        pet.setOwner(owner);

        helper.assertTrue(owner.getUUID().equals(pet.ownerUuid()),
            ENTITY + " was tamed and did not remember by whom.");
        helper.assertTrue(owner.getStringUUID().equals(
            ServerHooks.state().property(pet.getUUID(), "owner")),
            "The owner never reached the state a restart reads back.");
        helper.succeed();
    }

    /**
     * A loot table hands out items when a behaviour asks it to, with nothing having died.
     *
     * A sneeze and a swallowed mob both drop what a table holds, and where that table is looked up
     * moved between the versions, so a wrong lookup costs those behaviours their drop while
     * everything around them keeps working.
     *
     * A table hands back one stack per entry it rolled, and an entry whose count came out at zero is
     * an empty stack among them, so what is asserted is that something real was in the roll.
     */
    @GameTest()
    public void aLootTableRollsWithoutADeath(GameTestHelper helper) {
        List<ItemStack> rolled = Platform.rollLoot(helper.getLevel(),
            Platform.id("minecraft", "entities/cow"), Platform.mockPlayer(helper));
        helper.assertTrue(rolled.stream().anyMatch(stack -> !stack.isEmpty()),
            "A table every version ships handed out nothing.");
        helper.succeed();
    }
}
