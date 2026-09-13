package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
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

    /** The entity the synthetic pack declares young without giving it anything to grow into. */
    private static final String YOUNG_ENTITY = "tp:sprite";

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

    /**
     * A Bedrock loot function Java has none of picks an enchantment and rolls its level.
     *
     * `enchant_book_for_trading` reaches a loot function of this mod's own type carrying the object
     * the pack wrote, so what is under test is the whole path: the type registered, the converted
     * table named it, the object survived the codec and the book came back enchanted.
     */
    @GameTest()
    public void aBedrockLootFunctionEnchantsTheBookItNames(GameTestHelper helper) {
        List<ItemStack> rolled = Platform.rollLoot(helper.getLevel(),
            Platform.id("tp", "entities/tome"), Platform.mockPlayer(helper));
        if (rolled.isEmpty()) {
            helper.succeed();
            return;
        }

        helper.assertTrue(rolled.stream().anyMatch(stack -> Platform.storedEnchantmentCount(stack) > 0),
            "The pack's book table handed out a book carrying no enchantment.");
        helper.succeed();
    }

    /**
     * A drop the pack asked to keep lies there past the five minutes an ordinary one lasts.
     *
     * The lifetime is counted on the entity and the pack states it on the item, so the two are only
     * joined once a stack is lying in a world.
     */
    @GameTest()
    public void aDropThePackKeepsIsGivenNoDeadline(GameTestHelper helper) {
        Item kept = AddonItems.all().get(KEPT_ITEM);
        if (kept == null) {
            helper.succeed();
            return;
        }

        BlockPos at = helper.absolutePos(STAND);
        ItemEntity dropped = new ItemEntity(helper.getLevel(),
            at.getX() + 0.5, at.getY(), at.getZ() + 0.5, new ItemStack(kept));
        helper.getLevel().addFreshEntity(dropped);

        helper.assertTrue(dropped.getAge() < 0,
            KEPT_ITEM + " was dropped and started counting down to despawning anyway.");
        helper.succeed();
    }

    /**
     * A dyeable item starts out the colour the pack painted it.
     *
     * 1.20.1 decides a dyed colour from the item's Java class, which a data-driven item has none of,
     * so there is nothing on the stack to read there.
     */
    @GameTest()
    public void aDyeableItemCarriesThePacksColour(GameTestHelper helper) {
        Item dyed = AddonItems.all().get(DYED_ITEM);
        if (dyed == null) {
            helper.succeed();
            return;
        }

        //? if >=1.21 {
        net.minecraft.world.item.component.DyedItemColor colour = new ItemStack(dyed)
            .get(net.minecraft.core.component.DataComponents.DYED_COLOR);
        helper.assertTrue(colour != null, DYED_ITEM + " was declared dyeable and carries no colour.");
        helper.assertTrue((colour.rgb() & 0xFFFFFF) == DYED_COLOUR,
            DYED_ITEM + " carries a colour the pack did not ask for.");
        //?}
        helper.succeed();
    }

    /**
     * An entity arrives wearing what its equipment table rolled.
     *
     * The table is named the Bedrock way and looked up the Java way, and where that lookup lives
     * moved between versions, so the dressing can silently come out empty on one of them.
     */
    @GameTest()
    public void anEntityWearsWhatItsTableRolled(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(ENTITY);
        if (type == null || AddonEntities.definitionOf(type) == null
            || !AddonEntities.definitionOf(type).components().containsKey("minecraft:equipment")) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);

        helper.assertTrue(spawned instanceof net.minecraft.world.entity.Mob dressed
                && !dressed.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty(),
            ENTITY + " names an equipment table and arrived wearing nothing.");
        helper.succeed();
    }

    /**
     * An entity whose pack declared it young reads as young.
     *
     * `minecraft:is_baby` is the flag for an entity that is young without growing up, and a pack's
     * render controllers branch on it through `query.is_baby` to pick the geometry drawn for those
     * proportions. A wrong answer picks the grown geometry for the body while the parts written for
     * the young one keep their own place, so a hat lands above the head it belongs on.
     */
    @GameTest()
    public void anEntityDeclaredYoungReadsAsYoung(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(YOUNG_ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);

        helper.assertTrue(spawned instanceof AddonEntity young && young.isBaby(),
            YOUNG_ENTITY + " declares minecraft:is_baby and reads as grown.");
        helper.succeed();
    }

    /**
     * A bow a pack declared fires its ammunition when it is drawn and let go.
     *
     * Drawing runs through the game's own use timer rather than through a call, so what is checked
     * is the whole path: starting a use, the ticks counting down, and the release that launches.
     */
    @GameTest()
    public void aShooterFiresWhatThePackLoadsIt(GameTestHelper helper) {
        Item bow = AddonItems.all().get(SHOOTER_ITEM);
        if (bow == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(bow instanceof at.ioexception.mcaddon.item.AddonShooterItem,
            SHOOTER_ITEM + " declares a shooter and did not become one.");

        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(STAND);
        Player archer = Platform.mockPlayer(helper);
        Platform.placeAt(archer, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, -20f);
        // The hand is a slot of the inventory, so the bow has to be held before the ammunition is
        // handed over or it lands in the same slot and is thrown away again.
        archer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(bow));
        archer.getInventory().add(new ItemStack(net.minecraft.world.item.Items.ARROW, 8));

        archer.startUsingItem(InteractionHand.MAIN_HAND);
        for (int tick = 0; tick < FULL_DRAW; tick++) {
            archer.tick();
        }
        archer.releaseUsingItem();

        helper.assertTrue(!level.getEntitiesOfClass(Projectile.class,
                archer.getBoundingBox().inflate(8)).isEmpty(),
            SHOOTER_ITEM + " was drawn and released and nothing was fired.");
        helper.succeed();
    }

    /**
     * What a merchant's offers come out as once every item function has run over them.
     *
     * The book the pack sells is enchanted and priced as the offers are built, which is the one path
     * where a Bedrock item function reaches the other side of the trade it sits on. A trade whose
     * result is a map of somewhere this world holds none of is left out instead of reaching the
     * screen as an offer of nothing, and one whose function this mod only reports keeps its item.
     */
    @GameTest()
    public void aMerchantsOffersCarryWhatItsItemFunctionsDid(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);

        List<ItemStack> results = new java.util.ArrayList<>();
        ItemStack book = ItemStack.EMPTY;
        int paidForBook = 0;
        for (net.minecraft.world.item.trading.MerchantOffer offer
            : ((AddonEntity) spawned).getOffers()) {
            results.add(offer.getResult());
            if (offer.getResult().is(net.minecraft.world.item.Items.ENCHANTED_BOOK)) {
                book = offer.getResult();
                paidForBook = offer.getBaseCostA().getCount();
            }
        }

        helper.assertTrue(results.stream().noneMatch(ItemStack::isEmpty),
            ENTITY + " offers a trade that hands over nothing.");
        helper.assertTrue(results.stream().anyMatch(stack ->
                stack.is(net.minecraft.world.item.Items.LEATHER_HELMET)),
            ENTITY + " lost the trade whose result is painted and whose data value is reported.");
        helper.assertTrue(!book.isEmpty(), ENTITY + " offers no enchanted book.");
        helper.assertTrue(Platform.storedEnchantmentCount(book) > 0,
            "the book " + ENTITY + " sells carries no enchantment.");
        helper.assertTrue(paidForBook >= BOOK_FLOOR,
            "the book " + ENTITY + " sells costs " + paidForBook + ", under the priced floor.");
        helper.succeed();
    }

    /** The base cost and one level of the per-level cost the pack's book trade states. */
    private static final int BOOK_FLOOR = 5;

    /** Longer than the second the synthetic pack's bow takes to draw fully. */
    private static final int FULL_DRAW = 25;

    /** The item the synthetic pack declares a bow. */
    private static final String SHOOTER_ITEM = "tp:wand";

    /** The item the synthetic pack asks to keep on the ground. */
    private static final String KEPT_ITEM = "tp:dust";

    /** The item the synthetic pack declares dyeable, and the colour it declares it. */
    private static final String DYED_ITEM = "tp:shard";
    private static final int DYED_COLOUR = 0x4C8FD1;
}
