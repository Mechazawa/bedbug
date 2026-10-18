package at.ioexception.mcaddon.mixin;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The fields of a jigsaw structure's own definition that `place structure` builds a placement from,
 * since Java's `Structure.generate` takes none of them as overrides.
 */
@Mixin(JigsawStructure.class)
public interface JigsawStructureAccessor {
    @Accessor("startPool")
    net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool>
        mcaddon$startPool();

    @Accessor("startJigsawName")
    Optional<Identifier> mcaddon$startJigsawName();

    @Accessor("maxDepth")
    int mcaddon$maxDepth();

    @Accessor("startHeight")
    HeightProvider mcaddon$startHeight();

    @Accessor("useExpansionHack")
    boolean mcaddon$useExpansionHack();

    @Accessor("projectStartToHeightmap")
    Optional<Heightmap.Types> mcaddon$projectStartToHeightmap();

    //? if >=26 {
    @Accessor("maxDistanceFromCenter")
    JigsawStructure.MaxDistance mcaddon$maxDistanceFromCenter();
    //?} else {
    /*@Accessor("maxDistanceFromCenter")
    int mcaddon$maxDistanceFromCenter();
    *///?}

    //? if >=1.21 {
    @Accessor("poolAliases")
    java.util.List<net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding>
        mcaddon$poolAliases();

    @Accessor("dimensionPadding")
    net.minecraft.world.level.levelgen.structure.pools.DimensionPadding mcaddon$dimensionPadding();
    //?}
}
