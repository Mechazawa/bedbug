package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.mixin.MappedRegistryAccessor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Adds entries to the built-in registries an add-on writes into, frozen or not.
 *
 * Fabric keeps the built-in registries open until every mod has initialised, so at startup this
 * only runs the registration. After the freeze it opens each registry for the length of the
 * registration and closes it again the way `MappedRegistry.freeze` would have. Nothing is ever
 * removed and no raw id moves: an entry added here is appended after every entry already there, so
 * every id a client or a save already holds stays what it was.
 */
public final class RegistryEdits {
    /** Every built-in registry an add-on's objects go into. */
    private static final List<Registry<?>> WRITTEN = List.of(BuiltInRegistries.SOUND_EVENT,
        BuiltInRegistries.PARTICLE_TYPE, BuiltInRegistries.ENTITY_TYPE, BuiltInRegistries.BLOCK,
        BuiltInRegistries.ITEM);

    /** The registries whose values ask for their holder as they are constructed. */
    private static final List<Registry<?>> INTRUSIVE = List.of(BuiltInRegistries.ENTITY_TYPE,
        BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM);

    private RegistryEdits() {
    }

    /**
     * Runs `registration` with every registry it may write into open.
     *
     * An `Item`, a `Block` or an `EntityType` built after the freeze throws inside its own
     * constructor unless it is built in here. A registry is frozen again even when the registration
     * throws, and one left holding a holder whose value was built and never registered refuses the
     * way `freeze` does, naming it.
     */
    public static void adding(Runnable registration) {
        List<Opened> opened = WRITTEN.stream()
            .filter(registry -> ((MappedRegistryAccessor) registry).mcaddon$frozen())
            .map(Opened::open)
            .toList();
        //? if <26 {
        /*int states = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size();
        *///?}
        RuntimeException failure = null;
        try {
            registration.run();
        } catch (RuntimeException error) {
            failure = error;
        }
        for (Opened registry : opened) {
            IllegalStateException left = registry.close();
            if (left == null) continue;
            if (failure == null) {
                failure = left;
            } else {
                failure.addSuppressed(left);
            }
        }
        //? if <26 {
        /*// 26 has Fabric initialise every state as it is added; before that the client's tag step
        // does it for every state at once, so a block added here is ready before it is used.
        if (net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size() != states) {
            net.minecraft.world.level.block.Blocks.rebuildCache();
        }
        *///?}
        if (failure != null) throw failure;
    }

    /** One registry opened for writing, and what closing it again has to put back. */
    private record Opened(Registry<?> registry, MappedRegistryAccessor fields, int size) {
        static Opened open(Registry<?> registry) {
            MappedRegistryAccessor fields = (MappedRegistryAccessor) registry;
            fields.mcaddon$setFrozen(false);
            if (INTRUSIVE.contains(registry)) fields.mcaddon$setUnregisteredHolders(new IdentityHashMap<>());
            return new Opened(registry, fields, registry.size());
        }

        /** Freezes the registry again, and returns what refuses to be frozen, or null. */
        IllegalStateException close() {
            var unregistered = fields.mcaddon$unregisteredHolders();
            fields.mcaddon$setUnregisteredHolders(null);
            fields.mcaddon$setFrozen(true);
            //? if >=26 {
            // A component lookup caches what it found per component type, and freezing is what
            // builds a new one; a holder added afterwards starts with no tags bound at all, where
            // every other holder was bound at the freeze, until the next tag step binds them again.
            // A registry that did not grow keeps both, because other threads may be reading them.
            if (registry.size() != size) {
                fields.mcaddon$setComponentLookup(
                    new net.minecraft.core.component.DataComponentLookup<>(fields.mcaddon$byId()));
                fields.mcaddon$refreshTagsInHolders();
            }
            //?}
            return unregistered == null || unregistered.isEmpty() ? null
                : new IllegalStateException(registry.key() + " was left holding values nobody registered: "
                    + unregistered.keySet());
        }
    }
}
