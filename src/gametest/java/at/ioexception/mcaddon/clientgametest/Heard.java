package at.ioexception.mcaddon.clientgametest;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The sounds the client was asked to play since the last time this was cleared. */
public final class Heard {
    /** One request: the sound event and where it was played. */
    public record Sound(String event, Vec3 at) {
    }

    private static final List<Sound> SOUNDS = new CopyOnWriteArrayList<>();

    private Heard() {
    }

    public static void record(SoundInstance sound) {
        SOUNDS.add(new Sound(sound.getIdentifier().toString(), new Vec3(sound.getX(), sound.getY(), sound.getZ())));
    }

    public static void clear() {
        SOUNDS.clear();
    }

    public static List<Sound> of(String event) {
        return SOUNDS.stream().filter(sound -> sound.event().equals(event)).toList();
    }
}
