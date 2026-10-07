package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * A pack's script names the game's own mobs by Bedrock's ids, and is answered under them.
 *
 * The test pack's script watches for `minecraft:villager_v2` the way a pack replacing villagers
 * does: by comparing `typeId` as each entity spawns, by querying the overworld every tick, and by
 * subscribing to `entityHurt` narrowed to that type. Each answer is a tag on the entity, so a Java
 * villager the script never recognised carries none of them.
 */
public final class VanillaNamesClientGameTest implements FabricClientGameTest {
    private static final int WAIT_TICKS = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("execute as @p at @s run summon minecraft:villager ~4 ~ ~ {NoAI:1b}");

            Worlds.waitForServer(context, world, server -> !tagged(server, "tp:spawn_seen").isEmpty(), WAIT_TICKS,
                "A Java villager spawned and the script never read its typeId as minecraft:villager_v2.");
            Worlds.waitForServer(context, world, server -> !tagged(server, "tp:found_by_query").isEmpty(), WAIT_TICKS,
                "getEntities({ type: \"minecraft:villager_v2\" }) never found a Java villager.");
            Worlds.waitForServer(context, world, server -> !tagged(server, "tp:villager_companion").isEmpty(),
                WAIT_TICKS, "The script found a villager and the sprite it spawned beside it never appeared.");
            Worlds.waitForServer(context, world,
                server -> !tagged(server, "tp:companion_marked_on_spawn").isEmpty(), WAIT_TICKS,
                "entitySpawn reached the script before the code that spawned the sprite had marked it.");

            world.getServer().runCommand("damage @e[type=minecraft:villager,limit=1] 1");
            Worlds.waitForServer(context, world, server -> !tagged(server, "tp:hurt_as_villager").isEmpty(),
                WAIT_TICKS, "An entityHurt listener for minecraft:villager_v2 never heard a Java villager hurt.");
            Worlds.waitForServer(context, world, server -> !tagged(server, "tp:before_hurt_as_villager").isEmpty(),
                WAIT_TICKS, "A before entityHurt listener filtered to minecraft:villager_v2 never heard it.");
            Assert.that(untagged(world, "tp:hurt_as_zombie"),
                "An entityHurt listener subscribed for minecraft:zombie heard a villager being hurt.");
            Assert.that(untagged(world, "tp:before_hurt_as_zombie"),
                "A before entityHurt listener filtered to minecraft:zombie heard a villager being hurt.");

            world.getServer().runCommand("execute as @p at @s run summon minecraft:experience_orb ~16 ~ ~");
            Worlds.waitForServer(context, world, server -> tagged(server, "tp:xp_orb_seen").stream()
                    .anyMatch(orb -> javaId(orb).equals("minecraft:experience_orb")), WAIT_TICKS,
                "An experience orb spawned and the script never read its typeId as minecraft:xp_orb.");

            world.getServer().runCommand("execute as @a run scriptevent \"tp:villager\"");
            Worlds.waitForServer(context, world, server -> !tagged(server, "tp:spawned_as_villager_v2").isEmpty(),
                WAIT_TICKS, "spawnEntity(\"minecraft:villager_v2\") spawned nothing.");
            String spawned = world.getServer()
                .computeOnServer(server -> javaId(tagged(server, "tp:spawned_as_villager_v2").get(0)));
            Assert.that(spawned.equals("minecraft:villager"),
                "spawnEntity(\"minecraft:villager_v2\") spawned a " + spawned + " rather than a villager.");
        }
    }

    private static String javaId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static boolean untagged(TestSingleplayerContext world, String tag) {
        return world.getServer().computeOnServer(server -> tagged(server, tag).isEmpty());
    }

    private static List<Entity> tagged(MinecraftServer server, String tag) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        return player.level().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(64),
            entity -> entity.entityTags().contains(tag));
    }
}
