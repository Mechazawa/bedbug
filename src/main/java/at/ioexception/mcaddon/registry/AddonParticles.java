package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.particle.ParticleDefinition;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One particle type per effect a pack describes.
 *
 * Registering a type is what lets the game stitch the effect's texture onto the particle atlas and
 * hand a client the sprite for it, so this runs on both sides even though only a client draws one.
 */
public final class AddonParticles {
    private static final Map<String, ParticleDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<String, SimpleParticleType> TYPES = new LinkedHashMap<>();

    private AddonParticles() {
    }

    /**
     * Registers a type for each effect not registered yet, and binds every effect these packs
     * describe to their description. The first pack describing an effect decides it.
     */
    public static void register(List<ConvertedPack> packs) {
        java.util.Set<String> described = new java.util.HashSet<>();
        for (ConvertedPack pack : packs) {
            ParticleDefinition.readAll(pack.pack()).forEach((identifier, definition) -> {
                Identifier id = idOf(identifier);
                if (id == null || !described.add(identifier)) return;

                SimpleParticleType type = BuiltInRegistries.PARTICLE_TYPE.getOptional(id)
                    .filter(SimpleParticleType.class::isInstance)
                    .map(SimpleParticleType.class::cast)
                    .orElseGet(() -> Registry.register(BuiltInRegistries.PARTICLE_TYPE, id,
                        net.fabricmc.fabric.api.particle.v1.FabricParticleTypes.simple()));

                DEFINITIONS.put(identifier, definition);
                TYPES.put(identifier, type);
            });
        }
        if (!described.isEmpty()) {
            McAddon.LOGGER.info("Registered {} particle effects from add-ons.", described.size());
        }
    }

    /** The registered type for a Bedrock particle identifier, or null when no pack describes one. */
    public static SimpleParticleType typeOf(String identifier) {
        return TYPES.get(identifier);
    }

    public static ParticleDefinition definitionOf(String identifier) {
        return DEFINITIONS.get(identifier);
    }

    public static Map<String, ParticleDefinition> all() {
        return DEFINITIONS;
    }

    /** The Java id a Bedrock particle identifier becomes, or null when it cannot become one. */
    public static Identifier idOf(String identifier) {
        Names.Identifier split = Names.split(identifier, "mcaddon");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }
}
