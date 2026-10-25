package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The sounds a goal or a component names are moments of the entity's own `sounds.json` entry, and
 * each is heard when the thing it belongs to happens.
 *
 * `tp:sentinel` names a moment for each: the charge and the boom of a sonic boom, the gulp of a
 * swallowed mob, the growl of a defence, the sound of being provoked, and the cast and prepare of
 * a spell and of its step.
 */
public final class SentinelSoundClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(ui -> ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE)).create()) {
            Worlds.waitForChunks(world);
            context.runOnClient(client -> Heard.clear());

            summon(world, "tp:sentinel", "~3", "tp:sentinel_make_booming");
            world.getServer().runCommand("execute as @p at @s run summon minecraft:pig ~7 ~ ~ {NoAI:1b}");
            world.getServer().runOnServer(server -> sentinels(server).getFirst().setTarget(pigs(server).getFirst()));
            hear(context, "tp:sentinel.charge", "A sonic boom names a charge sound and it was never heard.");
            hear(context, "tp:sentinel.boom", "A sonic boom names an attack sound and it was never heard.");
            clear(world);

            summon(world, "tp:sentinel", "~3", "tp:sentinel_make_eating");
            world.getServer().runCommand("execute as @p at @s run summon minecraft:pig ~5 ~ ~ {NoAI:1b}");
            world.getServer().runOnServer(server -> sentinels(server).getFirst().setTarget(pigs(server).getFirst()));
            hear(context, "tp:sentinel.gulp", "An entity eating a mob names a sound for it and it was never heard.");
            clear(world);

            summon(world, "tp:sentinel", "~3", "tp:sentinel_make_defending");
            world.getServer().runCommand("execute as @p at @s run summon minecraft:pig ~6 ~ ~ {NoAI:1b}");
            world.getServer().runOnServer(server -> {
                Player owner = server.getPlayerList().getPlayers().getFirst();
                sentinels(server).getFirst().setOwner(owner);
                owner.setLastHurtByMob(pigs(server).getFirst());
            });
            hear(context, "tp:sentinel.growl", "An entity defending its owner names an aggro sound and it was never heard.");
            clear(world);

            world.getServer().runCommand("execute as @p at @s run summon tp:sentinel ~3 ~ ~");
            world.getServer().runCommand("execute as @p at @s run summon minecraft:pig ~6 ~ ~ {NoAI:1b}");
            context.waitTicks(5);
            world.getServer().runCommand("damage @e[type=tp:sentinel,limit=1] 1 minecraft:mob_attack by @e[type=minecraft:pig,limit=1]");
            hear(context, "tp:sentinel.provoked", "An entity being provoked names a sound for it and it was never heard.");
            clear(world);

            world.getServer().runCommand("execute as @p at @s run summon tp:sentinel ~3 ~ ~");
            world.getServer().runCommand("execute as @p at @s run summon tp:sentinel ~6 ~ ~");
            context.waitTicks(5);
            world.getServer().runOnServer(server -> {
                List<AddonEntity> found = sentinels(server);
                EntityEvents.trigger(found.get(0), "tp:sentinel_make_sending", null, ServerHooks.state());
                found.get(0).setTarget(found.get(1));
            });
            hear(context, "tp:sentinel.cast", "A spell names a start sound and it was never heard.");
            hear(context, "tp:sentinel.prepare", "A spell's step names a sound and it was never heard.");
        }
    }

    private static void summon(TestSingleplayerContext world, String type, String across, String event) {
        world.getServer().runCommand("execute as @p at @s run summon " + type + " " + across + " ~ ~");
        world.getServer().runCommand("bedrock event entity @e[type=" + type + "] " + event);
    }

    private static void hear(ClientGameTestContext context, String event, String message) {
        Worlds.waitForClient(context, client -> !Heard.of(event).isEmpty(), PATIENCE, () -> message);
    }

    private static void clear(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            Player player = server.getPlayerList().getPlayers().getFirst();
            player.level().getEntitiesOfClass(Entity.class, new AABB(player.blockPosition()).inflate(32),
                entity -> !(entity instanceof Player)).forEach(Entity::discard);
        });
    }

    private static List<AddonEntity> sentinels(MinecraftServer server) {
        Player player = server.getPlayerList().getPlayers().getFirst();
        return player.level().getEntitiesOfClass(AddonEntity.class, new AABB(player.blockPosition()).inflate(16));
    }

    private static List<Mob> pigs(MinecraftServer server) {
        Player player = server.getPlayerList().getPlayers().getFirst();
        return player.level().getEntitiesOfClass(Mob.class, new AABB(player.blockPosition()).inflate(16),
            mob -> !(mob instanceof AddonEntity));
    }
}
