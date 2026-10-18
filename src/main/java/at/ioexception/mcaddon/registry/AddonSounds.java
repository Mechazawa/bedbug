package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.MusicDefinitions;
import at.ioexception.mcaddon.convert.BedrockSoundNames;
import at.ioexception.mcaddon.convert.ConvertedPack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One sound event per definition a pack ships.
 *
 * Registered from the common initialiser rather than the client one: the server needs the ids to
 * exist before it can tell a client to play one.
 */
public final class AddonSounds {
    /** The track a pack asked for in each biome, by the name Bedrock and Java both give the biome. */
    private static final Map<String, Music> MUSIC = new LinkedHashMap<>();

    private AddonSounds() {
    }

    /** What a pack wants playing in the named biome, or null where no pack named that biome. */
    public static Music musicIn(String biome) {
        return MUSIC.get(biome);
    }

    public static void register(List<ConvertedPack> packs) {
        Set<String> seen = new HashSet<>();
        int registered = 0;

        for (ConvertedPack pack : packs) {
            for (String event : pack.soundEvents()) {
                if (!seen.add(event)) continue;
                Identifier id = Identifier.tryParse(event);
                if (id == null) {
                    McAddon.LOGGER.warn("{} defines a sound named {}, which is not a usable id.",
                        pack.displayName(), event);
                    continue;
                }
                if (BuiltInRegistries.SOUND_EVENT.containsKey(id)) continue;
                Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
                registered++;
            }
        }
        if (registered > 0) McAddon.LOGGER.info("Registered {} sound events from add-ons.", registered);
    }

    /** Replaces the music chosen per biome with what these packs choose. */
    public static void readMusic(List<ConvertedPack> packs) {
        MUSIC.clear();
        packs.forEach(AddonSounds::readMusic);
    }

    /**
     * The music a pack chose per biome, against the sound events it just registered.
     *
     * A definition naming one of Bedrock's own tracks has no definition in the pack to convert, so
     * it is read as Java's equivalent. One neither the pack nor the engine has plays nothing, so it
     * is left out and the biome keeps whatever the game already plays there.
     */
    private static void readMusic(ConvertedPack pack) {
        MusicDefinitions.readAll(pack.pack()).forEach((biome, music) -> {
            String named = BedrockSoundNames.java(music.event(), pack.namespace(),
                pack.soundEvents());
            Identifier id = named == null ? null : Identifier.tryParse(named);
            SoundEvent event = id == null ? null
                : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
            if (event == null) {
                AddonEntities.unsupported("music_definitions naming " + music.event());
                return;
            }
            MUSIC.put(biome, new Music(Holder.direct(event),
                music.minDelay(), music.maxDelay(), false));
        });
    }
}
