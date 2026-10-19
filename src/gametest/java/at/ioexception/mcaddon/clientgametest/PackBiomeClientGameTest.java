package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.AmbientSounds;
import net.minecraft.world.attribute.BackgroundMusic;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The nether biomes the test pack declares generate in a normal world, reach a player standing in
 * them as the pack painted them, and are where the pack's spawn rule puts its entity.
 *
 * `tp:soot_fields` is painted by a `biomes/*.client_biome.json` and `tp:cinder_wastes` by an entry in
 * `biomes_client.json`, so the two shapes a resource pack writes a biome in each reach a client. What
 * is read back is the client's own copy: the biome as the registry sync delivered it, and the
 * environment attributes the renderer and the sound engine ask at the player's position.
 *
 * The spawn rule selects on `soot`, a tag only the pack's own biome carries, and allows the sprite
 * on crying obsidian alone. Java's natural spawner is driven at an arena cut into the soot fields,
 * half crying obsidian and half obsidian, and every sprite it places has to stand on the first. The
 * rule names the monster population, which is the cap Java counts a sprite against.
 *
 * The pack's whole-world lighting is read in the overworld on the way.
 */
public final class PackBiomeClientGameTest implements FabricClientGameTest {
    private static final ResourceKey<Biome> SOOT = biome("tp:soot_fields");
    private static final ResourceKey<Biome> CINDER = biome("tp:cinder_wastes");

    /** The radius and steps `/locate biome` searches with. */
    private static final int SEARCH_RADIUS = 6400;
    private static final int HORIZONTAL_STEP = 32;
    private static final int VERTICAL_STEP = 64;

    /** Under the nether's roof and well above its lava sea. */
    private static final int ARENA_Y = 100;
    private static final int ARENA_HALF = 8;

    /** How far from the arena the player waits; nothing spawns within 24 blocks of a player. */
    private static final int PLAYER_OFFSET = 40;

