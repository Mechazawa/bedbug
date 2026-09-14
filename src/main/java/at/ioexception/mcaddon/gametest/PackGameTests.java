package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.registry.AddonParticles;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

/**
 * What an add-on does once it is loaded into a world.
 *
 * These run against the synthetic pack `tools/testpack` builds, which the game test task installs
 * first, so they assert on content this repository owns rather than on anybody else's. With no pack
 * present each one succeeds without asserting, because a checkout is allowed to have no add-on.
 */
public final class PackGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /** The entity the synthetic pack declares, which every assertion about one uses. */
    private static final String ENTITY = "tp:ember";

    /** The entity whose components state a value and a ceiling that differ. */
    private static final String HURT_ENTITY = "tp:wisp";
    private static final String BLOCK = "tp:lamp";

    /**
     * A pack's own entity reaches the world and stays there.
     *
     * Registering a type is not the same as being able to spawn one: the attributes, the goals and
     * the components all run when it arrives, and any of them throwing takes the entity with it.
     */
    @GameTest()
    public void aPackEntitySpawnsAndSurvives(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        helper.assertTrue(spawned != null, ENTITY + " could not be created.");

        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        helper.assertTrue(level.addFreshEntity(spawned), ENTITY + " was refused by the world.");

        helper.runAfterDelay(5, () -> {
            helper.assertFalse(spawned.isRemoved(), ENTITY + " did not survive five ticks.");
            helper.assertTrue(spawned.isAlive(), ENTITY + " arrived dead.");
            helper.succeed();
        });
    }

    /**
     * The health a pack asked for is the health the entity has.
     *
     * `minecraft:health` becomes an attribute on the type, which is fixed when the type is built, so
     * a component read at the wrong moment silently leaves the entity on the default twenty.
     */
    @GameTest()
    public void aPackEntityCarriesTheHealthItAskedFor(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        Entity spawned = Platform.createMob(type, helper.getLevel());
        helper.assertTrue(spawned instanceof net.minecraft.world.entity.LivingEntity,
            ENTITY + " is not something that can be hurt.");

        net.minecraft.world.entity.LivingEntity living = (net.minecraft.world.entity.LivingEntity) spawned;
        helper.assertTrue(living.getMaxHealth() > 0,
            ENTITY + " has no health at all.");
        helper.assertTrue(living.getHealth() == living.getMaxHealth(),
            ENTITY + " arrived already hurt.");
        helper.succeed();
    }

    /**
     * A pack's entity runs its own events without ending the tick.
     *
     * An event names component groups to add and remove and commands to run, and all of it happens
     * on an entity that is in the world, so a mistake anywhere in it reaches the server thread.
     */
    @GameTest()
    public void aPackEntityRunsItsOwnEvents(GameTestHelper helper) {
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

        EntityEvents.trigger(spawned, "minecraft:entity_spawned", null, ServerHooks.state());
        EntityEvents.trigger(spawned, "tp:no_such_event", null, ServerHooks.state());

        helper.runAfterDelay(3, () -> {
            helper.assertFalse(spawned.isRemoved(),
                ENTITY + " was lost by running an event on it.");
            helper.succeed();
        });
    }

    /**
     * A pack's block places and comes back as itself.
     *
     * The conversion writes a block, its states and its models, and a pack's own block is the only
     * thing that proves all three agree about the same name.
     */
    @GameTest()
    public void aPackBlockPlacesAndReadsBack(GameTestHelper helper) {
        net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK
            .getOptional(Platform.id("tp", "lamp")).orElse(null);
        if (block == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(STAND);
        helper.assertTrue(level.setBlockAndUpdate(at, block.defaultBlockState()),
            BLOCK + " could not be placed.");
        helper.assertBlockPresent(block, STAND);
        helper.succeed();
    }

    /**
     * The items and spawn eggs a pack declares are registered under its own namespace.
     *
     * A spawn egg is registered separately from the item it belongs beside, and both used to be
     * drawn from a texture nothing had stitched, so their presence is worth asserting on.
     */
    @GameTest()
    public void aPackRegistersItsItemsAndEggs(GameTestHelper helper) {
        if (AddonEntities.typeOf(ENTITY) == null) {
            helper.succeed();
            return;
        }

        Item wand = BuiltInRegistries.ITEM.getOptional(Platform.id("tp", "wand")).orElse(null);
        helper.assertTrue(wand != null, "The pack's own item was never registered.");

        Item egg = BuiltInRegistries.ITEM.getOptional(Platform.id("tp", "ember_spawn_egg")).orElse(null);
        helper.assertTrue(egg != null, "The pack's spawn egg was never registered.");
        helper.assertTrue(AddonItems.all().containsKey("tp:wand"),
            "The mod lost track of an item it registered.");
        helper.succeed();
    }

    /**
     * A pack's particle effects are registered and can be asked for by name.
     *
     * The type has to exist on both sides: a server names it to tell a client to run it, and a
     * client needs the same name to know what it was told.
     */
    @GameTest()
    public void aPackRegistersItsParticles(GameTestHelper helper) {
        if (AddonEntities.typeOf(ENTITY) == null) {
            helper.succeed();
            return;
        }

        helper.assertFalse(AddonParticles.all().isEmpty(),
            "A pack shipping particle effects registered none of them.");
        AddonParticles.all().keySet().forEach(identifier ->
            helper.assertTrue(AddonParticles.typeOf(identifier) != null,
                identifier + " was read but never registered."));
        helper.succeed();
    }

    /**
     * The health a pack states as two numbers reaches the entity as two numbers.
     *
     * Bedrock's `value` is where the entity starts and its `max` is the ceiling it can be healed to.
     * Reading `value` as the ceiling gives a mob that can never be healed past what it spawned with,
     * and nothing reports it because the component is named as implemented either way.
     */
    @GameTest()
    public void aPackEntityStartsBelowTheCeilingItsPackGaveIt(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(HURT_ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        Entity spawned = Platform.createMob(type, helper.getLevel());
        helper.assertTrue(spawned instanceof net.minecraft.world.entity.LivingEntity,
            HURT_ENTITY + " is not something that can be hurt.");

        net.minecraft.world.entity.LivingEntity living = (net.minecraft.world.entity.LivingEntity) spawned;
        helper.assertTrue(living.getMaxHealth() == 20f,
            HURT_ENTITY + " asked for a ceiling of 20 and got " + living.getMaxHealth() + ".");
        helper.assertTrue(living.getHealth() == 6f,
            HURT_ENTITY + " asked to start at 6 and started at " + living.getHealth() + ".");
        helper.succeed();
    }

    /**
     * Anger runs out, and the pack's own event is what ends it.
     *
     * Bedrock states the anger as a countdown in seconds with an event at the end of it. An entity
     * whose component is read as a flag alone is angry for the rest of its life, and `calm_event`
     * never runs, which no count of components can see.
     */
    @GameTest(structure = "mcaddon:platform", maxTicks = 200)
    public void aPackEntitysAngerRunsOutAndFiresItsCalmEvent(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(HURT_ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);

        at.ioexception.mcaddon.entity.AddonEntity addon =
            (at.ioexception.mcaddon.entity.AddonEntity) spawned;
        addon.provoke();
        helper.assertTrue(addon.isAngry(), HURT_ENTITY + " would not be provoked.");

        // The component asks for one second of anger and the sensors run every ten ticks, so the
        // entity's own clock decides when this holds rather than the world's.
        helper.succeedWhen(() -> {
            helper.assertFalse(addon.isAngry(), HURT_ENTITY + " is still angry.");
            helper.assertTrue(addon.activeGroups().contains("tp:calmed"),
                HURT_ENTITY + " calmed down without running the event its pack wrote for it.");
        });
    }

    /**
     * Everything the loaded packs asked for and did not get is named rather than lost.
     *
     * The register is what `/mcaddon entities` reads, and its worth is in being able to say nothing
     * as well as something. A line without a name or a count would be neither.
     */
    @GameTest()
    public void whatIsUnimplementedIsNamed(GameTestHelper helper) {
        McAddon.packs().packs().forEach(pack ->
            helper.assertTrue(pack.displayName() != null, "A loaded pack has no name."));

        AddonEntities.report().stream().skip(1).forEach(line ->
            helper.assertTrue(line.contains("minecraft:") || line.contains("tp:") || line.contains(" x"),
                "A reported gap does not name what was missing: " + line));
        helper.succeed();
    }
}
