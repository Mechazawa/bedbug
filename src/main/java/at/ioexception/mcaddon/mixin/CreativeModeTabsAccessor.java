package at.ioexception.mcaddon.mixin;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The display parameters the creative tabs were last built for.
 *
 * The game builds every tab's contents once and builds them again only when the parameters it
 * cached change. Clearing them is how a client that switched add-on sets has the tabs built again
 * the next time the inventory opens.
 */
@Mixin(CreativeModeTabs.class)
public interface CreativeModeTabsAccessor {
    @Accessor("CACHED_PARAMETERS")
    static void mcaddon$setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) {
        throw new AssertionError();
    }
}