    private static final int SPAWN_ATTEMPTS = 300;
    private static final int PATIENCE = 1200;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
            .adjustSettings(ui -> {
                ui.setWorldType(ui.getNormalPresetList().get(0));
                ui.setSeed("12345");
                ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            }).create()) {
            Worlds.waitForChunks(world);
            TestServerContext server = world.getServer();
            lighting(context);

            BlockPos soot = locate(context, server, SOOT);
            soot(context, soot);
            spawnsOnlyWhereItsRuleAllows(server, soot);

            BlockPos cinder = locate(context, server, CINDER);
            cinder(context, cinder);
        }
    }

    /**
     * Finds the biome in the nether, carves an arena wholly inside it and puts the player beside it.
     *
     * The player flies, because the nether's roof is solid where the arena is cut and a player
     * standing nowhere falls through the lava sea.
     */
    private static BlockPos locate(ClientGameTestContext context, TestServerContext server, ResourceKey<Biome> biome) {
        BlockPos centre = server.computeOnServer(minecraft -> centreOf(minecraft.getLevel(Level.NETHER), biome));
        Assert.that(centre != null, "No " + biome.identifier() + " generated in the nether within "
            + SEARCH_RADIUS + " blocks of the origin, or none wide enough to stand an arena in.");

        BlockPos stand = centre.offset(PLAYER_OFFSET, 0, 0);
        server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        server.runCommand("execute in minecraft:the_nether run tp @a " + (stand.getX() + 0.5) + " "
            + stand.getY() + " " + (stand.getZ() + 0.5));
        Worlds.waitForServer(context, server, minecraft -> ready(minecraft.getLevel(Level.NETHER), centre),
            PATIENCE, "The nether around " + centre.toShortString() + " never finished loading.");

        server.runCommand(nether("fill", centre.offset(-ARENA_HALF, -2, -ARENA_HALF),
            centre.offset(ARENA_HALF, 3, ARENA_HALF), "minecraft:air"));
        server.runCommand(nether("fill", centre.offset(-ARENA_HALF, -1, -ARENA_HALF),
            centre.offset(-1, -1, ARENA_HALF), "minecraft:crying_obsidian"));
        server.runCommand(nether("fill", centre.offset(0, -1, -ARENA_HALF),
            centre.offset(ARENA_HALF, -1, ARENA_HALF), "minecraft:obsidian"));
        server.runCommand(nether("fill", stand.offset(-1, 0, -1), stand.offset(1, 2, 1), "minecraft:air"));

        context.waitFor(client -> client.level != null && client.level.dimension() == Level.NETHER
            && client.level.isLoaded(centre));
        return centre;
    }

    /** The first spot near where the search landed whose arena and surroundings are all that biome. */
    private static BlockPos centreOf(ServerLevel nether, ResourceKey<Biome> biome) {
        var found = nether.findClosestBiome3d(holder -> holder.is(biome), new BlockPos(0, ARENA_Y, 0),
            SEARCH_RADIUS, HORIZONTAL_STEP, VERTICAL_STEP);
        if (found == null) return null;

        BlockPos near = found.getFirst().atY(ARENA_Y);
        for (int reach = 0; reach <= 64; reach += 8) {
            for (int dx = -reach; dx <= reach; dx += 8) {
                for (int dz = -reach; dz <= reach; dz += 8) {
                    BlockPos centre = near.offset(dx, 0, dz);
                    if (wholly(nether, centre, biome)) return centre;
                }
            }
        }
        return null;
    }

    /** Whether the arena and a margin round it, which the spawner's wandering reaches, are the biome. */
    private static boolean wholly(ServerLevel nether, BlockPos centre, ResourceKey<Biome> biome) {
        int margin = ARENA_HALF + 4;
        for (int dx = -margin; dx <= margin; dx += 4) {
            for (int dz = -margin; dz <= margin; dz += 4) {
                if (!nether.getBiome(centre.offset(dx, 0, dz)).is(biome)) return false;
            }
        }
        return true;
    }

    private static boolean ready(ServerLevel nether, BlockPos centre) {
        return nether.players().size() == 1 && nether.isPositionEntityTicking(centre)
            && nether.areEntitiesLoaded(ChunkPos.containing(centre).pack());
    }

    /**
     * The overworld is lit with the ambient colour the pack's `minecraft:default_lighting` states,
     * which reaches it as a timeline the overworld's own tag was given.
     */
    private static void lighting(ClientGameTestContext context) {
        var ambient = context.computeOnClient(client -> client.level.environmentAttributes()
            .getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, client.player.blockPosition()));
        colour(ambient, 0x2b3042, "ambient light in the overworld");
    }

    /** What `biomes/soot_fields.client_biome.json` and the behaviour pack's tints paint. */
    private static void soot(ClientGameTestContext context, BlockPos centre) {
        Biome biome = clientBiome(context, centre, SOOT);
        colour(biome.getGrassColor(centre.getX(), centre.getZ()), 0x8c5a3c, "grass of tp:soot_fields");
        colour(biome.getFoliageColor(), 0x5c3a28, "foliage of tp:soot_fields");
        colour(biome.getWaterColor(), 0x4f6d7a, "water of tp:soot_fields");
        Assert.near(biome.getBaseTemperature(), 1.6, 1.0e-6, "the temperature of tp:soot_fields");
        Assert.that(!biome.hasPrecipitation(), "tp:soot_fields has no downfall and was given rain.");

        colour(attribute(context, EnvironmentAttributes.FOG_COLOR, centre), 0x3c2a24, "fog in tp:soot_fields");
        Assert.near(attribute(context, EnvironmentAttributes.FOG_START_DISTANCE, centre), 4, 1.0e-4,
            "where the fog starts in tp:soot_fields");
        Assert.near(attribute(context, EnvironmentAttributes.FOG_END_DISTANCE, centre), 40, 1.0e-4,
            "where the fog ends in tp:soot_fields");
        colour(attribute(context, EnvironmentAttributes.WATER_FOG_COLOR, centre), 0x24463c,
            "water fog in tp:soot_fields");
        Assert.near(attribute(context, EnvironmentAttributes.WATER_FOG_END_DISTANCE, centre), 9, 1.0e-4,
            "where the water fog ends in tp:soot_fields");

        List<AmbientParticle> particles = attribute(context, EnvironmentAttributes.AMBIENT_PARTICLES, centre);
        Assert.that(particles.size() == 1 && particles.get(0).particle().getType() == ParticleTypes.ASH,
            "Expected the ash tp:soot_fields asks for in its air and found " + particles + ".");
        Assert.near(particles.get(0).probability(), 0.0125, 1.0e-6, "the chance of ash in tp:soot_fields");

        AmbientSounds sounds = attribute(context, EnvironmentAttributes.AMBIENT_SOUNDS, centre);
        Assert.that(sounds.loop().map(PackBiomeClientGameTest::named).orElse("").equals("tp:ambient.glade.loop"),
            "Expected the pack's own loop in tp:soot_fields and found " + sounds + ".");
        Assert.that(sounds.mood().map(mood -> named(mood.soundEvent())).orElse("")
            .equals("minecraft:ambient.basalt_deltas.mood"),
            "Expected the basalt deltas' mood in tp:soot_fields and found " + sounds + ".");
        Assert.that(sounds.additions().size() == 1
            && named(sounds.additions().get(0).soundEvent()).equals("minecraft:ambient.basalt_deltas.additions"),
            "Expected the basalt deltas' additions in tp:soot_fields and found " + sounds + ".");
        Assert.near(sounds.additions().get(0).tickChance(), 0.0111, 1.0e-6,
            "the chance of an addition in tp:soot_fields");

        BackgroundMusic music = attribute(context, EnvironmentAttributes.BACKGROUND_MUSIC, centre);
        Assert.that(music.defaultMusic().map(track -> named(track.sound())).orElse("")
            .equals("tp:ambient.glade.loop"),
            "Expected the track the pack's music definition names in tp:soot_fields and found " + music + ".");
        Assert.near(attribute(context, EnvironmentAttributes.MUSIC_VOLUME, centre), 0.25, 1.0e-6,
            "the music volume in tp:soot_fields");
    }

    /** What the `biomes_client.json` entry for `tp:cinder_wastes` and the fog it names paint. */
    private static void cinder(ClientGameTestContext context, BlockPos centre) {
        Biome biome = clientBiome(context, centre, CINDER);
        colour(biome.getWaterColor(), 0x8a2f12, "water of tp:cinder_wastes");
        Assert.near(biome.getBaseTemperature(), 2.0, 1.0e-6, "the temperature of tp:cinder_wastes");

        colour(attribute(context, EnvironmentAttributes.FOG_COLOR, centre), 0x7a1408, "fog in tp:cinder_wastes");
        Assert.near(attribute(context, EnvironmentAttributes.FOG_START_DISTANCE, centre), 6, 1.0e-4,
            "where the fog starts in tp:cinder_wastes");
        Assert.near(attribute(context, EnvironmentAttributes.FOG_END_DISTANCE, centre), 52, 1.0e-4,
            "where the fog ends in tp:cinder_wastes");
        colour(attribute(context, EnvironmentAttributes.WATER_FOG_COLOR, centre), 0x61200c,
            "water fog in tp:cinder_wastes");
        Assert.near(attribute(context, EnvironmentAttributes.WATER_FOG_END_DISTANCE, centre), 11, 1.0e-4,
            "where the water fog ends in tp:cinder_wastes");
    }

    /**
     * The sprite is offered in the soot fields' spawn list and nobody else's, and every one Java's
     * natural spawner places at the arena stands on crying obsidian.
     */
    private static void spawnsOnlyWhereItsRuleAllows(TestServerContext server, BlockPos centre) {
        EntityType<?> sprite = AddonEntities.typeOf("tp:sprite");
        Assert.that(sprite != null, "The test add-on declares tp:sprite and no entity type of that name exists.");
        Assert.that(sprite.getCategory() == MobCategory.MONSTER, "tp:sprite's spawn rule names the monster"
            + " population and its type counts against the " + sprite.getCategory() + " cap instead.");

        Set<String> offering = server.computeOnServer(minecraft -> offering(minecraft, sprite));
        Assert.that(offering.equals(Set.of(SOOT.identifier().toString())),
            "Expected only tp:soot_fields, which carries the tag the spawn rule asks for, to offer tp:sprite"
                + " and found " + offering + ".");

        List<String> underfoot = server.computeOnServer(minecraft -> {
            ServerLevel nether = minecraft.getLevel(Level.NETHER);
            RandomSource random = RandomSource.create(centre.asLong());
            for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
                NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, nether, centre.offset(
                    random.nextIntBetweenInclusive(-ARENA_HALF, ARENA_HALF), 0,
                    random.nextIntBetweenInclusive(-ARENA_HALF, ARENA_HALF)));
            }
            AABB around = new AABB(centre).inflate(PLAYER_OFFSET);
            return nether.getEntitiesOfClass(Entity.class, around, entity -> entity.getType() == sprite).stream()
                .map(entity -> BuiltInRegistries.BLOCK.getKey(
                    nether.getBlockState(entity.blockPosition().below()).getBlock()).toString())
                .toList();
        });
        Assert.that(!underfoot.isEmpty(), "Java's natural spawner placed no tp:sprite on the crying obsidian"
            + " its spawn rule allows, in " + SPAWN_ATTEMPTS + " attempts.");
        Assert.that(underfoot.stream().allMatch("minecraft:crying_obsidian"::equals),
            "Expected every naturally spawned tp:sprite on crying obsidian and found them on " + underfoot + ".");
    }

    /** Every biome whose monster spawn list holds the type. */
    private static Set<String> offering(MinecraftServer server, EntityType<?> type) {
        return server.registryAccess().lookupOrThrow(Registries.BIOME).entrySet().stream()
            //? if >=26.3 {
            /*.filter(entry -> entry.getValue().getAttributes()
                .applyModifier(net.minecraft.world.attribute.EnvironmentAttributes.NATURAL_MOB_SPAWNS,
                    net.minecraft.world.level.biome.MobSpawnSettings.EMPTY)
                .getMobsToSpawn(MobCategory.MONSTER).unwrap().stream()
            *///?} else {
            .filter(entry -> entry.getValue().getMobSettings().getMobs(MobCategory.MONSTER).unwrap().stream()
            //?}
                .anyMatch(weighted -> weighted.value().type() == type))
            .map(entry -> entry.getKey().identifier().toString())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The biome the client holds there, waited for, because the chunk reaches the client after the player. */
    private static Biome clientBiome(ClientGameTestContext context, BlockPos centre, ResourceKey<Biome> expected) {
        Worlds.waitForClient(context, client -> client.level.getBiome(centre).is(expected), PATIENCE,
            () -> "Expected the client to hold " + expected.identifier() + " at " + centre.toShortString()
                + " and found " + Minecraft.getInstance().level.getBiome(centre).unwrapKey().orElse(null) + ".");
        return context.computeOnClient(client -> client.level.getBiome(centre).value());
    }

    private static <T> T attribute(ClientGameTestContext context, EnvironmentAttribute<T> attribute, BlockPos at) {
        return context.computeOnClient(client -> client.level.environmentAttributes().getValue(attribute, at));
    }

    //? if >=26.3 {
    /*private static void colour(org.joml.Vector3fc found, int wanted, String what) {
        colour(net.minecraft.util.ARGB.colorFromVector3f(found), wanted, what);
    }
    *///?}

    private static void colour(int found, int wanted, String what) {
        Assert.that((found & 0xFFFFFF) == wanted, "Expected the " + what + " to be #" + Integer.toHexString(wanted)
            + " and found #" + Integer.toHexString(found & 0xFFFFFF) + ".");
    }

    private static String named(Holder<SoundEvent> sound) {
        return sound.value().location().toString();
    }

    private static String nether(String command, BlockPos from, BlockPos to, String block) {
        return "execute in minecraft:the_nether run " + command + " " + from.getX() + " " + from.getY() + " "
            + from.getZ() + " " + to.getX() + " " + to.getY() + " " + to.getZ() + " " + block;
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static ResourceKey<Biome> biome(String id) {
        return ResourceKey.create(Registries.BIOME, Identifier.parse(id));
    }
}
