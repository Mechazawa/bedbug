package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackSettings;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonFogs;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.registry.AddonParticles;
import at.ioexception.mcaddon.resource.AddonPacks;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    private static final String FOG = "tp:dusk";

    /** The two the pack declares for the components a plain mob has no occasion to use. */
    private static final String PROJECTILE = "tp:spark";
    private static final String SLOW_DYING = "tp:warden";

    /** The pack's own entity texture, whose top band is written with Bedrock's cut-out alpha. */
    private static final String MARKED_TEXTURE = "textures/entity/sprite";

    /** The body colour the pack's highest subpack paints that texture in, and no other form does. */
    private static final int BRIGHT_SPRITE_BODY = 0xEB9620;

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
     * A pack's projectile flies the arc its own component describes.
     *
     * A mob is not a `Projectile`, so nothing about the arc comes from the game: the velocity, the
     * inertia taken off it each tick and the gravity under it are all read out of
     * `minecraft:projectile`. What this catches is the flight never starting, which leaves the
     * entity hanging where it was put with the goals and the gravity both switched off.
     */
    @GameTest()
    public void aPackProjectileFlies(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(PROJECTILE);
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity shot = Platform.createMob(type, level);
        helper.assertTrue(shot instanceof AddonEntity, PROJECTILE + " is not a pack entity.");

        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(shot, at.getX() + 0.5, at.getY() + 3, at.getZ() + 0.5, 0f, 0f);
        helper.assertTrue(level.addFreshEntity(shot), PROJECTILE + " was refused by the world.");

        double from = shot.getZ();
        ((AddonEntity) shot).launchAsProjectile(null, new net.minecraft.world.phys.Vec3(0, 0, 1));
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(shot.getZ() > from,
                PROJECTILE + " did not move along the direction it was fired in.");
            helper.succeed();
        });
    }

    /**
     * A projectile that turns `isolated_physics` off is held back by the cobwebs it flies through.
     *
     * The pack's spark turns the flag off, as Mojang's own arrows do, which puts its flight on the
     * path every other entity is moved along. A flight still teleported along its arc crosses the
     * webs at the speed it was fired at.
     */
    @GameTest()
    public void aProjectileOffItsIsolatedPathIsHeldBackByCobwebs(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(PROJECTILE);
        if (type == null) {
            helper.succeed();
            return;
        }

        for (int z = STAND.getZ(); z < STAND.getZ() + WEBS; z++) {
            helper.setBlock(new BlockPos(STAND.getX(), STAND.getY(), z),
                net.minecraft.world.level.block.Blocks.COBWEB);
        }
        ServerLevel level = helper.getLevel();
        Entity shot = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(shot, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        helper.assertTrue(level.addFreshEntity(shot), PROJECTILE + " was refused by the world.");

        double from = shot.getZ();
        ((AddonEntity) shot).launchAsProjectile(null, new net.minecraft.world.phys.Vec3(0, 0, 1));
        helper.runAfterDelay(3, () -> {
            double flown = shot.getZ() - from;
            helper.assertTrue(flown > 0, PROJECTILE + " did not move through the webs at all.");
            helper.assertTrue(flown < WEBBED_REACH,
                PROJECTILE + " flew " + flown + " blocks through cobwebs in three ticks.");
            helper.succeed();
        });
    }

    /** The cobwebs laid in the spark's path, and less than three unhindered ticks of its flight. */
    private static final int WEBS = 6;
    private static final double WEBBED_REACH = 3;

    /**
     * `minecraft:custom_hit_test` widens what a hit may land on and leaves the collision box alone.
     *
     * The two are separate components in Bedrock and one question in Java, so the check is that the
     * pick radius grew and the bounding box did not.
     */
    @GameTest()
    public void aPackEntityIsHitOnItsOwnHitboxes(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(PROJECTILE);
        if (type == null) {
            helper.succeed();
            return;
        }

        Entity shot = Platform.createMob(type, helper.getLevel());
        helper.assertTrue(shot.getPickRadius() > 0,
            PROJECTILE + " declares hitboxes wider than its collision box and answers none of them.");
        helper.assertTrue(shot.getBbWidth() < 1,
            PROJECTILE + " widened its collision box, which the component does not describe.");
        helper.succeed();
    }

    /**
     * An entity a pack gave the dragon's death takes the dragon's two hundred ticks to die.
     *
     * A mob ordinarily disappears twenty ticks after its health reaches zero, so the entity is
     * ticked past that and has to still be standing. The ticks are driven here rather than waited
     * for, because a game test has twenty of its own to reach a verdict in.
     */
    @GameTest()
    public void aPackEntityDiesTheLongWay(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(SLOW_DYING);
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity dying = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(dying, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        helper.assertTrue(level.addFreshEntity(dying), SLOW_DYING + " was refused by the world.");

        net.minecraft.world.entity.LivingEntity living = (net.minecraft.world.entity.LivingEntity) dying;
        living.setHealth(0f);
        for (int i = 0; i < ORDINARY_DEATH_TICKS * 2; i++) {
            living.tick();
        }
        helper.assertFalse(dying.isRemoved(),
            SLOW_DYING + " was gone before a two hundred tick death could finish.");
        helper.assertTrue(living.deathTime > ORDINARY_DEATH_TICKS,
            SLOW_DYING + " counted no further than an ordinary mob's death.");
        helper.succeed();
    }

    /** How long a mob Java has no other plan for lies there before it is taken away. */
    private static final int ORDINARY_DEATH_TICKS = 20;

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
     * A pack's entity drops what its loot table holds.
     *
     * Where that table is asked for differs across the four versions this builds against, and a
     * mistake there costs every pack its drops while everything else still works. Nothing short of
     * a real death in a real world exercises it, which is why this is a game test and not a unit one.
     */
    @GameTest()
    public void aPackEntityDropsItsLoot(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        Entity spawned = Platform.createMob(type, level);
        helper.assertTrue(spawned instanceof at.ioexception.mcaddon.entity.AddonEntity,
            ENTITY + " is not an add-on entity.");

        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(spawned, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(spawned);

        // A vanilla table rather than a pack's own: every version ships one, and what is under test
        // is the hook that reaches a table at all, not which table a pack named.
        ((at.ioexception.mcaddon.entity.AddonEntity) spawned)
            .setLootTable(Platform.id("minecraft", "entities/cow"));
        Platform.kill(spawned, level);

        helper.runAfterDelay(3, () -> {
            helper.assertFalse(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                spawned.getBoundingBox().inflate(4)).isEmpty(),
                ENTITY + " died holding a loot table and dropped nothing.");
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
     * A pack's block makes the sounds of the group its own `minecraft:sound` names.
     *
     * The lamp names glass in its behaviour definition and the pack ships no `blocks.json`, so a
     * block made from the definition alone keeps the stone a block falls back on.
     */
    @GameTest()
    public void aPackBlockSoundsLikeTheGroupItNames(GameTestHelper helper) {
        net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK
            .getOptional(Platform.id("tp", "lamp")).orElse(null);
        if (block == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(block.defaultBlockState().getSoundType()
                == net.minecraft.world.level.block.SoundType.GLASS,
            BLOCK + " names the glass sound group and does not sound like glass.");
        helper.succeed();
    }

    /**
     * A pack's block runs the event its own clock asks for.
     *
     * `minecraft:queued_ticking` names an interval and an event, and none of that chain exists
     * outside a running world: placing the block schedules the tick, the tick runs the event, and
     * the event writes one of the pack's own states back onto the block.
     */
    @GameTest()
    public void aPackBlockRunsItsScheduledEvent(GameTestHelper helper) {
        net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK
            .getOptional(Platform.id("tp", "lamp")).orElse(null);
        Property<?> declared = block == null ? null : block.getStateDefinition().getProperty("tp_lit");
        if (!(declared instanceof BooleanProperty lit)) {
            helper.succeed();
            return;
        }

        BlockPos at = helper.absolutePos(STAND);
        helper.getLevel().setBlockAndUpdate(at, block.defaultBlockState().setValue(lit, false));
        helper.succeedWhen(() -> helper.assertTrue(helper.getBlockState(STAND).getValue(lit),
            BLOCK + " never ran the event its clock schedules."));
    }

    /**
     * A pack's block runs the event it names for whoever stands on it.
     *
     * Java calls `stepOn` from the entity's own movement, so a mob that lands on the block is what
     * fires `minecraft:on_step_on` and what the event then writes into the block's states.
     */
    @GameTest()
    public void aPackBlockRunsItsStepOnEvent(GameTestHelper helper) {
        net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK
            .getOptional(Platform.id("tp", "lamp")).orElse(null);
        Property<?> declared = block == null ? null : block.getStateDefinition().getProperty("tp_level");
        if (!(declared instanceof IntegerProperty steps)) {
            helper.succeed();
            return;
        }

        helper.getLevel().setBlockAndUpdate(helper.absolutePos(STAND),
            block.defaultBlockState().setValue(steps, 0));
        // 26.2 moved the vanilla entity types out of the class that builds them.
        //? if >=26.2 {
        helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityTypes.PIG, STAND.above());
        //?} else {
        /*helper.spawnWithNoFreeWill(EntityType.PIG, STAND.above());
        *///?}
        helper.succeedWhen(() -> helper.assertTrue(helper.getBlockState(STAND).getValue(steps) == 2,
            BLOCK + " never ran the event it names for whoever steps on it."));
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
     * A texel the pack marks the way Bedrock marks a cut-out one reaches the game drawn.
     *
     * Bedrock's alpha test keeps every texel carrying any alpha and Java's cutout shader keeps only
     * those above a tenth, so a region a pack wrote at alpha three is drawn by one edition and
     * thrown away by the other. Nothing about that is visible in a count: the file is converted, the
     * model finds it, and the entity is simply drawn without whatever the region held. What the
     * conversion has to do instead is raise it, and this reads the written file back to say it did.
     */
    @GameTest()
    public void aMarkedTexelIsConvertedOpaque(GameTestHelper helper) {
        if (AddonPacks.all().stream().noneMatch(one -> one.texturePaths().containsKey(MARKED_TEXTURE))) {
            helper.succeed();
            return;
        }

        BufferedImage image = convertedTexture(MARKED_TEXTURE);
        helper.assertTrue(image != null, "The converted " + MARKED_TEXTURE + " could not be read.");

        helper.assertTrue(image.getRGB(0, 0) >>> 24 == 0xFF,
            "A texel the pack marked as drawn was converted too faint for Java's cutout to keep it.");
        helper.assertTrue(image.getRGB(image.getWidth() - 1, image.getHeight() - 1) >>> 24 == 0,
            "A texel the pack left empty was converted opaque.");
        helper.succeed();
    }

    /**
     * A pack shipping several forms of itself is loaded as the one its author put highest.
     *
     * The synthetic pack paints its sprite three times over, once in the pack itself and once in
     * each of its two subpacks, and leaves the low one last in the manifest so a reader taking the
     * last entry rather than the highest tier picks the wrong one. The body colour of the converted
     * texture is what says which of the three the converter read.
     */
    @GameTest()
    public void aPackIsLoadedAsItsHighestSubpack(GameTestHelper helper) {
        BedrockPack pack = McAddon.packs().packs().stream()
            .filter(one -> one.subpackName() != null)
            .findFirst().orElse(null);
        if (pack == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue("Bright sprite".equals(pack.subpackName()),
            "The pack was loaded as " + pack.subpackName() + " rather than as its highest subpack.");

        BufferedImage image = convertedTexture(MARKED_TEXTURE);
        helper.assertTrue(image != null, "The converted " + MARKED_TEXTURE + " could not be read.");
        helper.assertTrue((image.getRGB(0, 31) & 0xFFFFFF) == BRIGHT_SPRITE_BODY,
            "The converted sprite was drawn from a form of the pack other than the chosen subpack.");
        helper.succeed();
    }

    /**
     * The settings a pack declares reach the two places that read one.
     *
     * Bedrock lets a player move these on a screen beside the pack, so a pack's Molang and its
     * script both read back whatever they were left at. With no such screen, the value is the
     * `default` the manifest states, and both readers have to agree on that or a pack sees its
     * slider through its script and its own default through its models.
     */
    @GameTest()
    public void aPacksOwnSettingsReadBackAtTheirDeclaredValues(GameTestHelper helper) {
        if (PackSettings.values().isEmpty()) {
            helper.succeed();
            return;
        }

        helper.assertTrue(PackSettings.of("tp:ember_glows").enabled(),
            "A toggle the pack ships switched on read back switched off.");
        helper.assertTrue(PackSettings.of("tp:ember_heat").number() == 5.0,
            "A slider the pack ships at five read back at "
                + PackSettings.of("tp:ember_heat").number() + ".");
        helper.assertTrue(PackSettings.of("tp:ember_mood").selected("restless"),
            "The dropdown answered with an option the pack did not ship it at.");
        helper.assertTrue(!PackSettings.of("tp:ember_mood").selected("calm"),
            "The dropdown answered yes to an option it is not set to.");
        helper.assertTrue(PackSettings.values().get("tp:ember_glows") == Boolean.TRUE,
            "A script reading the pack settings is answered differently from the pack's own Molang.");
        helper.succeed();
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

    /**
     * A pack's lighting and atmospherics reach the world they were written for.
     *
     * Writing a timeline file proves nothing on its own. What this asks is the rest of the path: the
     * registry decoded the entry, the tag the pack ships merged with the one the game ships rather
     * than replacing it, and the dimension type resolved that tag into the set it samples the day
     * from. A tag that failed to merge leaves the files on disk and the world lit the way it always
     * was, which no count of written files would show.
     */
    @GameTest()
    public void aPackSkyReachesTheWorld(GameTestHelper helper) {
        for (ConvertedPack pack : AddonPacks.all()) {
            long written = timelinesWritten(pack);
            if (written == 0) continue;

            int given = Platform.overworldTimelines(helper.getLevel(), pack.namespace());
            if (given < 0) continue;

            helper.assertTrue(given == written, pack.namespace() + " wrote " + written
                + " timelines and the overworld was given " + given + " of them.");
        }
        helper.succeed();
    }

    /** How many timeline entries a converted pack put in the registry's own directory. */
    private static long timelinesWritten(ConvertedPack pack) {
        Path directory = pack.root().resolve("data").resolve(pack.namespace()).resolve("timeline");
        if (!Files.isDirectory(directory)) return 0;

        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            return entries.filter(file -> file.getFileName().toString().endsWith(".json")).count();
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /** The image a pack's texture was written as, or null where nothing wrote one. */
    private static BufferedImage convertedTexture(String texture) {
        ConvertedPack pack = AddonPacks.all().stream()
            .filter(one -> one.texturePaths().containsKey(texture))
            .findFirst().orElse(null);
        if (pack == null) return null;

        String mapped = pack.texturePaths().get(texture);
        int colon = mapped.indexOf(':');
        Path file = pack.root().resolve("assets").resolve(mapped.substring(0, colon))
            .resolve("textures").resolve(mapped.substring(colon + 1) + ".png");

        try {
            return ImageIO.read(file.toFile());
        } catch (IOException unreadable) {
            return null;
        }
    }

    /**
     * The fog a pack ships is read, and its two distances mean what the file said they meant.
     *
     * `render_distance_type` decides whether a distance is blocks or a multiple of the render
     * distance, and the render distance belongs to the client, so the multiple is what the server
     * keeps. A file read the other way puts the fog on the player's nose.
     */
    @GameTest()
    public void aPackFogIsReadWithItsOwnDistances(GameTestHelper helper) {
        FogDefinition fog = AddonFogs.definitionOf(FOG);
        if (fog == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(fog.scaledToRenderDistance(), FOG + " lost the render distance it scales to.");
        helper.assertTrue(fog.endAt(160f) > fog.startAt(160f), FOG + " ends before it starts.");
        helper.assertTrue(fog.red() > 0f || fog.green() > 0f || fog.blue() > 0f,
            FOG + " came out black, so its colour was not read.");
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
     * A goal runs at the priority and the speed its pack gave it.
     *
     * Bedrock and Java both order goals by a number where lower wins. A goal read without that
     * number runs in the order the components happened to be written in, which no count of
     * components can see: the component is named as implemented either way and the entity simply
     * does the wrong thing first.
     */
    @GameTest()
    public void aPackEntitysGoalsRunAtTheirDeclaredPriority(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(HURT_ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        Entity spawned = Platform.createMob(type, helper.getLevel());
        helper.assertTrue(spawned instanceof AddonEntity, HURT_ENTITY + " is not an add-on entity.");

        // The pack gives its stroll a priority of three, which is neither the first nor the last
        // of the goals it declares, so a count-up would land on a different number.
        AddonEntity addon = (AddonEntity) spawned;
        helper.assertTrue(addon.goalPriorities().contains(3),
            HURT_ENTITY + " runs its goals at " + addon.goalPriorities()
                + " rather than at the priority its pack declared.");
        helper.succeed();
    }
}
