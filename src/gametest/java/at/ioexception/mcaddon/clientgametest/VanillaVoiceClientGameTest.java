package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.EntitySounds;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.registry.ActiveAddons;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.player.Player;

/**
 * A resource pack's `sounds.json` entry for a mob of the game's own decides what that mob sounds
 * like, the way a pack replacing the game's villagers silences them.
 *
 * The test pack keys an entry under `villager_v2`, Bedrock's bare name for Java's villager: its
 * idle sound has a volume of nothing, its hurt sound an empty name, and its `haggle.no` names a sound
 * of the pack's own. The villager's `haggle.yes` is left alone, so Java's own sound still plays for it.
 * Another add-on keying the villager later than the test pack decides its voice instead, the way
 * Villager News silences every moment, so the test asks nothing then.
 */
public final class VanillaVoiceClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 100;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!testPackVoicesTheVillager()) return;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("execute as @p at @s run summon minecraft:villager ~2 ~ ~ {NoAI:1b}");
            context.waitTicks(5);
            context.runOnClient(client -> Heard.clear());

            world.getServer().runOnServer(server -> {
                Villager villager = villager(server);
                villager.playSound(SoundEvents.VILLAGER_AMBIENT, 1, 1);
                villager.playSound(SoundEvents.VILLAGER_NO, 1, 1);
            });
            world.getServer().runCommand("damage @e[type=minecraft:villager,limit=1] 1");
            context.waitTicks(5);
            world.getServer().runOnServer(server -> villager(server).playSound(SoundEvents.VILLAGER_YES, 1, 1));

            Worlds.waitForClient(context, client -> !Heard.of(id(SoundEvents.VILLAGER_YES)).isEmpty(), PATIENCE,
                () -> "The villager's yes, which the pack leaves to Java, was never heard.");
            context.runOnClient(client -> {
                if (Heard.of("tp:villager.refuse").size() != 1) {
                    throw new AssertionError("The villager's no plays the pack's own sound once, and it was heard "
                        + Heard.of("tp:villager.refuse").size() + " times.");
                }
                for (SoundEvent silenced : new SoundEvent[] {SoundEvents.VILLAGER_AMBIENT, SoundEvents.VILLAGER_HURT,
                    SoundEvents.VILLAGER_NO}) {
                    if (!Heard.of(id(silenced)).isEmpty()) {
                        throw new AssertionError("The pack replaces " + id(silenced) + " and it was heard.");
                    }
                }
            });
        }
    }

    private static boolean testPackVoicesTheVillager() {
        String byFullName = null;
        String byBareName = null;
        for (ConvertedPack pack : ActiveAddons.active().converted()) {
            Map<String, EntitySounds> voices = EntitySounds.readAll(pack.pack());
            if (voices.containsKey("minecraft:villager_v2")) byFullName = pack.namespace();
            if (voices.containsKey("villager_v2")) byBareName = pack.namespace();
        }
        return "tp".equals(byFullName != null ? byFullName : byBareName);
    }

    private static Villager villager(MinecraftServer server) {
        Player player = server.getPlayerList().getPlayers().getFirst();
        return player.level().getEntitiesOfClass(Villager.class, new AABB(player.blockPosition()).inflate(8))
            .getFirst();
    }

    private static String id(SoundEvent sound) {
        return sound.location().toString();
    }
}
