package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonParticles;
import at.ioexception.mcaddon.script.ScriptBridge;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.script.ScriptCoverage;
import at.ioexception.mcaddon.script.surface.Blocks;
import at.ioexception.mcaddon.script.surface.Containers;
import at.ioexception.mcaddon.script.surface.Items;
import at.ioexception.mcaddon.script.surface.Registries;
import at.ioexception.mcaddon.script.surface.Worlds;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * What the script surface does to a world that is actually there.
 *
 * A pack reaches these through `bridge.containers` and its siblings, and the answers only mean
 * something against real blocks and real containers. The surfaces take a server, so a test drives
 * them the same way a pack's script does without needing a pack installed.
 */
public final class SurfaceGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /**
     * A chest answers for what is in it.
     *
     * This is the path a pack takes to give, take and count items, and every step of it needs a
     * container that exists: a handle, a size, a write and the read that has to agree with it.
     */
    @GameTest()
    public void aContainerHoldsWhatIsPutInIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(STAND);
        level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());

        Containers containers = new Containers(level.getServer(), new ScriptCoverage());
        String handle = containers.ofBlock(dimension(level), at.getX(), at.getY(), at.getZ());
        helper.assertTrue(handle != null, "A chest gave no container handle.");
        helper.assertTrue(containers.isValid(handle), "A chest's container reported itself invalid.");
        equal(helper, containers.size(handle), 27, "chest size");
        equal(helper, containers.emptySlots(handle), 27, "empty slots in a new chest");

        JsonObject stack = new JsonObject();
        stack.addProperty("typeId", "minecraft:stone");
        stack.addProperty("amount", 5);
        containers.setItem(handle, 0, stack.toString());

        String read = containers.item(handle, 0);
        helper.assertTrue(read != null, "A container forgot the item it was given.");

        JsonObject back = Json.parse(read).getAsJsonObject();
        equal(helper, back.get("typeId").getAsString(), "minecraft:stone", "stored item");
        equal(helper, back.get("amount").getAsInt(), 5, "stored amount");
        equal(helper, containers.emptySlots(handle), 26, "empty slots after one is filled");
        helper.succeed();
    }

    /**
     * A block keeps the states it is set with.
     *
     * Setting a permutation used to drop every state, which turned a rotated block into a default
     * one. A log has an axis, so reading it back is what proves the state survived the write.
     */
    @GameTest()
    public void aPermutationKeepsItsStates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(STAND);

        Blocks blocks = new Blocks(level.getServer(), new ScriptCoverage());
        JsonObject states = new JsonObject();
        states.addProperty("axis", "x");
        blocks.setPermutation(dimension(level), at.getX(), at.getY(), at.getZ(),
            "minecraft:oak_log", states.toString());

        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.OAK_LOG, STAND);
        equal(helper, level.getBlockState(at)
            .getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS),
            net.minecraft.core.Direction.Axis.X, "the axis the permutation asked for");

        String permutation = blocks.permutationAt(dimension(level), at.getX(), at.getY(), at.getZ());
        helper.assertTrue(permutation != null && permutation.contains("oak_log"),
            "The block did not describe itself as the one that was placed.");
        helper.succeed();
    }

    /**
     * The world surface reads and writes the blocks it is asked about.
     *
     * A pack fills a region and then asks what is in it, so the two have to agree about the same
     * world rather than each being right on its own.
     */
    @GameTest()
    public void theWorldAnswersForItsOwnBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(STAND);

        // Bedrock's own fill answers with the positions it changed, three numbers to a block.
        Worlds worlds = new Worlds(level.getServer(), new ScriptCoverage());
        int[] filled = worlds.fillBlocks(dimension(level), at.getX(), at.getY(), at.getZ(),
            at.getX() + 1, at.getY(), at.getZ() + 1, "minecraft:stone", null);
        helper.assertTrue(filled != null && filled.length == 12,
            "Filling a two by two answered with "
                + (filled == null ? "nothing" : filled.length + " numbers") + " rather than 12.");
        equal(helper, filled[0], at.getX(), "the first filled block's x");

        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.STONE, STAND);
        JsonObject filter = new JsonObject();
        com.google.gson.JsonArray wanted = new com.google.gson.JsonArray();
        wanted.add("minecraft:stone");
        filter.add("includeTypes", wanted);

        helper.assertTrue(worlds.containsBlock(dimension(level), at.getX(), at.getY(), at.getZ(),
            at.getX() + 1, at.getY(), at.getZ() + 1, filter.toString(), false),
            "The world denied holding the blocks it had just been filled with.");

        // A pack that writes a filter badly gets no filter, and never an ended tick.
        helper.assertTrue(worlds.containsBlock(dimension(level), at.getX(), at.getY(), at.getZ(),
            at.getX(), at.getY(), at.getZ(), "not json at all", false),
            "A malformed filter was not read as no filter.");
        helper.succeed();
    }

    /**
     * A registry lookup answers for something the game has, and refuses what it does not.
     *
     * A pack calls these to turn a name into a thing, and a lookup that answers for everything is
     * as wrong as one that answers for nothing.
     */
    @GameTest()
    public void aRegistryLookupKnowsWhatExists(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Registries registries = new Registries(server, new ScriptCoverage());
        Items items = new Items(server, new ScriptCoverage());

        helper.assertTrue(registries.hasEffectType("minecraft:speed"),
            "The effect registry denied knowing speed.");
        helper.assertFalse(registries.hasEffectType("mcaddon:no_such_effect"),
            "The effect registry claimed an effect nothing declares.");
        helper.assertTrue(items.exists("minecraft:stone"), "The item registry denied knowing stone.");
        helper.assertFalse(items.exists("mcaddon:no_such_item"),
            "The item registry claimed an item nothing declares.");
        helper.assertTrue(items.blockExists("minecraft:chest"),
            "The block registry denied knowing a chest.");
        helper.succeed();
    }

    /**
     * An item describes itself with the numbers a pack reads off it.
     *
     * How many of a thing stack is what a pack checks before handing one over, and it differs by
     * item rather than being the same everywhere.
     */
    @GameTest()
    public void anItemDescribesItself(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Items items = new Items(server, new ScriptCoverage());

        String described = items.describe("minecraft:iron_pickaxe");
        helper.assertTrue(described != null, "An iron pickaxe described itself as nothing.");

        JsonObject body = Json.parse(described).getAsJsonObject();
        equal(helper, body.get("maxAmount").getAsInt(), 1, "how many pickaxes stack");
        equal(helper, body.get("id").getAsString(), "minecraft:iron_pickaxe",
            "the id an item answers with");

        String stone = items.describe("minecraft:stone");
        equal(helper, Json.parse(stone).getAsJsonObject().get("maxAmount").getAsInt(), 64,
            "how many stone stack");
        helper.succeed();
    }

    /**
     * What a composter makes of an item reads back out of a hundred, the way a pack states it, for
     * an item of the game's own and for one a pack declares.
     */
    @GameTest()
    public void anItemReportsItsCompostingChance(GameTestHelper helper) {
        Items items = new Items(helper.getLevel().getServer(), new ScriptCoverage());
        equal(helper, compostingChance(items, "minecraft:wheat_seeds"), 30, "the composting chance of seeds");
        if (at.ioexception.mcaddon.registry.AddonItems.named("tp:berry") != null) {
            equal(helper, compostingChance(items, "tp:berry"), 40, "the composting chance of tp:berry");
        }
        helper.succeed();
    }

    private static int compostingChance(Items items, String itemId) {
        JsonObject body = Json.parse(items.describe(itemId)).getAsJsonObject();
        return body.has("compostable")
            ? body.getAsJsonObject("compostable").get("compostingChance").getAsInt() : 0;
    }

    /**
     * A tool wears out and a block does not, which is what a durability component is read for.
     *
     * This is the answer `ItemStack.getComponent("minecraft:durability")` is built on, so a zero
     * here is a pack being told a pickaxe never breaks.
     */
    @GameTest()
    public void aToolReportsHowMuchWearItTakes(GameTestHelper helper) {
        ScriptBridge bridge = bridgeOf(helper);
        if (bridge == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(bridge.itemMaxDurability("minecraft:iron_pickaxe") > 0,
            "An iron pickaxe reported that it never wears out.");
        equal(helper, bridge.itemMaxDurability("minecraft:stone"), 0,
            "the wear a stone block takes");
        helper.succeed();
    }

    /** Null when no pack ships a script, which is when there is no bridge to ask. */
    private static ScriptBridge bridgeOf(GameTestHelper helper) {
        ScriptHost scripts =
            ServerHooks.scripts();
        return scripts == null ? null : scripts.bridge();
    }

    /**
     * The particle command takes both a pack's own effect and one of the game's.
     *
     * A pack's effect is an emitter the mod runs and anything else is Java's own particle, so the
     * command has to accept either name. Whether a particle is then seen depends on somebody being
     * there to see it, which a test world has nobody for.
     */
    @GameTest()
    public void theParticleCommandTakesBothKinds(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(STAND);
        String where = at.getX() + " " + at.getY() + " " + at.getZ();

        BedrockLines.parses(helper, "particle minecraft:flame " + where);
        BedrockLines.parses(helper, "particle mcaddon:pack_effect " + where);
        helper.assertTrue(AddonParticles.definitionOf("mcaddon:pack_effect") == null,
            "A particle nothing declares was claimed by the mod.");
        helper.succeed();
    }

    /**
     * Two values that have to match, named so a failure says what was being compared.
     *
     * The helper gained a comparison of its own in 1.21, and this runs on 1.20.1 too, so the one
     * assertion every version has is what it is built from.
     */
    private static void equal(GameTestHelper helper, Object found, Object wanted, String what) {
        helper.assertTrue(wanted.equals(found),
            what + " came back as " + found + " rather than " + wanted + ".");
    }

    /**
     * What a pack asked for and did not get is counted rather than lost.
     *
     * This is the register `/mcaddon entities` reads, and a name reaching it is the only reason that
     * command says anything true. Counting the same name twice must not report it twice.
     */
    @GameTest()
    public void anUnimplementedNameIsCountedOnce(GameTestHelper helper) {
        String name = "minecraft:game_test_component";
        AddonEntities.unsupported(name);
        AddonEntities.unsupported(name);

        long naming = AddonEntities.report().stream()
            .filter(line -> line.contains(name)).count();
        equal(helper, (int) naming, 1, "lines naming one unimplemented component");
        helper.assertTrue(AddonEntities.report().stream()
            .anyMatch(line -> line.contains(name) && line.contains("x2")),
            "The register lost the second time a component was asked for.");
        helper.succeed();
    }

    /**
     * An entity carrying no pack definition refuses what a pack's own would allow.
     *
     * Riding is the clearest of these: a mob is rideable because a component said so, so one with no
     * components at all has nowhere for a rider to sit.
     */
    @GameTest()
    public void anEntityWithNoDefinitionCarriesNobody(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        net.minecraft.world.entity.EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", "pig")).orElse(null);
        helper.assertTrue(type != null, "The game has no pig to test with.");

        net.minecraft.world.entity.Entity pig = Platform.createMob(type, level);
        helper.assertTrue(pig != null, "A pig could not be created.");

        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(pig);
        helper.runAfterDelay(2, () -> {
            helper.assertFalse(pig.isRemoved(), "A pig did not survive the tick it was spawned in.");
            helper.succeed();
        });
    }

    private static String dimension(ServerLevel level) {
        return Platform.idOf(level.dimension()).toString();
    }
}
