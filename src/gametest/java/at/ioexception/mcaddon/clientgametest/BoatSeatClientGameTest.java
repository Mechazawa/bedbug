package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

/**
 * A pack's script seats an entity of its own in a Java boat through the boat's `minecraft:rideable`.
 *
 * Bedrock's boat carries that component whether anyone is aboard or not, and a pack dressing the
 * game's villagers in a model of its own asks for it to move the model into the boat the villager
 * boarded. A boat answering without the component leaves the model where the pack last put it, far
 * above the boat. The test pack's `tp:board` spawns a sprite two blocks over an empty boat and asks
 * the boat for its seats, so the sprite is drawn in the boat only when the boat answered.
 */
public final class BoatSeatClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 100;
    /** Java seats one rider a third of the boat's height above the boat's own position. */
    private static final double SEAT_HEIGHT = 0.1875;
    private static final double TOLERANCE = 0.05;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            TestServerContext game = world.getServer();
            Stage.build(context, world, "creative");
            game.runCommand("summon minecraft:oak_boat 0.5 120 3.5");
            context.waitFor(client -> boat(client) != null);
            game.runCommand("bedrock execute as @a run scriptevent tp:board");

            Worlds.waitForServer(context, game,
                server -> playerTagged(server, "tp:boat_without_seats") || boarded(server), PATIENCE,
                "The script never answered tp:board.");
            Assert.that(!game.computeOnServer(server -> playerTagged(server, "tp:boat_without_seats")),
                "getComponent(\"minecraft:rideable\") answered nothing for an empty Java boat.");
            Worlds.waitForClient(context, client -> seated(client) != null, PATIENCE,
                () -> "The sprite the script seated in the boat never rode it on the client.");
            Worlds.waitForClient(context, client -> Math.abs(height(client)) < TOLERANCE, PATIENCE,
                () -> "The sprite in the boat stands " + height(Minecraft.getInstance()) + " from the seat Java's"
                    + " boat puts a rider in.");
        }
    }

    private static double height(Minecraft client) {
        return seated(client).getY() - boat(client).getY() - SEAT_HEIGHT;
    }

    private static boolean playerTagged(MinecraftServer server, String tag) {
        return server.getPlayerList().getPlayers().get(0).entityTags().contains(tag);
    }

    private static boolean boarded(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0).level().getEntitiesOfClass(AddonEntity.class,
            server.getPlayerList().getPlayers().get(0).getBoundingBox().inflate(16),
            entity -> entity.entityTags().contains("tp:boarded")).stream().anyMatch(Entity::isPassenger);
    }

    private static Entity boat(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("oak_boat")) return entity;
        }
        return null;
    }

    private static AddonEntity seated(Minecraft client) {
        Entity boat = boat(client);
        if (boat == null) return null;
        return boat.getPassengers().stream().filter(AddonEntity.class::isInstance).map(AddonEntity.class::cast)
            .findFirst().orElse(null);
    }
}
