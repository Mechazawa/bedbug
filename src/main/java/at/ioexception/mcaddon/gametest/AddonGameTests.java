package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.Set;

/**
 * What holds on a running server, with or without an add-on installed.
 *
 * No pack content belongs in this repository, so the two tests that need one succeed when none is
 * loaded and assert for real when one is. Putting a `.mcaddon` in the run directory's `addons/`
 * gives them something to work with.
 *
 * `@GameTest` carries no attributes: before 26 it is Mojang's annotation, whose structure name has
 * no default, and the replacements in `build.remap.gradle.kts` fill that in for those versions.
 */
public final class AddonGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /**
     * A script that throws costs its tick and nothing else.
     *
     * Both calls reach the guest through a path the pack's own script uses: an index no pack
     * registered a command under, and an event no listener was attached to. Whatever the engine
     * raises there has to be caught, or the tick that follows never happens.
     */
    @GameTest()
    public void scriptFailureKeepsTheServerTicking(GameTestHelper helper) {
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts != null) {
            scripts.runCommand(-1, "gametest", "");
            scripts.raiseWorldEvent("gameTestRaisesNoSuchEvent", Map.of());
        }
        helper.runAfterDelay(2, helper::succeed);
    }

    /**
     * The Bedrock commands this mod adds accept what a pack writes.
     *
     * Reading is what every Bedrock line depends on, and it reaches the argument tree without
     * needing a target to exist.
     */
    @GameTest()
    public void bedrockCommandsParse(GameTestHelper helper) {
        BedrockLines.parses(helper, "playanimation @e animation.walk");
        BedrockLines.parses(helper, "playanimation @e animation.walk next.state 0.5 q.is_alive");
        BedrockLines.parses(helper, "event entity @e mcaddon:test_event");
        // Bedrock writes the port or leaves it out, and the line reaches the command as it stands.
        BedrockLines.parses(helper, "transfer @a \"example.test\"");
        BedrockLines.parses(helper, "transfer @a \"example.test\" 25565");
        helper.succeed();
    }

    /**
     * `/camera` and `/camerashake` take every word order Bedrock allows.
     *
     * Both build a deeper argument tree than anything else this mod registers, and each piece after
     * the preset may be left out, so what a pack wrote is only accepted if every one of those routes
     * through the tree ends somewhere. A missing route is a line dropped at load time.
     */
    @GameTest()
    public void cameraCommandsParse(GameTestHelper helper) {
        BedrockLines.parses(helper, "camera @a clear");
        BedrockLines.parses(helper, "camera @a set minecraft:free");
        BedrockLines.parses(helper, "camera @a set free rot 30 -90");
        BedrockLines.parses(helper, "camera @a set minecraft:free pos 1 2 3");
        BedrockLines.parses(helper, "camera @a set minecraft:free pos ~ ~ ~ rot 0 0");
        BedrockLines.parses(helper, "camera @a set minecraft:third_person ease 2 in_out_quad");
        BedrockLines.parses(helper, "camera @a set minecraft:third_person_front ease 0.5 linear pos 1 2 3 rot 0 0");
        BedrockLines.parses(helper, "camerashake add @a");
        BedrockLines.parses(helper, "camerashake add @a 0.5");
        BedrockLines.parses(helper, "camerashake add @a 0.5 3");
        BedrockLines.parses(helper, "camerashake add @a 0.5 3 rotational");
        BedrockLines.parses(helper, "camerashake stop @a");
        BedrockLines.parses(helper, "fog @a push minecraft:fog_crimson_forest \"my fog\"");
        BedrockLines.parses(helper, "fog @a pop \"my fog\"");
        BedrockLines.parses(helper, "fog @a remove \"my fog\"");
        helper.succeed();
    }

    /** Both commands run against a live target, and an event no entity declares is answered. */
    @GameTest()
    public void bedrockCommandsRun(GameTestHelper helper) {
        Entity target = place(helper, vanilla("armor_stand"));
        helper.assertTrue(target != null, "minecraft:armor_stand could not be created.");

        String selector = target.getStringUUID();
        helper.assertTrue(BedrockLines.run(helper, "playanimation " + selector + " animation.walk") == 1,
            "/playanimation reached no target.");
        helper.assertTrue(BedrockLines.run(helper, "event entity " + selector + " mcaddon:no_such_event") == 0,
            "/event fired an event nothing declares.");
        helper.succeed();
    }

    /**
     * `/clearspawnpoint` takes away the respawn point a player was given.
     *
     * Java's own commands only ever give one, so the player is given a point first and then has it
     * taken away by the command this mod registers, named the way a function line names them.
     */
    @GameTest()
    public void aClearedSpawnPointIsGone(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            Platform.respawnAt(player, helper.getLevel(), helper.absolutePos(STAND));
            helper.assertTrue(Platform.respawnPosition(player) != null,
                "The player was given no spawn point to clear.");
            helper.assertTrue(BedrockLines.run(helper, "clearspawnpoint " + player.getScoreboardName()) == 1,
                "/clearspawnpoint reached no player.");
            helper.assertTrue(Platform.respawnPosition(player) == null,
                "/clearspawnpoint left the player's spawn point where it was.");
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    /** An add-on entity is still in the world on the tick after it was summoned. */
    @GameTest()
    public void addonEntitySurvivesTheTickItSpawnedIn(GameTestHelper helper) {
        Set<EntityType<?>> types = AddonEntities.all().keySet();
        if (types.isEmpty()) {
            helper.succeed();
            return;
        }

        EntityType<?> type = types.iterator().next();
        Entity spawned = place(helper, type);
        helper.assertTrue(spawned != null, type + " could not be created.");
        helper.runAfterDelay(1, () -> {
            helper.assertFalse(spawned.isRemoved(), type + " was gone a tick after it was summoned.");
            helper.succeed();
        });
    }

    /**
     * Delivering the tick's data-driven triggers ends, and an entity no pack declares raises none.
     *
     * A listener on `dataDrivenEntityTrigger` may trigger another event, so the host holds the
     * trigger until its next tick rather than raising it in place. Ticking twice stands in for that:
     * the second tick has an empty queue and the tick still finishes.
     */
    @GameTest()
    public void entityEventDeliveryEndsInOneTick(GameTestHelper helper) {
        Entity plain = place(helper, vanilla("armor_stand"));
        helper.assertTrue(plain != null, "minecraft:armor_stand could not be created.");
        helper.assertFalse(EntityEvents.trigger(plain, "minecraft:entity_spawned", null, ServerHooks.state()),
            "An entity with no add-on definition ran an event.");

        if (ServerHooks.scripts() != null) {
            ServerHooks.scripts().tick(0);
            ServerHooks.scripts().tick(1);
        }
        helper.runAfterDelay(2, helper::succeed);
    }

    /**
     * A converted dimension decodes on the version that has to read it.
     *
     * A dimension type states a different set of fields on each supported version and a `dimension`
     * entry a data pack cannot decode stops a client on the screen asking the player to fix their
     * packs, which is a failure no compile and no count reports. Both are decoded here through the
     * game's own codecs, so a field name that moved fails the version it moved on.
     */
    @GameTest()
    public void aConvertedDimensionDecodes(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops =
            net.minecraft.resources.RegistryOps.create(
                com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());

        decodes(helper, "dimension type",
            net.minecraft.world.level.dimension.DimensionType.DIRECT_CODEC, ops,
            Platform.dimensionType(-64, 384));
        decodes(helper, "dimension",
            net.minecraft.world.level.dimension.LevelStem.CODEC, ops,
            at.ioexception.mcaddon.convert.Worldgen.voidDimension("minecraft:overworld",
                "minecraft:the_void"));
        helper.succeed();
    }

    /**
     * A pack's dimension is written on the version that can hold one and named on the one that
     * cannot, and a generator nothing converts is named on every version that reads dimensions.
     *
     * A world opened a second time reads the conversion the first one cached, and what the packs
     * asked for and did not get has to be named then as well.
     */
    @GameTest()
    public void aPackDimensionIsWrittenOrNamed(GameTestHelper helper) {
        at.ioexception.mcaddon.convert.ConvertedPack pack = ActiveAddons.active().converted().stream()
            .filter(one -> one.namespace().equals("tp")).findFirst().orElse(null);
        if (pack == null) {
            helper.succeed();
            return;
        }

        java.nio.file.Path data = pack.root().resolve("data").resolve("tp");
        boolean written = java.nio.file.Files.isRegularFile(data.resolve("dimension").resolve("grove.json"))
            && java.nio.file.Files.isRegularFile(data.resolve("dimension_type").resolve("grove.json"));
        java.util.List<String> report = AddonEntities.report();
        if (Platform.convertsDimensions()) {
            helper.assertTrue(written, "tp:grove was not written as a dimension and its type.");
            helper.assertTrue(!java.nio.file.Files.exists(data.resolve("dimension").resolve("chasm.json")),
                "tp:chasm names a generator nothing converts and was written anyway.");
        } else {
            helper.assertTrue(!written, "tp:grove was written on a version that makes such a world experimental.");
            helper.assertTrue(report.stream().anyMatch(line -> line.contains("behaviour pack dimensions (needs ")),
                "tp:grove is not named among what the packs asked for: " + report);
        }
        if (Platform.convertsWorldgen()) {
            helper.assertTrue(report.stream().anyMatch(line -> line.contains("dimension generator_type overworld")),
                "tp:chasm's generator is not named among what the packs asked for: " + report);
        }
        helper.succeed();
    }

    /**
     * A pack's `cameras/presets` are read, and each field of theirs that Java has nothing to draw
     * with is named: `tp:overlook` asks for the player's effects and a longer rendering reach, and
     * `tp:orbit` for the radius and the two offsets of the orbit it is built on.
     */
    @GameTest()
    public void aCameraPresetsDroppedFieldsAreNamed(GameTestHelper helper) {
        if (ActiveAddons.active().converted().stream().noneMatch(one -> one.namespace().equals("tp"))) {
            helper.succeed();
            return;
        }

        java.util.List<String> report = AddonEntities.report();
        helper.assertTrue(report.stream().noneMatch(line -> line.startsWith("    behaviour pack cameras")),
            "the pack's cameras directory is named as one nothing reads: " + report);
        for (String field : java.util.List.of("player_effects", "extend_player_rendering", "radius", "view_offset",
            "entity_offset")) {
            helper.assertTrue(report.stream().anyMatch(line -> line.startsWith("    camera preset " + field + " x")),
                "the camera preset field " + field + " is not named among what the packs asked for: " + report);
        }
        helper.succeed();
    }

    private static <T> void decodes(GameTestHelper helper, String what,
        com.mojang.serialization.Codec<T> codec,
        com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops,
        com.google.gson.JsonObject json) {
        com.mojang.serialization.DataResult<T> result = codec.parse(ops, json);
        helper.assertTrue(result.result().isPresent(),
            "A converted " + what + " did not decode: "
                + result.error().map(Object::toString).orElse(""));
    }

    private static EntityType<?> vanilla(String path) {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", path)).orElse(null);
    }

    /** Null when the type has no entity to build, which every assertion here treats as a failure. */
    private static Entity place(GameTestHelper helper, EntityType<?> type) {
        ServerLevel level = helper.getLevel();
        Entity entity = type == null ? null : Platform.createMob(type, level);
        if (entity == null) return null;

        BlockPos where = helper.absolutePos(STAND);
        Platform.placeAt(entity, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(entity);
        return entity;
    }
}
