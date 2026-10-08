package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A pack's recipes, made at the block each is made at, through the screen that block opens.
 *
 * Every input is clicked into its slot the way a mouse click sends it and the result is shift-clicked
 * out, so what is asserted is what the player ends up carrying: `tp:lamp_recipe` is a ring of eight
 * stone around one `tp:dust`, `tp:wand_recipe` is shapeless and asks for two sticks and a
 * `tp:shard`, `tp:shard_cooked` smelts `tp:dust` into `tp:shard`, and `tp:glow_brew` brews an
 * awkward potion into one of night vision over `tp:dust`.
 */
public final class CraftingClientGameTest implements FabricClientGameTest {
    private static final BlockPos STATION = Stage.CENTRE.south().above();

    /** The first slot of the player's hotbar in a crafting table's menu, after the result and the grid. */
    private static final int CRAFTING_HOTBAR = 37;
    private static final int FURNACE_HOTBAR = 30;
    private static final int BREWING_HOTBAR = 32;

    /** How fast the server is run while it smelts and brews, so neither takes the seconds it would. */
    private static final int HURRIED_TICK_RATE = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            shaped(context, world);
            shapeless(context, world);
            smelt(context, world);
            brew(context, world);
        }
    }

    private static void shaped(ClientGameTestContext context, TestSingleplayerContext world) {
        open(context, world, "minecraft:crafting_table", CraftingScreen.class);
        give(context, world, List.of("minecraft:stone 8", "tp:dust 1"));

        pick(context, CRAFTING_HOTBAR);
        for (int slot : new int[] {1, 2, 3, 4, 6, 7, 8, 9}) place(context, slot, 1);
        pick(context, CRAFTING_HOTBAR + 1);
        place(context, 5, 0);
        take(context, world, 0, "tp:lamp");

        Map<String, Integer> carried = world.getServer().computeOnServer(CraftingClientGameTest::carried);
        Assert.that(carried.equals(Map.of("tp:lamp", 1)), "Expected crafting tp:lamp_recipe to leave the player"
            + " carrying one tp:lamp and none of its stone or dust, and the player carries " + carried + ".");
    }

    private static void shapeless(ClientGameTestContext context, TestSingleplayerContext world) {
        open(context, world, "minecraft:crafting_table", CraftingScreen.class);
        give(context, world, List.of("minecraft:stick 2", "tp:shard 1"));

        pick(context, CRAFTING_HOTBAR);
        place(context, 3, 1);
        place(context, 8, 1);
        pick(context, CRAFTING_HOTBAR + 1);
        place(context, 4, 0);
        take(context, world, 0, "tp:wand");

        Map<String, Integer> carried = world.getServer().computeOnServer(CraftingClientGameTest::carried);
        Assert.that(carried.equals(Map.of("tp:wand", 1)), "Expected crafting tp:wand_recipe out of two sticks and"
            + " a tp:shard to leave one tp:wand and nothing else, and the player carries " + carried + ".");
    }

    private static void smelt(ClientGameTestContext context, TestSingleplayerContext world) {
        open(context, world, "minecraft:furnace", FurnaceScreen.class);
        give(context, world, List.of("tp:dust 1", "minecraft:coal 1"));

        pick(context, FURNACE_HOTBAR);
        place(context, 0, 0);
        pick(context, FURNACE_HOTBAR + 1);
        place(context, 1, 0);
        hurried(context, world, () -> take(context, world, 2, "tp:shard"));

        Map<String, Integer> carried = world.getServer().computeOnServer(CraftingClientGameTest::carried);
        Assert.that(carried.equals(Map.of("tp:shard", 1)), "Expected smelting a tp:dust with one coal to leave one"
            + " tp:shard and nothing else, and the player carries " + carried + ".");
    }

    private static void brew(ClientGameTestContext context, TestSingleplayerContext world) {
        open(context, world, "minecraft:brewing_stand", BrewingStandScreen.class);
        give(context, world, List.of("minecraft:potion[potion_contents={potion:\"minecraft:awkward\"}] 1",
            "tp:dust 1", "minecraft:blaze_powder 1"));

        pick(context, BREWING_HOTBAR);
        place(context, 0, 0);
        pick(context, BREWING_HOTBAR + 1);
        place(context, 3, 0);
        pick(context, BREWING_HOTBAR + 2);
        place(context, 4, 0);
        hurried(context, world, () -> context.waitFor(client ->
            potionOf(client.player.containerMenu.getSlot(0).getItem()).endsWith("night_vision"), 600));
        String brewed = context.computeOnClient(client -> potionOf(client.player.containerMenu.getSlot(0).getItem()));
        Assert.that(brewed.equals("minecraft:night_vision"), "Expected tp:glow_brew to brew an awkward potion over"
            + " tp:dust into one of night vision and the bottle holds " + brewed + ".");
        String ingredient = context.computeOnClient(client ->
            id(client.player.containerMenu.getSlot(3).getItem()));
        Assert.that(ingredient.equals("minecraft:air"), "Expected brewing to use up the tp:dust and the ingredient"
            + " slot holds " + ingredient + ".");
    }

    /** Puts the station in front of the player, empties their hands and opens it with the use key. */
    private static void open(ClientGameTestContext context, TestSingleplayerContext world, String station,
        Class<? extends Screen> screen) {
        context.runOnClient(client -> {
            if (client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
        });
        Stage.build(context, world, "survival");
        world.getServer().runCommand("setblock " + STATION.getX() + " " + STATION.getY() + " " + STATION.getZ()
            + " " + station);
        Worlds.waitForServer(context, world.getServer(), server -> Stage.blockAt(server, STATION).equals(station), 20,
            station + " was never set in front of the player.");
        context.getInput().lookAt(STATION);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(STATION));
        context.getInput().pressKey(options -> options.keyUse);
        context.waitForScreen(screen);
    }

    private static void give(ClientGameTestContext context, TestSingleplayerContext world, List<String> stacks) {
        stacks.forEach(stack -> world.getServer().runCommand("give @a " + stack));
        int wanted = stacks.size();
        Worlds.waitForServer(context, world.getServer(), server -> carried(server).size() == wanted, 40,
            "The player was given " + stacks + " and does not carry them all.");
        context.waitTicks(2);
    }

    /** A left click on a slot, which picks up what it holds. */
    private static void pick(ClientGameTestContext context, int slot) {
        click(context, slot, 0, ContainerInput.PICKUP);
    }

    /** A click on a slot while carrying something: the right button leaves one there, the left all. */
    private static void place(ClientGameTestContext context, int slot, int button) {
        click(context, slot, button, ContainerInput.PICKUP);
    }

    /** Waits for the menu to offer the result, then shift-clicks it into the inventory. */
    private static void take(ClientGameTestContext context, TestSingleplayerContext world, int slot, String result) {
        context.waitFor(client -> id(client.player.containerMenu.getSlot(slot).getItem()).equals(result), 600);
        click(context, slot, 0, ContainerInput.QUICK_MOVE);
        Worlds.waitForServer(context, world.getServer(), server -> carried(server).containsKey(result), 40,
            result + " was shift-clicked out of the result slot and never reached the player's inventory.");
    }

    /** What clicking a slot of the open screen sends, which is also what the screen does with it. */
    private static void click(ClientGameTestContext context, int slot, int button, ContainerInput input) {
        context.runOnClient(client -> client.gameMode.handleContainerInput(client.player.containerMenu.containerId,
            slot, button, input, client.player));
        context.waitTick();
    }

    /** Runs a wait with the server going faster than a player would see it go. */
    private static void hurried(ClientGameTestContext context, TestSingleplayerContext world, Runnable wait) {
        world.getServer().runCommand("tick rate " + HURRIED_TICK_RATE);
        try {
            wait.run();
        } finally {
            world.getServer().runCommand("tick rate 20");
        }
    }

    /** Every item the player carries, counted by kind, with the screen's cursor counting as carried. */
    private static Map<String, Integer> carried(MinecraftServer server) {
        Map<String, Integer> counts = new TreeMap<>();
        Inventory inventory = Stage.player(server).getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) counts.merge(id(stack), stack.getCount(), Integer::sum);
        }
        ItemStack cursor = Stage.player(server).containerMenu.getCarried();
        if (!cursor.isEmpty()) counts.merge(id(cursor), cursor.getCount(), Integer::sum);
        return counts;
    }

    private static String potionOf(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents == null || contents.potion().isEmpty() ? "none"
            : contents.potion().get().unwrapKey().map(key -> key.identifier().toString()).orElse("none");
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
