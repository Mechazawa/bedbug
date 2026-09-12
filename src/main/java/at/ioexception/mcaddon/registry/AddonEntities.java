package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.TradeTable;
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
    private static final Map<EntityType<?>, TradeTable> TRADES = new LinkedHashMap<>();

    private AddonEntities() {
    }

    public static ServerEntityDefinition definitionOf(EntityType<?> type) {
        return DEFINITIONS.get(type);
    }

    /** Null when the entity does not trade. */
    public static TradeTable tradesOf(EntityType<?> type) {
        return TRADES.get(type);
    }

    /**
     * The entity type a Bedrock identifier names, whoever owns it.
     *
     * An add-on's own types are checked first, then the game's, under whichever name the two
     * editions give it: a pack writing `minecraft:zombie_pigman` means Java's `zombified_piglin`,
     * and it means that wherever the pack writes it.
     */
    public static EntityType<?> typeOf(String identifier) {
        EntityType<?> own = BY_IDENTIFIER.get(identifier);
        if (own != null) return own;

        String java = at.ioexception.mcaddon.convert.BedrockEntityNames.java(identifier);
        Identifier id = java == null ? null : Identifier.tryParse(java);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
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
        readTrades(pack, definition).ifPresent(trades -> TRADES.put(type, trades));
        Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type);
        FabricDefaultAttributeRegistry.register(type, AddonEntity.attributes(definition));
    }

    /**
     * The trade table an entity names, read while the pack it came from is still in hand.
     *
     * Bedrock has two components for this and they differ only in whether the trades cost emeralds,
     * which Java's merchant screen does not distinguish.
     */
    private static java.util.Optional<TradeTable> readTrades(BedrockPack pack,
        ServerEntityDefinition definition) {
        for (String component : List.of("minecraft:trade_table", "minecraft:economy_trade_table")) {
            com.google.gson.JsonObject body = definition.components().get(component);
            String table = body == null ? null : at.ioexception.mcaddon.util.Json.text(body, "table", null);
            if (table != null) return java.util.Optional.of(TradeTable.read(pack, table));
        }
        return java.util.Optional.empty();
    }

    /** Bedrock identifiers carry characters a resource location rejects, so both halves are cleaned. */
    private static Identifier identifier(String bedrock) {
        Names.Identifier split = Names.split(bedrock, "minecraft");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }
}
