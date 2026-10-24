package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.EntitySounds;
import at.ioexception.mcaddon.bedrock.EventSounds;
import at.ioexception.mcaddon.bedrock.MusicDefinitions;
import at.ioexception.mcaddon.convert.BedrockSoundNames;
import at.ioexception.mcaddon.convert.ConvertedPack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;

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

    /** What each level sound event the active packs give a sound plays. */
    private static final Map<String, EventSound> EVENTS = new LinkedHashMap<>();

    /** What the active packs' `sounds.json` says each entity makes, by the name it is keyed under. */
    private static volatile Map<String, Voice> voices = Map.of();

    /**
     * The moment Bedrock files each of Java's own mob sounds under, by the last part of the Java id:
     * `entity.villager.trade` is the villager's `haggle`.
     */
    private static final Map<String, String> MOMENTS = Map.of("ambient", "ambient", "hurt", "hurt",
        "death", "death", "step", "step", "trade", "haggle", "yes", "haggle.yes", "no", "haggle.no");

    private AddonSounds() {
    }

    /** One pack's entry for an entity, with the names it gives read against that pack's own sounds. */
    private record Voice(EntitySounds sounds, String namespace, List<String> defined) {
        /**
         * What plays in place of `sound`, the Java sound this entity is about to make: the sound
         * itself where the entry names nothing for that moment, the entry's own sound where it names
         * one Java has, and null where the entry makes no sound for it.
         */
        SoundEvent replace(SoundEvent sound) {
            Identifier id = BuiltInRegistries.SOUND_EVENT.getKey(sound);
            String path = id == null ? "" : id.getPath();
            String moment = path.startsWith("entity.") ? MOMENTS.get(path.substring(path.lastIndexOf('.') + 1)) : null;
            if (moment == null || sounds.event(moment) == null) return sound;
            if (sounds.silences(moment)) return null;

            SoundEvent named = registered(BedrockSoundNames.java(sounds.event(moment), namespace, defined));
            return named == null ? sound : named;
        }
    }

    /**
     * What a mob of the game's own makes in place of `sound`, by what an active resource pack's
     * `sounds.json` says that mob makes: the sound itself where no pack names that moment, the pack's
     * sound where one does, and null where the pack silences it. A pack replacing the game's
     * villagers silences their idle, trade and hurt sounds this way.
     */
    public static SoundEvent voiceOf(Entity entity, SoundEvent sound) {
        if (voices.isEmpty() || entity instanceof at.ioexception.mcaddon.entity.AddonEntity) return sound;

        String identifier = AddonEntities.identifierOf(entity.getType());
        Voice voice = voices.get(identifier);
        if (voice == null) voice = voices.get(identifier.substring(identifier.indexOf(':') + 1));
        return voice == null ? sound : voice.replace(sound);
    }

    /** Replaces what each entity makes with what these packs say, a later pack overriding an earlier one. */
    public static void readVoices(List<ConvertedPack> packs) {
        Map<String, Voice> read = new LinkedHashMap<>();
        for (ConvertedPack pack : packs) {
            EntitySounds.readAll(pack.pack()).forEach((name, sounds) ->
                read.put(name, new Voice(sounds, pack.namespace(), pack.soundEvents())));
        }
        voices = Map.copyOf(read);
    }

    /**
     * A level sound event as Java plays it: the sound event, how loud and the range of its pitch.
     */
    public record EventSound(SoundEvent sound, float volume, float lowPitch, float highPitch) {
        /** Plays it where the player stands, for every player near enough to hear. */
        public void playAt(net.minecraft.world.entity.player.Player player) {
            float pitch = lowPitch + player.getRandom().nextFloat() * (highPitch - lowPitch);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound,
                player.getSoundSource(), volume, pitch);
        }
    }

    /**
     * What a level sound event plays: the entry an active pack's `individual_event_sounds` gives it,
     * or else the sound Bedrock's own entry for it names. Null where neither has a Java sound.
     */
    public static EventSound eventSound(String event) {
        EventSound own = EVENTS.get(event);
        if (own != null) return own;

        SoundEvent engine = registered(BedrockSoundNames.javaForEvent(event));
        return engine == null ? null : new EventSound(engine, 1, 1, 1);
    }

    /** The sound event registered under a Java id, or null where there is none or no id. */
    private static SoundEvent registered(String java) {
        Identifier id = java == null ? null : Identifier.tryParse(java);
        return id == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
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

    /**
     * Replaces the level sound events these packs give a sound with what these packs say, a later
     * pack overriding an earlier one as Bedrock's pack stack does.
     *
     * Only an item's `minecraft:swing_sounds` asks for a level sound event, so an entry no item
     * names, which in Bedrock would change that moment for every player, is reported instead.
     *
     * @param named the level sound events the active items name
     */
    public static void readEvents(List<ConvertedPack> packs, Set<String> named) {
        EVENTS.clear();
        for (ConvertedPack pack : packs) {
            EventSounds.readAll(pack.pack()).forEach((event, entry) -> {
                if (!named.contains(event)) {
                    AddonEntities.unsupported("sounds.json individual_event_sounds " + event);
                    return;
                }
                SoundEvent sound = registered(
                    BedrockSoundNames.java(entry.sound(), pack.namespace(), pack.soundEvents()));
                if (sound == null) {
                    AddonEntities.unsupported("individual_event_sounds naming " + entry.sound());
                    return;
                }
                EVENTS.put(event, new EventSound(sound, entry.volume(),
                    (float) entry.pitch()[0], (float) entry.pitch()[1]));
            });
        }
        named.stream().filter(event -> eventSound(event) == null)
            .forEach(event -> AddonEntities.unsupported("swing sound " + event));
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
            SoundEvent event = registered(BedrockSoundNames.java(music.event(), pack.namespace(),
                pack.soundEvents()));
            if (event == null) {
                AddonEntities.unsupported("music_definitions naming " + music.event());
                return;
            }
            MUSIC.put(biome, new Music(Holder.direct(event),
                music.minDelay(), music.maxDelay(), false));
        });
    }
}
