package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * A pack's record, put into a jukebox by a player, plays the music the pack gave it for as long as
 * the pack said and tells a comparator what the pack said.
 *
 * `tp:disc` names `record.13`, which the resource pack's `sound_definitions.json` redefines as its
 * own tone, states a duration of four seconds and a comparator signal of seven.
 */
public final class RecordClientGameTest implements FabricClientGameTest {
    private static final BlockPos JUKEBOX = Stage.CENTRE.south().above();
    private static final int COMPARATOR_SIGNAL = 7;
    private static final int LENGTH_TICKS = 4 * 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "survival");
            world.getServer().runCommand("setblock " + JUKEBOX.getX() + " " + JUKEBOX.getY() + " " + JUKEBOX.getZ()
                + " minecraft:jukebox");
            Stage.hold(context, world, "tp:disc", 1);
            context.getInput().lookAt(JUKEBOX);
            context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(JUKEBOX));
            context.getInput().pressKey(options -> options.keyUse);

            Worlds.waitForServer(context, world, server -> jukebox(server).getSongPlayer().isPlaying(), 40, () ->
                "tp:disc was used on a jukebox and the jukebox is not playing; it holds "
                    + world.getServer().computeOnServer(server -> jukebox(server).getTheItem().toString()) + ".");
            int started = world.getServer().computeOnServer(MinecraftServer::getTickCount);
            context.waitFor(client -> client.level.getBlockState(JUKEBOX).getValue(JukeboxBlock.HAS_RECORD));

            String sound = world.getServer().computeOnServer(server -> {
                JukeboxSong song = jukebox(server).getSongPlayer().getSong();
                return song == null ? "none"
                    : BuiltInRegistries.SOUND_EVENT.getKey(song.soundEvent().value()).toString();
            });
            Assert.that(sound.equals("tp:record.13"), "Expected the jukebox to play the pack's own record.13 and it"
                + " plays " + sound + ".");
            int signal = world.getServer().computeOnServer(server -> jukebox(server).getComparatorOutput());
            Assert.that(signal == COMPARATOR_SIGNAL, "Expected a jukebox playing tp:disc to give a comparator "
                + COMPARATOR_SIGNAL + " and it gives " + signal + ".");
            String held = world.getServer().computeOnServer(server ->
                BuiltInRegistries.ITEM.getKey(Stage.held(server).getItem()).toString());
            Assert.that(held.equals("minecraft:air"), "Expected the disc to leave the player's hand for the jukebox"
                + " and the hand holds " + held + ".");

            Worlds.waitForServer(context, world, server -> !jukebox(server).getSongPlayer().isPlaying(),
                LENGTH_TICKS * 3, "tp:disc states four seconds and was still playing after twelve.");
            int played = world.getServer().computeOnServer(MinecraftServer::getTickCount) - started;
            Assert.that(played >= LENGTH_TICKS, "Expected tp:disc to play for at least its four seconds, "
                + LENGTH_TICKS + " ticks, and it stopped after " + played + ".");
        }
    }

    private static JukeboxBlockEntity jukebox(MinecraftServer server) {
        return (JukeboxBlockEntity) Stage.player(server).level().getBlockEntity(JUKEBOX);
    }
}
