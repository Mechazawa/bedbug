package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.resource.AddonSet;
import at.ioexception.mcaddon.state.WorldAddonList;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Plays a world with the add-ons its list turns on, before the data packs are offered to it.
 *
 * Every world a dedicated server, a single player game or a world being created opens builds its
 * pack repository here first. A client opening a saved world has activated its set and reloaded
 * its resources before this runs.
 */
@Mixin(ServerPacksSource.class)
public abstract class ServerPacksSourceMixin {
    @Inject(method = "createPackRepository(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;)"
        + "Lnet/minecraft/server/packs/repository/PackRepository;", at = @At("HEAD"))
    private static void mcaddon$openWorld(LevelStorageSource.LevelStorageAccess access,
                                          CallbackInfoReturnable<?> info) {
        // A world made before it kept a list had every installed add-on; one being made without a
        // list chosen takes the set its data packs were just loaded with.
        AddonSet fallback = access.hasWorldData() ? ActiveAddons.installed() : ActiveAddons.active();
        ActiveAddons.open(WorldAddonList.open(access.getLevelPath(LevelResource.ROOT), fallback));
    }
}
