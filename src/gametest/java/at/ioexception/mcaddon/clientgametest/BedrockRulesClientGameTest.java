package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.PlayerRules;
import at.ioexception.mcaddon.server.BedrockRules;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The game rules of Bedrock's a client draws by, set through `/bedrock gamerule`: the coordinates
 * and the day count written at the top left of the screen, the lines naming what an item may break,
 * and the toast for a recipe unlocked, each read back off what the client drew or would draw.
 */
public final class BedrockRulesClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("bedrock gamerule showcoordinates true");
            world.getServer().runCommand("bedrock gamerule showdaysplayed true");
            context.waitFor(client -> Drawn.drewText("Position: ") && Drawn.drewText("Days played: "));

            world.getServer().runCommand("bedrock gamerule showcoordinates false");
            context.waitFor(client -> !PlayerRules.on(BedrockRules.SHOW_COORDINATES, false));
            context.runOnClient(client -> Drawn.clearTexts());
            context.waitTicks(5);
            Assert.that(!Drawn.drewText("Position: ") && Drawn.drewText("Days played: "),
                "the coordinates stayed, or the day count went, once showcoordinates was off.");

            world.getServer().runCommand(
                "give @a minecraft:diamond_pickaxe[minecraft:can_break={blocks:\"minecraft:stone\"}]");
            context.waitFor(client -> client.player.getMainHandItem().has(DataComponents.CAN_BREAK));
            Assert.that(!breakLines(context).isEmpty(), "a pickaxe naming what it breaks says nothing about it.");
            world.getServer().runCommand("bedrock gamerule showtags false");
            context.waitFor(client -> !PlayerRules.on(BedrockRules.SHOW_TAGS, true));
            Assert.that(breakLines(context).isEmpty(), "the blocks a pickaxe breaks are listed with showtags off.");

            world.getServer().runCommand("bedrock gamerule showrecipemessages false");
            context.waitFor(client -> !PlayerRules.on(BedrockRules.SHOW_RECIPE_MESSAGES, true));
            world.getServer().runCommand("recipe give @a minecraft:stone_slab");
            context.waitTicks(20);
            Assert.that(context.computeOnClient(client -> recipeToast(client) == null),
                "a recipe toast showed with showrecipemessages off.");
            world.getServer().runCommand("bedrock gamerule showrecipemessages true");
            context.waitFor(client -> PlayerRules.on(BedrockRules.SHOW_RECIPE_MESSAGES, false));
            world.getServer().runCommand("recipe give @a minecraft:stone_bricks");
            context.waitFor(client -> recipeToast(client) != null);
        }
    }

    private static List<Component> breakLines(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            List<Component> lines = new ArrayList<>();
            client.player.getMainHandItem().get(DataComponents.CAN_BREAK).addToTooltip(lines::add);
            return lines;
        });
    }

    private static RecipeToast recipeToast(net.minecraft.client.Minecraft client) {
        return Worlds.toasts(client).getToast(RecipeToast.class, Toast.NO_TOKEN);
    }
}
