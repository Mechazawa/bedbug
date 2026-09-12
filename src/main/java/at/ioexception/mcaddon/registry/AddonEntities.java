package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An entity type per entity a behaviour pack declares.
 *
 * Registration happens during mod initialisation, before the registries freeze, which is why the
 * packs are read that early. A pack that names an entity Java cannot represent is reported here
 * rather than dropped without a word.
 */
public final class AddonEntities {
    private static final Map<EntityType<?>, ServerEntityDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<String, EntityType<?>> BY_IDENTIFIER = new LinkedHashMap<>();
    private static final Map<String, AtomicInteger> UNSUPPORTED = new TreeMap<>();

    private AddonEntities() {
    }

    public static ServerEntityDefinition definitionOf(EntityType<?> type) {
        return DEFINITIONS.get(type);
    }

    public static EntityType<?> typeOf(String identifier) {
        return BY_IDENTIFIER.get(identifier);
    }

    public static Map<EntityType<?>, ServerEntityDefinition> all() {
        return DEFINITIONS;
    }

    /** Counted rather than logged one by one: a pack uses the same component on every entity it has. */
    public static void unsupported(String component) {
        UNSUPPORTED.computeIfAbsent(component, name -> new AtomicInteger()).incrementAndGet();
    }

    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        UNSUPPORTED.forEach((component, uses) -> lines.add("    " + component + " x" + uses.get()));
        if (!lines.isEmpty()) lines.add(0, "Entity components the packs used and this mod does not implement:");
        return lines;
    }

    public static void register(PackSet packs) {
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;
            for (ServerEntityDefinition definition : ServerEntityDefinition.readAll(pack)) {
                register(pack, definition);
            }
        }
        if (!DEFINITIONS.isEmpty()) {
            McAddon.LOGGER.info("Registered {} entity types from add-ons.", DEFINITIONS.size());
            report().forEach(McAddon.LOGGER::info);
        }
    }

    private static void register(BedrockPack pack, ServerEntityDefinition definition) {
        Identifier id = identifier(definition.identifier());
        if (id == null) {
            McAddon.LOGGER.warn("{} declares {}, which is not a usable identifier.",
                pack.displayName(), definition.identifier());
            return;
        }
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            // A pack that changes a vanilla entity rather than adding one: the type already exists.
            return;
        }

        EntityType<AddonEntity> type = Platform.buildEntityType(
            EntityType.Builder.of(AddonEntity::new, MobCategory.CREATURE)
                .sized(definition.width() * definition.scale(), definition.height() * definition.scale()),
            id);

        DEFINITIONS.put(type, definition);
        BY_IDENTIFIER.put(definition.identifier(), type);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type);
        FabricDefaultAttributeRegistry.register(type, AddonEntity.attributes(definition));
    }

    /** Bedrock identifiers carry characters a resource location rejects, so both halves are cleaned. */
    private static Identifier identifier(String bedrock) {
        int colon = bedrock.indexOf(':');
        String namespace = colon > 0 ? Names.sanitise(bedrock.substring(0, colon)) : "minecraft";
        String path = Names.sanitise(colon > 0 ? bedrock.substring(colon + 1) : bedrock);
        return namespace.isEmpty() || path.isEmpty() ? null : Platform.id(namespace, path);
    }
}
