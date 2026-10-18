package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.PlayerMusic;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/**
 * `/music` plays a pack's track on the client as music, loops it, turns it down, queues behind a
 * track that plays once, refuses to queue behind one that loops, and stops.
 *
 * The test add-on's one sound, `tp:sprite.chime`, is the track. A test client may have no audio
 * device, so what is asserted is the track the client is playing and what it was asked to be.
 */
public final class MusicClientGameTest implements FabricClientGameTest {
    private static final String TRACK = "tp:sprite.chime";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("bedrock music play " + TRACK + " 0.5 2 loop");
            context.waitFor(client -> PlayerMusic.playing() != null);
            SoundInstance looping = context.computeOnClient(client -> PlayerMusic.playing());
            Assert.that(looping.getIdentifier().toString().equals(TRACK),
                "the client plays " + looping.getIdentifier());
            Assert.that(looping.isLooping(), "a track played with loop does not loop.");
            Assert.that(looping.getSource() == SoundSource.MUSIC && looping.isRelative(),
                "the track is not music heard everywhere: " + looping.getSource());
            Assert.near(context.computeOnClient(client -> PlayerMusic.wantedVolume()), 0.5, 0.001,
                "the track's volume");

            world.getServer().runCommand("bedrock music volume 0.25");
            context.waitFor(client -> PlayerMusic.wantedVolume() != null && PlayerMusic.wantedVolume() < 0.3);
            world.getServer().runCommand("bedrock music queue " + TRACK);
            context.waitTicks(5);
            Assert.that(context.computeOnClient(client -> PlayerMusic.queued()) == 0,
                "a track was queued behind one that loops.");

            world.getServer().runCommand("bedrock music play " + TRACK + " 1 0 play_once");
            context.waitFor(client -> PlayerMusic.playing() != null && !PlayerMusic.playing().isLooping());
            world.getServer().runCommand("bedrock music queue " + TRACK);
            context.waitFor(client -> PlayerMusic.queued() == 1);

            world.getServer().runCommand("bedrock music stop");
            context.waitFor(client -> PlayerMusic.playing() == null && PlayerMusic.queued() == 0);
        }
    }
}
