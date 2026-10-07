package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/**
 * A single player world opens with the test add-on loaded, and what its server summons reaches the
 * client as the pack's own entity type.
 */
public final class WorldClientGameTest implements FabricClientGameTest {
    private static final Identifier SPRITE = Identifier.parse("tp:sprite");

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(SPRITE)) {
            throw new AssertionError("The test add-on is not loaded: " + SPRITE + " is not registered.");
        }

        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            //? if >=26.2 {
            world.getConnection().waitForChunksRender();
            //?} else {
            /*world.getClientLevel().waitForChunksRender();
            *///?}
            world.getServer().runCommand("summon tp:sprite ~ ~ ~3");
            context.waitFor(client -> client.level != null && seen(client.level.entitiesForRendering()));
        }
    }

    private static boolean seen(Iterable<Entity> entities) {
        for (Entity entity : entities) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(SPRITE)) return true;
        }
        return false;
    }
}
