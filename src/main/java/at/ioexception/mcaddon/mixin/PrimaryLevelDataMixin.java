package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.McAddon;
import com.mojang.serialization.Lifecycle;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opens an experimental world without the question, in a development environment only.
 *
 * A world holding one entry from a pack the game has no `KnownPack` for is experimental, and a
 * client then puts a screen in front of it that waits for a click forever. The harnesses have
 * nobody at the keyboard, so such a run used to hang until its deadline and report that the world
 * never opened, which says nothing about what made it experimental.
 *
 * The game is asked again on every open and keeps no record of having been answered, so this cannot
 * become a click somebody makes once. What it is instead is a line at `ERROR`, which every harness
 * already reads as a failure, and a world that opens anyway so the rest of the run still happens.
 * The gap that caused it is still a failure and still has to be fixed at its cause.
 *
 * A released jar never runs this: `isDevelopmentEnvironment` is false outside a `runClient`,
 * `runServer` or `runGameTest`, so a player is asked the question Mojang means them to be asked.
 */
@Mixin(PrimaryLevelData.class)
public abstract class PrimaryLevelDataMixin {
    @Unique
    private boolean mcaddonSaidSo;

    @Inject(method = "worldGenSettingsLifecycle", at = @At("RETURN"), cancellable = true)
    private void mcaddon$openExperimentalWorldsWhileDeveloping(CallbackInfoReturnable<Lifecycle> info) {
        if (info.getReturnValue() == Lifecycle.stable()) return;
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;

        if (!mcaddonSaidSo) {
            mcaddonSaidSo = true;
            McAddon.LOGGER.error("This world is experimental, which outside a development "
                + "environment asks the player whether to load it at all, every time they open it. "
                + "Something wrote a dynamic registry entry from a pack carrying no KnownPack.");
        }
        info.setReturnValue(Lifecycle.stable());
    }
}
