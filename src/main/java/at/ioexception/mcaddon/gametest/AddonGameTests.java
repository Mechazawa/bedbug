package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
     * Parsing is what a `.mcfunction` line and a command block depend on, and it reaches the
     * argument tree without needing a target to exist.
     */
    @GameTest()
    public void bedrockCommandsParse(GameTestHelper helper) {
        parses(helper, "playanimation @e animation.walk");
        parses(helper, "playanimation @e animation.walk next.state 0.5 q.is_alive");
        parses(helper, "event entity @e mcaddon:test_event");
        helper.succeed();
    }

    /** Both commands run against a live target, and an event no entity declares is answered. */
    @GameTest()
    public void bedrockCommandsRun(GameTestHelper helper) {
        Entity target = place(helper, vanilla("armor_stand"));
        helper.assertTrue(target != null, "minecraft:armor_stand could not be created.");

        String selector = target.getStringUUID();
        helper.assertTrue(run(helper, "playanimation " + selector + " animation.walk") == 1,
            "/playanimation reached no target.");
        helper.assertTrue(run(helper, "event entity " + selector + " mcaddon:no_such_event") == 0,
            "/event fired an event nothing declares.");
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
     * A listener on `dataDrivenEntityTrigger` may trigger another event, so delivery runs outside
     * the trigger it came from. Calling it twice over stands in for that: the second call has an
     * empty queue and the tick still finishes.
     */
    @GameTest()
    public void entityEventDeliveryEndsInOneTick(GameTestHelper helper) {
        Entity plain = place(helper, vanilla("armor_stand"));
        helper.assertTrue(plain != null, "minecraft:armor_stand could not be created.");
        helper.assertFalse(EntityEvents.trigger(plain, "minecraft:entity_spawned", null, ServerHooks.state()),
            "An entity with no add-on definition ran an event.");

        EntityEvents.deliver();
        EntityEvents.deliver();
        helper.runAfterDelay(2, helper::succeed);
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

    private static void parses(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack();
        ParseResults<CommandSourceStack> parsed = server.getCommands().getDispatcher().parse(command, source);

        helper.assertTrue(parsed.getExceptions().isEmpty() && !parsed.getReader().canRead(),
            "/" + command + " does not parse.");
    }

    private static int run(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        } catch (CommandSyntaxException error) {
            helper.fail("/" + command + " did not run: " + error.getMessage());
            return 0;
        }
    }
}
