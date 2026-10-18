package at.ioexception.mcaddon.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * What a frozen built-in registry has to have opened again before an entry can be added to it.
 *
 * `validateWrite` refuses every write while `frozen` is set, and the `Item`, `Block` and
 * `EntityType` constructors ask the registry for a holder of their own, which it hands out only
 * while its map of unregistered holders exists. Freezing nulls that map and, from 26, builds the
 * component lookup and binds the tags, so `RegistryEdits` puts all of it back by hand.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor {
    @Accessor("frozen")
    boolean mcaddon$frozen();

    @Accessor("frozen")
    void mcaddon$setFrozen(boolean frozen);

    @Accessor("unregisteredIntrusiveHolders")
    Map<Object, Holder.Reference<Object>> mcaddon$unregisteredHolders();

    @Accessor("unregisteredIntrusiveHolders")
    void mcaddon$setUnregisteredHolders(Map<Object, Holder.Reference<Object>> holders);

    //? if >=26 {
    @Accessor("byId")
    it.unimi.dsi.fastutil.objects.ObjectList<Holder.Reference<Object>> mcaddon$byId();

    @Accessor("componentLookup")
    void mcaddon$setComponentLookup(net.minecraft.core.component.DataComponentLookup<Object> lookup);

    @org.spongepowered.asm.mixin.gen.Invoker("refreshTagsInHolders")
    void mcaddon$refreshTagsInHolders();
    //?}
}
