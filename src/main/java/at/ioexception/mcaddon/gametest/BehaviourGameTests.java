package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
     * A table asks what the mob it belongs to just swallowed, and drops what that meal is worth.
     *
     * `entity_killed` is the one Bedrock condition about a moment rather than about a death: the
     * table `minecraft:behavior.eat_mob` names is rolled as the meal goes down, so what the
     * condition asks about is the mob that goal just took. The pack's table holds one pool per kind
     * of meal, so a condition answering the wrong way shows up as the other pool's drop.
     */
    @GameTest()
    public void aBedrockLootConditionAsksWhatTheMobSwallowed(GameTestHelper helper) {
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

        EntityType<?> mealType = AddonEntities.typeOf(YOUNG_ENTITY);
        if (mealType == null) {
            helper.succeed();
            return;
        }

        AddonEntity hunter = (AddonEntity) spawned;
        Identifier table = Platform.id("tp", "entities/meal");
        helper.assertTrue(Platform.rollLoot(level, table, hunter).isEmpty(),
            "A table conditioned on a meal dropped something before anything was eaten.");

        hunter.setKilled(Platform.createMob(mealType, level));
        List<ItemStack> rolled = Platform.rollLoot(level, table, hunter);
        helper.assertTrue(rolled.stream().anyMatch(stack -> stack.is(Items.PORKCHOP)),
            "The pool the swallowed " + YOUNG_ENTITY + " unlocks dropped nothing.");
        helper.assertTrue(rolled.stream().noneMatch(stack -> stack.is(Items.SLIME_BALL)),
            "The pool for a meal that was never eaten dropped anyway.");
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
     * The wool a table hands out is the colour of the entity it was rolled for.
     *
     * `set_data_from_color_index` is the one data value written nowhere in the pack: it is whatever
     * colour the entity is carrying at the moment the table is rolled, and the colour arrives with
     * a component group, so nothing about it exists until an entity is standing in a world.
     */
    @GameTest()
    public void aColourIndexPicksTheItemTheEntitysOwnColourNames(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        Identifier table = Platform.id("tp", "entities/fleece");
        helper.assertTrue(rolled(helper, table, ember).contains("minecraft:white_wool"),
            ENTITY + " carries no colour and its table handed out no white wool.");

        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        helper.assertTrue(rolled(helper, table, ember).contains("minecraft:blue_wool"),
            ENTITY + " was given colour " + GROUPED_DYE + " and its table handed out other wool.");
        helper.succeed();
    }

    /**
     * A range over a Bedrock id's data values hands out one of the items that id stood for.
     *
     * Which one is settled per roll rather than per table, so what a run of rolls shows is the
     * whole path: the items the conversion worked out survived the codec, and the roll is what
     * picks between them rather than the entry naming one of them for good. Sixteen rolls over
     * three carpets land on one colour about once in seven million runs.
     */
    @GameTest()
    public void aRangeOfDataValuesPicksOneOfTheItemsItCovers(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        List<String> covered = List.of("minecraft:orange_carpet", "minecraft:magenta_carpet",
            "minecraft:light_blue_carpet");
        Identifier table = Platform.id("tp", "entities/fleece");
        java.util.Set<String> carpets = new java.util.LinkedHashSet<>();
        for (int roll = 0; roll < 16; roll++) {
            rolled(helper, table, ember).stream().filter(covered::contains).forEach(carpets::add);
        }

        helper.assertTrue(!carpets.isEmpty(),
            ENTITY + " has a table naming three carpets by data value and handed out none of them.");
        helper.assertTrue(carpets.size() > 1,
            ENTITY + " rolled its carpet range sixteen times and every roll was " + carpets + ".");
        helper.succeed();
    }

    /** What a table handed out, by id, which is what a data value picks between. */
    private static List<String> rolled(GameTestHelper helper, Identifier table, Entity about) {
        return Platform.rollLoot(helper.getLevel(), table, about).stream()
            .map(stack -> net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString())
            .toList();
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
     * A marker a component group brings in is published while the group is on and not after it.
     *
     * Bedrock states nearly every one of these inside a group rather than at the top: a horse is
     * saddled by its saddled group going on and a sheep is sheared until its sheared group goes off
     * again. The flag is what a render controller branches on through `query.is_saddled` to hide or
     * show the saddle, so a flag that never arrives draws a saddled mount bare and one never cleared
     * leaves a sheep bald after its wool has grown back.
     */
    @GameTest()
    public void aMarkerFollowsTheComponentGroupThatCarriesIt(GameTestHelper helper) {
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
        helper.assertTrue(spawned instanceof AddonEntity, ENTITY + " is not an add-on entity.");
        AddonEntity ember = (AddonEntity) spawned;

        helper.assertTrue(!Boolean.TRUE.equals(ember.value("is_saddled")),
            ENTITY + " reads as saddled before its saddled group is on.");

        EntityEvents.trigger(ember, "tp:ember_saddle", null, ServerHooks.state());
        helper.assertTrue(Boolean.TRUE.equals(ember.value("is_saddled")),
            ENTITY + " gained minecraft:is_saddled from a group and reads as unsaddled.");

        EntityEvents.trigger(ember, "tp:ember_unsaddle", null, ServerHooks.state());
        helper.assertTrue(Boolean.FALSE.equals(ember.value("is_saddled")),
            ENTITY + " lost the group carrying minecraft:is_saddled and still reads as saddled.");
        helper.succeed();
    }

    /**
     * `has_component` answers for a component a live group carries as well as for a declared one.
     *
     * A pack asks this to find out which of an entity's groups are on, because a group is the only
     * way it can put a component on partway through. Every one of the hundred and eight uses in
     * Mojang's own behaviour pack names a component that lives in a group, so a filter reading the
     * declared list alone answers no to all of them and every branch behind one takes the wrong
     * road: a creeper never knows it is charged and a sheep treats itself as grown.
     */
    @GameTest()
    public void hasComponentSeesWhatAGroupBroughtIn(GameTestHelper helper) {
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
        helper.assertTrue(spawned instanceof AddonEntity, ENTITY + " is not an add-on entity.");
        AddonEntity ember = (AddonEntity) spawned;

        EntityEvents.trigger(ember, "tp:ember_look_for_saddle", null, ServerHooks.state());
        helper.assertTrue(!Boolean.TRUE.equals(ember.value("tp:saddle_seen")),
            ENTITY + " passed a has_component filter for a component no group of its had brought in.");

        EntityEvents.trigger(ember, "tp:ember_saddle", null, ServerHooks.state());
        EntityEvents.trigger(ember, "tp:ember_look_for_saddle", null, ServerHooks.state());
        helper.assertTrue(Boolean.TRUE.equals(ember.value("tp:saddle_seen")),
            ENTITY + " failed a has_component filter for a component its live group carries.");
        helper.succeed();
    }

    /**
     * The component groups an entity carries survive it being written out and read back.
     *
     * A group is what a pack changed about the entity rather than what it declared, and the set is
     * held in memory, so a chunk going away and coming back is where it would be lost. Each group is
     * stored as a property of its own, which is what reading the set again is built from. Without
     * that, a creeper struck by lightning is uncharged the next time its chunk loads and a mount
     * loses the saddle it was given.
     */
    @GameTest()
    public void theLiveComponentGroupsSurviveTheEntityBeingReadBack(GameTestHelper helper) {
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
        helper.assertTrue(spawned instanceof AddonEntity, ENTITY + " is not an add-on entity.");
        AddonEntity ember = (AddonEntity) spawned;

        EntityEvents.trigger(ember, "tp:ember_saddle", null, ServerHooks.state());
        // What a chunk load leaves behind: the stored properties, and a set built from nothing.
        ember.activeGroups().clear();

        ember.restoreGroups();
        helper.assertTrue(ember.declaresNow("minecraft:is_saddled"),
            ENTITY + " lost the component group it was given when its live set was built again.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:ageable` makes the entity young.
     *
     * Only a young entity grows up, so Bedrock states the count inside the group that makes it young
     * rather than on the entity: of the thirty-five vanilla entities that age, one writes it at the
     * top level. Reading the top level alone leaves a pack's baby with no count at all, which draws
     * it at the grown proportions and leaves it that size for good.
     */
    @GameTest()
    public void aGroupCarryingTheAgeCountMakesTheEntityYoung(GameTestHelper helper) {
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
        helper.assertTrue(spawned instanceof AddonEntity, ENTITY + " is not an add-on entity.");
        AddonEntity ember = (AddonEntity) spawned;

        helper.assertTrue(!ember.isBaby(), ENTITY + " reads as young before any group says it is.");

        EntityEvents.trigger(ember, "tp:ember_grow_young", null, ServerHooks.state());
        helper.assertTrue(ember.isBaby(),
            ENTITY + " gained minecraft:ageable from a group and still reads as grown.");
        // The component groups stay on the server, so this property is the client's only way of
        // knowing, and `query.is_baby` is what a render controller picks the proportions by.
        helper.assertTrue(Boolean.TRUE.equals(ember.value("is_baby")),
            ENTITY + " is young and never told the clients drawing it.");
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
     *
     * Two of the trades name a Bedrock id that stands for a family: one sells a bed out of the last
     * four of the sixteen, which is a colour the trade lands on only if the roll is what picked it,
     * and one sells the wool a data value glued onto its id names.
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
        List<String> sold = results.stream().map(stack -> net.minecraft.core.registries
            .BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()).toList();
        helper.assertTrue(sold.contains("minecraft:lime_wool"),
            ENTITY + " sells the wool a data value names and handed over " + sold + ".");
        helper.assertTrue(sold.stream().anyMatch(LATE_BEDS::contains),
            ENTITY + " sells a bed out of the last four colours and handed over " + sold + ".");

        helper.assertTrue(!book.isEmpty(), ENTITY + " offers no enchanted book.");
        helper.assertTrue(Platform.storedEnchantmentCount(book) > 0,
            "the book " + ENTITY + " sells carries no enchantment.");
        helper.assertTrue(paidForBook >= BOOK_FLOOR,
            "the book " + ENTITY + " sells costs " + paidForBook + ", under the priced floor.");
        helper.succeed();
    }

    /**
     * A behaviour pack's own animation script drives the entity it is declared on.
     *
     * All three kinds of statement are in the one path: the controller's entry assigns a Molang
     * variable, raises one of the entity's own events and runs a command as the entity, and the clip
     * that state plays raises a second event off its timeline. Each leaves a mark a world can be
     * asked about, so a run that reaches all of them proves the whole script ran rather than parsed.
     */
    @GameTest()
    public void aBehaviourPacksOwnClipsDriveTheEntity(GameTestHelper helper) {
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

        for (int tick = 0; tick < KINDLE_TICKS; tick++) {
            spawned.tick();
        }

        helper.assertTrue(Boolean.TRUE.equals(
                ServerHooks.state().property(spawned.getUUID(), "tp:kindled")),
            ENTITY + " never ran the event its controller raises as a state is entered.");
        helper.assertTrue(Boolean.TRUE.equals(
                ServerHooks.state().property(spawned.getUUID(), "tp:glowing")),
            ENTITY + " never ran the event on its clip's own timeline.");
        helper.assertTrue(Platform.tagsOf(spawned).contains("tp_kindled"),
            ENTITY + " never ran the command its controller state names.");
        helper.succeed();
    }

    /** Past the half second the pack's clip runs for, so its timeline has been crossed. */
    private static final int KINDLE_TICKS = 20;

    /** The base cost and one level of the per-level cost the pack's book trade states. */
    private static final int BOOK_FLOOR = 5;

    /** The colours the range the pack's bed trade rolls over covers, which white is not among. */
    private static final List<String> LATE_BEDS = List.of("minecraft:brown_bed", "minecraft:green_bed",
        "minecraft:red_bed", "minecraft:black_bed");

    /** Longer than the second the synthetic pack's bow takes to draw fully. */
    private static final int FULL_DRAW = 25;

    /** The item the synthetic pack declares a bow. */
    private static final String SHOOTER_ITEM = "tp:wand";

    /** The item the synthetic pack asks to keep on the ground. */
    private static final String KEPT_ITEM = "tp:dust";

    /** The item the synthetic pack declares dyeable, and the colour it declares it. */
    private static final String DYED_ITEM = "tp:shard";
    private static final int DYED_COLOUR = 0x4C8FD1;

    /**
     * A group carrying `minecraft:scale` changes how big the entity is.
     *
     * The entity type was built at whatever size the top level asked for, and that cannot move once
     * the registries are frozen, so the scale a group brings has to reach the entity itself. Every
     * one of the forty-two vanilla entities that scales in a group does it in the group that makes
     * it young, and none of them carries `minecraft:scale_by_age`, so a read of the definition alone
     * draws a pack's baby at its grown size and gives it a grown entity's box to collide in.
     */
    @GameTest()
    public void aGroupCarryingAScaleResizesTheEntity(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        float grown = ember.getBbWidth();
        EntityEvents.trigger(ember, "tp:ember_shrink", null, ServerHooks.state());
        helper.assertTrue(ember.getBbWidth() < grown * SHRUNK_BELOW,
            ENTITY + " gained minecraft:scale from a group and is still its grown size.");

        EntityEvents.trigger(ember, "tp:ember_unshrink", null, ServerHooks.state());
        helper.assertTrue(ember.getBbWidth() >= grown,
            ENTITY + " lost the group carrying minecraft:scale and stayed small.");
        helper.succeed();
    }

    /**
     * A group brings the entity the attributes it carries, and taking it away takes them back.
     *
     * Bedrock switches the damage with the difficulty and with whatever mood the entity is in, so
     * eighteen vanilla entities write `minecraft:attack` into a group and three write
     * `minecraft:knockback_resistance` into one. Java fixes both as attributes on the type, which
     * are read once and frozen, so a number a group brings only arrives if the entity is given it:
     * without that a bee is as gentle angry as it is calm. `minecraft:persistent` is the same shape
     * and one way only, because Java cannot tell a mob to start despawning again.
     */
    @GameTest()
    public void aGroupBringsTheAttributesItCarries(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        double calm = ember.getAttributeValue(Attributes.ATTACK_DAMAGE);
        helper.assertTrue(ember.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) == 0,
            ENTITY + " resists knockback before any group said it should.");
        helper.assertTrue(!ember.isPersistenceRequired(),
            ENTITY + " refuses to despawn before any group said it should.");

        EntityEvents.trigger(ember, "tp:ember_enrage", null, ServerHooks.state());
        helper.assertTrue(ember.getAttributeValue(Attributes.ATTACK_DAMAGE) > calm,
            ENTITY + " gained minecraft:attack from a group and hits no harder.");
        helper.assertTrue(ember.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) > 0,
            ENTITY + " gained minecraft:knockback_resistance from a group and is pushed as easily.");
        helper.assertTrue(ember.isPersistenceRequired(),
            ENTITY + " gained minecraft:persistent from a group and can still despawn.");

        EntityEvents.trigger(ember, "tp:ember_soothe", null, ServerHooks.state());
        helper.assertTrue(ember.getAttributeValue(Attributes.ATTACK_DAMAGE) == calm,
            ENTITY + " lost the group carrying minecraft:attack and kept hitting that hard.");
        helper.assertTrue(ember.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) == 0,
            ENTITY + " lost the group carrying minecraft:knockback_resistance and still resists.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:experience_reward` decides what killing the entity is worth.
     *
     * Bedrock puts the reward in the group that makes an entity grown, a baby being worth nothing,
     * and writes it as Molang: eighty-six of the eighty-seven `on_death` rewards in Mojang's own
     * behaviour pack ask whether a player struck the last blow rather than naming a number.
     */
    @GameTest()
    public void aGroupCarryingTheExperienceRewardDecidesWhatAKillIsWorth(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(ember, "tp:ember_mature", null, ServerHooks.state());
        Player killer = Platform.mockPlayer(helper);
        Platform.placeAt(killer, ember.getX(), ember.getY(), ember.getZ(), 0f, 0f);
        Platform.hurt(ember, helper.getLevel(),
            helper.getLevel().damageSources().playerAttack(killer), KILLING_BLOW);

        helper.assertTrue(!ember.isAlive(), ENTITY + " survived a killing blow.");
        int awarded = helper.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.ExperienceOrb.class, ember.getBoundingBox().inflate(8))
            .stream().mapToInt(net.minecraft.world.entity.ExperienceOrb::getValue).sum();
        helper.assertTrue(awarded >= GROWN_REWARD,
            ENTITY + " was killed carrying a group worth " + GROWN_REWARD
                + " and left " + awarded + " experience.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:angry` makes the entity angry, and its own event calms it again.
     *
     * All twenty uses in Mojang's own behaviour pack are inside a group and none is at the top,
     * because being angry is a state something put the entity into. The calm event is what takes the
     * group away, so an entity read from the definition is never angry and one whose anger nothing
     * counts stays angry for as long as it lives.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void aGroupCarryingAngerCalmsDownAgainOnItsOwn(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(!ember.isAngry(), ENTITY + " reads as angry before anything provoked it.");
        EntityEvents.trigger(ember, "tp:ember_provoke", null, ServerHooks.state());
        helper.assertTrue(ember.isAngry(),
            ENTITY + " gained minecraft:angry from a group and still reads as calm.");
        helper.assertTrue(Boolean.TRUE.equals(ember.value("is_angry")),
            ENTITY + " is angry and never told the clients drawing it.");

        helper.runAfterDelay(CALM_TICKS, () -> {
            helper.assertTrue(!ember.isAngry(),
                ENTITY + " stayed angry past the duration minecraft:angry names.");
            helper.assertTrue(Boolean.TRUE.equals(ember.value("tp:calmed")),
                ENTITY + " never raised the calm event minecraft:angry names.");
            helper.succeed();
        });
    }

    /**
     * A group is what arms a sensor, and an entity carrying no such group runs none.
     *
     * `minecraft:timer` and `minecraft:environment_sensor` are both how a pack arms a reaction it
     * wants for a while, so eighteen of the nineteen vanilla timers and seventeen environment
     * sensors are written into a group. A sensor read off the definition never runs for either one.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void aGroupIsWhatArmsASensor(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(SENSOR_TICKS, () -> {
            helper.assertTrue(!Boolean.TRUE.equals(ember.value("tp:sensed")),
                ENTITY + " ran an environment sensor no group of its had brought in.");
            helper.assertTrue(!Boolean.TRUE.equals(ember.value("tp:timed")),
                ENTITY + " ran a timer no group of its had brought in.");

            EntityEvents.trigger(ember, "tp:ember_alert_on", null, ServerHooks.state());
            helper.runAfterDelay(SENSOR_TICKS, () -> {
                helper.assertTrue(Boolean.TRUE.equals(ember.value("tp:sensed")),
                    ENTITY + " never ran the environment sensor its live group carries.");
                helper.assertTrue(Boolean.TRUE.equals(ember.value("tp:timed")),
                    ENTITY + " never ran the timer its live group carries.");
                helper.succeed();
            });
        });
    }

    /**
     * A timer armed a second time waits afresh rather than firing on the deadline before it.
     *
     * The count belongs to the entity and the component naming it belongs to the group, so a group
     * taken away partway through a wait leaves a deadline the entity walks past while nothing is
     * watching. Firing on that one costs the pack its whole wait every time the group comes back.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void aTimerArmedAgainStartsItsWaitAfresh(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(ember, "tp:ember_alert_on", null, ServerHooks.state());
        helper.runAfterDelay(PART_WAY, () -> {
            helper.assertTrue(!Boolean.TRUE.equals(ember.value("tp:timed")),
                ENTITY + " ran its timer before the wait the pack asked for was up.");
            EntityEvents.trigger(ember, "tp:ember_alert_off", null, ServerHooks.state());

            // Long enough for the deadline the first wait set to go by with the group away.
            helper.runAfterDelay(SENSOR_TICKS, () -> {
                EntityEvents.trigger(ember, "tp:ember_alert_on", null, ServerHooks.state());
                helper.runAfterDelay(PART_WAY, () -> {
                    helper.assertTrue(!Boolean.TRUE.equals(ember.value("tp:timed")),
                        ENTITY + " armed a timer again and it fired on the deadline before it.");
                    helper.succeed();
                });
            });
        });
    }

    /**
     * A group carrying `minecraft:collision_box` moves the width and the height apart from each other.
     *
     * A scale multiplies both axes together, so this is the only way a pack can say its entity
     * changes shape rather than size, and it is what a shell opening or a mount taking on a load is
     * described as. The entity type was built from the top level and is frozen with the registries,
     * so a box read off the definition leaves the entity colliding in whatever it was born with:
     * a wide one walks through the gap it should be stopped by and a short one is hit above its head.
     */
    @GameTest()
    public void aGroupCarryingACollisionBoxMovesBothAxesApart(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        float born = ember.getBbWidth();
        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        helper.assertTrue(ember.getBbWidth() > born,
            ENTITY + " gained a wider minecraft:collision_box from a group and did not widen.");
        helper.assertTrue(ember.getBbHeight() < ember.getBbWidth(),
            ENTITY + " gained a box wider than it is tall and is still as tall as it is wide.");

        EntityEvents.trigger(ember, "tp:ember_unload", null, ServerHooks.state());
        helper.assertTrue(ember.getBbWidth() == born,
            ENTITY + " lost the group carrying minecraft:collision_box and kept the box it brought.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:ambient_sound_interval` decides the gap between idle sounds.
     *
     * Bedrock states the gap in seconds and puts it in the group that gives the entity the voice: a
     * mob says nothing until the state that makes it say something is on it.
     */
    @GameTest()
    public void aGroupCarryingTheIdleSoundIntervalSetsTheGap(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        int silent = ember.getAmbientSoundInterval();
        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        helper.assertTrue(ember.getAmbientSoundInterval() == GROUPED_SOUND_GAP,
            ENTITY + " gained minecraft:ambient_sound_interval from a group and kept the gap "
                + silent + ".");
        helper.succeed();
    }

    /**
     * A group saying the entity may not be stolen refuses a lead.
     *
     * `minecraft:leashable` is written into a group as often as at the top, because being tied is a
     * state a pack puts the entity into, and `can_be_stolen` is the half of it that says whether
     * somebody else may take the other end.
     */
    @GameTest()
    public void aGroupRefusingTheEntityToBeStolenRefusesTheLead(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(ember.canBeLeashed(), ENTITY + " refuses a lead before any group says so.");
        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        helper.assertFalse(ember.canBeLeashed(),
            ENTITY + " gained a minecraft:leashable refusing to be stolen and accepts a lead.");
        helper.succeed();
    }

    /**
     * The flags a group brings reach the clients drawing the entity.
     *
     * `minecraft:can_power_jump` and `minecraft:color` are each read back through Molang rather than
     * held anywhere by Java, so the property is the whole of what either is worth on this side. Both
     * arrive with the group that saddles or dyes the entity, so a flag published once as the entity
     * spawned answers for a pack that never writes either at the top.
     */
    @GameTest()
    public void aGroupCarryingTheFlagsAPackReadsBackPublishesThem(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertFalse(Boolean.TRUE.equals(ember.value("can_power_jump")),
            ENTITY + " reads as able to power jump before any group said it could.");

        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        helper.assertTrue(Boolean.TRUE.equals(ember.value("can_power_jump")),
            ENTITY + " gained minecraft:can_power_jump from a group and never told the clients.");
        helper.assertTrue(ember.value("color") instanceof Number dye
                && dye.intValue() == GROUPED_DYE,
            ENTITY + " gained minecraft:color from a group and published " + ember.value("color") + ".");

        EntityEvents.trigger(ember, "tp:ember_unload", null, ServerHooks.state());
        helper.assertFalse(Boolean.TRUE.equals(ember.value("can_power_jump")),
            ENTITY + " lost the group carrying minecraft:can_power_jump and still reads as able.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:dweller` holds the entity to where it was standing.
     *
     * Bedrock has no other place for it: a villager is a dweller of the village it took a job in, so
     * the component arrives with the group describing the job. Java keeps the place on the mob, so a
     * read of the definition leaves a pack's villager free to walk out of its own village.
     */
    @GameTest()
    public void aGroupCarryingADwellerHoldsTheEntityWhereItStands(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertFalse(Platform.hasHome(ember),
            ENTITY + " is held somewhere before any group said it belonged there.");

        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        helper.assertTrue(Platform.hasHome(ember),
            ENTITY + " gained minecraft:dweller from a group and wanders as freely as before.");

        EntityEvents.trigger(ember, "tp:ember_unload", null, ServerHooks.state());
        helper.assertFalse(Platform.hasHome(ember),
            ENTITY + " lost the group carrying minecraft:dweller and is still held to the place.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:input_ground_controlled` hands the rider the reins.
     *
     * Being steerable is what a saddle brings, so Bedrock writes it into the group the saddle switches
     * on: eight of the nine vanilla uses are in a group. Java asks the vehicle which passenger is
     * driving, so a definition read leaves a pack's saddled mount walking wherever it likes with a
     * player on its back.
     */
    @GameTest()
    public void aGroupSteeringTheEntityHandsItsRiderTheReins(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        Player rider = Platform.mockPlayer(helper);
        Platform.placeAt(rider, ember.getX(), ember.getY(), ember.getZ(), 0f, 0f);
        rider.startRiding(ember);
        helper.assertTrue(ember.getPassengers().contains(rider), ENTITY + " could not be ridden.");
        helper.assertTrue(ember.getControllingPassenger() != rider,
            ENTITY + " is steered by its rider before any group said it could be.");

        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        helper.assertTrue(ember.getControllingPassenger() == rider,
            ENTITY + " gained minecraft:input_ground_controlled from a group and steers itself.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:sittable` lets a player sit the entity down.
     *
     * Sitting is a state rather than a fact, which is why Bedrock puts the component in a group: a
     * pet sits once it belongs to somebody. The click is the whole of it, so a definition read leaves
     * a tamed pet that cannot be told to stay.
     */
    @GameTest()
    public void aGroupCarryingASittableSitsTheEntityDown(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        Player owner = Platform.mockPlayer(helper);
        Platform.placeAt(owner, ember.getX(), ember.getY(), ember.getZ(), 0f, 0f);
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        ember.mobInteract(owner, InteractionHand.MAIN_HAND);
        helper.assertFalse(ember.getPose() == net.minecraft.world.entity.Pose.SITTING,
            ENTITY + " sat down before any group said it was sittable.");

        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        ember.mobInteract(owner, InteractionHand.MAIN_HAND);
        helper.assertTrue(ember.getPose() == net.minecraft.world.entity.Pose.SITTING,
            ENTITY + " gained minecraft:sittable from a group and stayed on its feet.");
        helper.assertTrue(Boolean.TRUE.equals(ember.value("tp:sat")),
            ENTITY + " sat down and never raised the event minecraft:sittable names.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:damage_sensor` answers for the blow the entity takes.
     *
     * A pack arms one for as long as the state it belongs to lasts, a mob shrugging off fire only
     * while it is standing in its own element, so thirty-four of the thirty-nine vanilla sensors that
     * are not at the top are in a group. The sensor decides both whether the damage lands and what
     * the entity does about it, so one read off the definition is a whole reaction that never runs.
     */
    @GameTest()
    public void aGroupCarryingADamageSensorAnswersForTheBlow(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(ember, "tp:ember_ward_on", null, ServerHooks.state());
        float whole = ember.getHealth();
        Platform.hurt(ember, helper.getLevel(), helper.getLevel().damageSources().generic(), SCRATCH);

        helper.assertTrue(Boolean.TRUE.equals(ember.value("tp:scratched")),
            ENTITY + " was hurt carrying a minecraft:damage_sensor and never ran its event.");
        helper.assertTrue(ember.getHealth() == whole,
            ENTITY + " carries a sensor refusing the damage and lost health anyway.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:explode` names the fuse and goes off where the entity fell.
     *
     * Being primed is the state Bedrock describes with this, so the component arrives with the group
     * a creeper enters when something walks into it. A pack's own controllers count the fuse down
     * over the entity's head, and the blast is what the component is for, so a definition read leaves
     * both out for every pack that primes its entity the way Bedrock says to.
     */
    @GameTest()
    public void aGroupCarryingAnExplosionNamesItsFuseAndGoesOff(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        AddonEntity bystander = spawn(helper, ENTITY);
        if (ember == null || bystander == null) {
            helper.succeed();
            return;
        }

        Platform.placeAt(bystander, ember.getX() + 1, ember.getY(), ember.getZ(), 0f, 0f);
        helper.assertTrue(ember.value("fuse_time") == null,
            ENTITY + " names a fuse before any group primed it.");

        EntityEvents.trigger(ember, "tp:ember_prime", null, ServerHooks.state());
        helper.assertTrue(ember.value("fuse_time") instanceof Number fuse
                && fuse.doubleValue() == PRIMED_FUSE,
            ENTITY + " was primed by a group and names the fuse " + ember.value("fuse_time") + ".");

        float whole = bystander.getHealth();
        Platform.hurt(ember, helper.getLevel(),
            helper.getLevel().damageSources().generic(), KILLING_BLOW);
        helper.assertTrue(bystander.getHealth() < whole || !bystander.isAlive(),
            ENTITY + " died carrying minecraft:explode from a group and nothing beside it was hurt.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:inventory` gives the entity somewhere to put what it picks up.
     *
     * Both halves of hauling arrive with the group that gives the entity the job: the slots, and the
     * `minecraft:shareables` list saying what may go in them. Without the slots there is one hand, so
     * a definition read leaves a pack's pack animal holding one item and refusing the rest, and a
     * shareables list read there lets it pick up whatever it treads on.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void aGroupCarryingAnInventoryStowsWhatTheEntityPicksUp(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        ItemEntity wanted = drop(helper, ember, net.minecraft.world.item.Items.GOLD_INGOT);
        ItemEntity refused = drop(helper, ember, net.minecraft.world.item.Items.IRON_INGOT);

        helper.runAfterDelay(PICKED_UP_BY, () -> {
            helper.assertFalse(wanted.isAlive(),
                ENTITY + " left the item its minecraft:shareables list names lying there.");
            helper.assertTrue(ember.getMainHandItem().isEmpty(),
                ENTITY + " gained minecraft:inventory from a group and held what it picked up.");
            helper.assertTrue(refused.isAlive(),
                ENTITY + " picked up an item its minecraft:shareables list leaves out.");
            helper.succeed();
        });
    }

    /**
     * A group carrying `minecraft:navigation.*` and `minecraft:movement.*` puts the entity in the air.
     *
     * Java builds both the navigator and the move control inside the mob constructor and keeps each
     * as a field, so an entity born walking keeps a ground navigator and a control that pulls it back
     * down however many groups arrive afterwards. Bedrock states how a mob gets about as freely
     * inside a group as at the top, because being airborne is something a mob becomes.
     */
    @GameTest()
    public void aGroupCarryingTheNavigationPutsTheEntityInTheAir(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(!(ember.getNavigation() instanceof FlyingPathNavigation),
            ENTITY + " flies before any group put it in the air.");
        helper.assertTrue(!(ember.getMoveControl() instanceof FlyingMoveControl),
            ENTITY + " hovers before any group put it in the air.");

        EntityEvents.trigger(ember, "tp:ember_take_off", null, ServerHooks.state());
        helper.assertTrue(ember.getNavigation() instanceof FlyingPathNavigation,
            ENTITY + " gained minecraft:navigation.float from a group and still paths along the ground.");
        helper.assertTrue(ember.getMoveControl() instanceof FlyingMoveControl,
            ENTITY + " gained minecraft:movement.hover from a group and is still pulled to the ground.");

        EntityEvents.trigger(ember, "tp:ember_land", null, ServerHooks.state());
        helper.assertTrue(!(ember.getNavigation() instanceof FlyingPathNavigation),
            ENTITY + " lost the group that put it in the air and kept the flying navigator.");
        helper.assertTrue(!(ember.getMoveControl() instanceof FlyingMoveControl),
            ENTITY + " lost the group that put it in the air and goes on hovering.");
        helper.succeed();
    }

    /**
     * A group saying the entity climbs gives it a navigator that goes up walls, and says so.
     *
     * `minecraft:can_climb` decides the navigator and is also one of the flags a pack's own render
     * controllers read back off the entity, so both halves move with the group that granted it.
     */
    @GameTest()
    public void aGroupSayingTheEntityClimbsGivesItAClimbersNavigator(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(!(ember.getNavigation() instanceof WallClimberNavigation),
            ENTITY + " climbs walls before any group said it could.");
        helper.assertTrue(Boolean.FALSE.equals(ember.value("can_climb")),
            ENTITY + " reads as a climber before any group said it was one.");

        EntityEvents.trigger(ember, "tp:ember_cling", null, ServerHooks.state());
        helper.assertTrue(ember.getNavigation() instanceof WallClimberNavigation,
            ENTITY + " gained minecraft:can_climb from a group and still paths along the ground.");
        helper.assertTrue(Boolean.TRUE.equals(ember.value("can_climb")),
            ENTITY + " gained minecraft:can_climb from a group and reads as no climber.");

        EntityEvents.trigger(ember, "tp:ember_let_go", null, ServerHooks.state());
        helper.assertTrue(!(ember.getNavigation() instanceof WallClimberNavigation),
            ENTITY + " lost the group carrying minecraft:can_climb and goes on climbing.");
        helper.succeed();
    }

    /**
     * A group refusing collision takes the entity out of everything else's way.
     *
     * Bedrock asks two questions where Java asks one: `minecraft:is_collidable` says whether anything
     * collides with the entity at all and `minecraft:pushable` says whether a collision moves it.
     * Both arrive with the group that makes an entity something to walk through.
     */
    @GameTest()
    public void aGroupRefusingCollisionMakesTheEntityUnpushable(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(ember.isPushable(),
            ENTITY + " stands its ground before any group said nothing could push it.");

        EntityEvents.trigger(ember, "tp:ember_fade", null, ServerHooks.state());
        helper.assertTrue(!ember.isPushable(),
            ENTITY + " gained minecraft:is_collidable from a group and is still pushed about.");
        helper.succeed();
    }

    /**
     * A group raising `minecraft:variable_max_auto_step` lets the entity climb a taller step.
     *
     * Java holds the height on the entity, as an attribute from 1.21 and as a field before it, so it
     * can move while the entity lives. The group that raises it is the one that puts a rider on a
     * mount or a mount on a road.
     */
    @GameTest()
    public void aGroupRaisingTheStepHeightLetsTheEntityClimbHigher(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        float born = Platform.stepHeight(ember);
        EntityEvents.trigger(ember, "tp:ember_shoe", null, ServerHooks.state());
        helper.assertTrue(Platform.stepHeight(ember) > born,
            ENTITY + " gained minecraft:variable_max_auto_step from a group and steps no higher.");

        EntityEvents.trigger(ember, "tp:ember_unshoe", null, ServerHooks.state());
        helper.assertTrue(Platform.stepHeight(ember) == born,
            ENTITY + " lost the group raising its step and kept the taller one.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:preferred_path` prices water for the entity's pathfinder.
     *
     * A pathfinding mob starts out costing water nothing, which is what lets it wade across rather
     * than walk around. The component is the entity keeping to the ground it was given.
     */
    @GameTest()
    public void aGroupCarryingThePreferredPathPricesWater(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(Platform.waterMalus(ember) == 0,
            ENTITY + " avoids water before any group said it kept to a path.");

        EntityEvents.trigger(ember, "tp:ember_shoe", null, ServerHooks.state());
        helper.assertTrue(Platform.waterMalus(ember) > 0,
            ENTITY + " gained minecraft:preferred_path from a group and wades as freely as before.");

        EntityEvents.trigger(ember, "tp:ember_unshoe", null, ServerHooks.state());
        helper.assertTrue(Platform.waterMalus(ember) == 0,
            ENTITY + " lost the group carrying minecraft:preferred_path and still avoids water.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:mob_effect_immunity` turns the effects it names away.
     *
     * Java asks the entity every time something would apply an effect, so the list can move while
     * the entity lives, and the group that carries it is the one that makes the entity proof
     * against something it was not proof against a moment ago.
     */
    @GameTest()
    public void aGroupCarryingAnEffectImmunityTurnsThatEffectAway(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        Identifier poison = Platform.id("minecraft", "poison");
        helper.assertTrue(Platform.addEffect(ember, poison, IMMUNITY_TICKS, 0, false),
            ENTITY + " refused poison before any group said it was immune.");
        Platform.removeEffect(ember, poison);

        EntityEvents.trigger(ember, "tp:ember_shoe", null, ServerHooks.state());
        helper.assertTrue(!Platform.addEffect(ember, poison, IMMUNITY_TICKS, 0, false),
            ENTITY + " gained minecraft:mob_effect_immunity from a group and took the effect anyway.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:trade_table` changes what is on the counter.
     *
     * Bedrock states the table inside the group that gives a trader its stock, which is how a
     * villager's profession decides what it sells, so which file is offered has to move with the
     * group. Every table any declaration names is read while the pack is open, because a file
     * cannot be opened afterwards.
     */
    @GameTest()
    public void aGroupCarryingATradeTableChangesWhatIsOnOffer(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(ember.getOffers().size() > RESTOCKED_OFFERS,
            ENTITY + " offers nothing out of the table written at the top level.");

        EntityEvents.trigger(ember, "tp:ember_stock", null, ServerHooks.state());
        helper.assertTrue(ember.getOffers().size() == RESTOCKED_OFFERS,
            ENTITY + " gained a minecraft:trade_table from a group and kept the offers it was born with.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:fire_immune` keeps the flames off while it is on.
     *
     * Java asks the entity rather than its type whenever something would burn it, so the answer can
     * move, and Bedrock writes the component in a group for the entities that are only sometimes
     * safe in fire.
     */
    @GameTest()
    public void aGroupCarryingFireImmunityKeepsTheFlamesOff(GameTestHelper helper) {
        AddonEntity sprite = spawn(helper, YOUNG_ENTITY);
        if (sprite == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(!sprite.fireImmune(),
            YOUNG_ENTITY + " shrugs off fire before any group said it should.");

        EntityEvents.trigger(sprite, "tp:temper", null, ServerHooks.state());
        helper.assertTrue(sprite.fireImmune(),
            YOUNG_ENTITY + " gained minecraft:fire_immune from a group and still burns.");

        EntityEvents.trigger(sprite, "tp:cool", null, ServerHooks.state());
        helper.assertTrue(!sprite.fireImmune(),
            YOUNG_ENTITY + " lost the group carrying minecraft:fire_immune and stayed safe.");
        helper.succeed();
    }

    /**
     * A group carrying `minecraft:item_hopper` draws from whatever container stands over the entity.
     *
     * A Bedrock hopper minecart is described this way, and what it hauls arrives with the group that
     * gives the entity somewhere to put it. Everything about the pull is asked of the entity on the
     * sensor's own pass, so nothing happens until a group has brought both halves in.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void aGroupCarryingAHopperDrawsFromWhatIsOverhead(GameTestHelper helper) {
        AddonEntity ember = spawn(helper, ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        BlockPos overhead = STAND.above();
        helper.setBlock(overhead, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(overhead))
            instanceof net.minecraft.world.Container above)) {
            helper.fail("No container stood over " + ENTITY + ".");
            return;
        }

        above.setItem(0, new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT));
        EntityEvents.trigger(ember, "tp:ember_load", null, ServerHooks.state());
        helper.runAfterDelay(PICKED_UP_BY, () -> {
            helper.assertTrue(above.getItem(0).isEmpty(),
                ENTITY + " gained minecraft:item_hopper from a group and drew nothing from overhead.");
            helper.succeed();
        });
    }

    /** Long enough that an effect which did apply is still standing on the next line. */
    private static final int IMMUNITY_TICKS = 200;

    /** The one trade the table the pack's stocked group names holds. */
    private static final int RESTOCKED_OFFERS = 1;

    /** An item lying where the entity stands, ready to be picked up on the next pass. */
    private static ItemEntity drop(GameTestHelper helper, AddonEntity beside, Item item) {
        ItemEntity dropped = new ItemEntity(helper.getLevel(),
            beside.getX(), beside.getY(), beside.getZ(), new ItemStack(item));
        dropped.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(dropped);
        return dropped;
    }

    /** The gap in ticks the pack's laden group states, which it writes in seconds. */
    private static final int GROUPED_SOUND_GAP = 60;

    /** The dye the pack's laden group names. */
    private static final int GROUPED_DYE = 11;

    /** The fuse in seconds the pack's primed group names. */
    private static final double PRIMED_FUSE = 2.5;

    /** Enough to show on the health bar, and well under the ember's four. */
    private static final float SCRATCH = 2f;

    /** Two passes of the sensor interval the pickup runs on, so one of them has been and gone. */
    private static final int PICKED_UP_BY = 25;

    /** One of the pack's own entities, in the world and ready to assert about. */
    private static AddonEntity spawn(GameTestHelper helper, String identifier) {
        EntityType<?> type = AddonEntities.typeOf(identifier);
        if (type == null) return null;

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);
        helper.assertTrue(spawned instanceof AddonEntity, identifier + " is not an add-on entity.");
        // Still, so a test the server ticks keeps its subject inside the structure.
        ((AddonEntity) spawned).setNoAi(true);
        return (AddonEntity) spawned;
    }

    /** Under the half the pack's own group scales the ember to, clear of rounding. */
    private static final float SHRUNK_BELOW = 0.75f;

    /** More than the ember's four health, so one blow is the whole of it. */
    private static final float KILLING_BLOW = 40f;

    /** What the pack's grown group is worth to a player who struck the last blow. */
    private static final int GROWN_REWARD = 7;

    /** Past the quarter second the pack's anger runs for, plus the sensor pass that notices. */
    private static final int CALM_TICKS = 30;

    /** Past the second the pack's timer waits, plus the pass that arms it. */
    private static final int SENSOR_TICKS = 40;

    /** Under the second the pack's timer waits, so one that started afresh has not reached it. */
    private static final int PART_WAY = 15;
}
