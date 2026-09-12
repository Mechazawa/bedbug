package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.ConvertedPack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One sound event per definition a pack ships.
 *
 * Registered from the common initialiser rather than the client one: the server needs the ids to
 * exist before it can tell a client to play one.
 */
public final class AddonSounds {
    private AddonSounds() {
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
}
