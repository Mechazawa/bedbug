package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The track a pack's `/music` plays on this client, and the ones queued behind it.
 *
 * A track is music of its own rather than a sound somewhere in the world: it plays in the music
 * category everywhere at once, and the game's own background music waits while one plays. It fades
 * in over the seconds its line gave and fades out over the seconds the line ending it gave, loops
 * when asked, and on finishing hands over to the next one queued.
 */
public final class PlayerMusic {
    private static final float TICKS_PER_SECOND = 20f;

    private static Track playing;
    private static final Deque<JsonObject> QUEUED = new ArrayDeque<>();

    private PlayerMusic() {
    }

    /** One instruction from the server's `/music`. */
    public static void accept(String body) {
        JsonObject instruction = Json.parse(body).getAsJsonObject();
        switch (Json.text(instruction, "action", "")) {
            case "play" -> {
                QUEUED.clear();
                fadeOut(seconds(instruction));
                start(instruction);
            }
            case "queue" -> {
                if (playing == null || playing.isStopped()) {
                    start(instruction);
                } else {
                    QUEUED.add(instruction);
                }
            }
            case "stop" -> {
                QUEUED.clear();
                fadeOut(seconds(instruction));
                playing = null;
            }
            case "volume" -> {
                if (playing != null) playing.wanted = instruction.get("volume").getAsFloat();
            }
            default -> {
            }
        }
    }

    /** Starts the next queued track once the one playing is over, which only this client can tell. */
    public static void tick() {
        if (playing == null || !playing.isStopped() && Minecraft.getInstance().getSoundManager().isActive(playing)) {
            return;
        }
        if (playing.age < 2) return;

        playing = null;
        JsonObject next = QUEUED.poll();
        if (next != null) start(next);
    }

    /** Whether a pack's track has the music to itself, which is what keeps the game's own waiting. */
    public static boolean holdsTheMusic() {
        return playing != null && !playing.isStopped();
    }

    /** The track playing, for a test to read its name, loudness and whether it loops. */
    public static SoundInstance playing() {
        return playing;
    }

    /** How many tracks wait behind the one playing. */
    public static int queued() {
        return QUEUED.size();
    }

    /** How loud the track playing is meant to be once it has faded in, or nothing when none plays. */
    public static Float wantedVolume() {
        return playing == null ? null : playing.wanted;
    }

    public static void clear() {
        QUEUED.clear();
        if (playing != null) Minecraft.getInstance().getSoundManager().stop(playing);
        playing = null;
    }

    private static void start(JsonObject instruction) {
        Identifier track = Identifier.tryParse(Json.text(instruction, "track", ""));
        if (track == null) return;

        Minecraft.getInstance().getMusicManager().stopPlaying();
        playing = new Track(SoundEvent.createVariableRangeEvent(track), instruction.get("volume").getAsFloat(),
            Math.round(seconds(instruction) * TICKS_PER_SECOND), instruction.get("loop").getAsBoolean());
        Minecraft.getInstance().getSoundManager().play(playing);
    }

    private static void fadeOut(float seconds) {
        if (playing != null) playing.fadeOut(Math.round(seconds * TICKS_PER_SECOND));
    }

    private static float seconds(JsonObject instruction) {
        return instruction.has("fade") ? instruction.get("fade").getAsFloat() : 0f;
    }

    /** One track, which moves its own loudness towards what it was asked for a tick at a time. */
    private static final class Track extends AbstractTickableSoundInstance {
        private final int fadeIn;
        private float wanted;
        private int age;
        private int fadeOut = -1;
        private int fading;

        private Track(SoundEvent event, float wanted, int fadeIn, boolean loop) {
            super(event, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
            this.wanted = wanted;
            this.fadeIn = fadeIn;
            this.looping = loop;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = fadeIn > 0 ? 0f : wanted;
        }

        private void fadeOut(int ticks) {
            if (ticks <= 0) {
                stop();
                return;
            }
            fadeOut = ticks;
            fading = ticks;
        }

        @Override
        public void tick() {
            age++;
            if (fadeOut >= 0) {
                fading--;
                volume = wanted * Math.max(0, fading) / fadeOut;
                if (fading <= 0) stop();
                return;
            }
            volume = fadeIn > 0 && age < fadeIn ? wanted * age / fadeIn : wanted;
        }
    }
}
