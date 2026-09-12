package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.resource.AddonPacks;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Offers the converted add-ons to every pack repository the game builds.
 *
 * Minecraft has no way for a mod to contribute a pack that is not inside its own jar, and an add-on
 * is only known at runtime. Adding a source at construction is the one point both the client's
 * resource repository and a world's data repository pass through.
 *
 * The same directory is offered to both. A converted resource pack holds only `assets`, so the data
 * repository reads nothing from it, and the reverse holds for a behaviour pack.
 */
@Mixin(PackRepository.class)
public class PackRepositoryMixin {
    @Mutable
    @Final
    @Shadow
    private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mcaddon$addAddonSource(CallbackInfo info) {
        Set<RepositorySource> extended = new LinkedHashSet<>(sources);
        extended.add(consumer -> AddonPacks.contribute(consumer, PackType.CLIENT_RESOURCES));
        sources = extended;
    }
}
