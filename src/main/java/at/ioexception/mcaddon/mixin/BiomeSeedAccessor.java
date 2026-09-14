package at.ioexception.mcaddon.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The seed a world places its biomes by.
 *
 * A client is never told the seed the world was made from; what it does get is this scrambled form
 * of it, which is the only number a client holds that is the same for everyone in one world and
 * different in the next.
 */
@Mixin(BiomeManager.class)
public interface BiomeSeedAccessor {
    @Accessor("biomeZoomSeed")
    long mcaddon$biomeZoomSeed();
}
