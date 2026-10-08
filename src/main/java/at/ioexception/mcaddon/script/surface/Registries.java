package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.convert.BedrockEntityNames;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.script.ScriptCoverage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.biome.Biome;
import org.graalvm.polyglot.HostAccess;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The static lookup tables a pack calls `get` and `getAll` on.
 *
 * Each is a view of a game registry, so what a pack finds is what this game has. Bedrock writes a
 * vanilla id without its namespace, so a bare name is read as a `minecraft:` one.
 *
 * A whole table is returned as JSON text rather than as a list of handles, because a pack reads the
 * fields off what it gets back and a Java object reaches the guest without them.
 */
public final class Registries extends Surface {
    public Registries(MinecraftServer server, ScriptCoverage coverage) {
        super(server, coverage);
    }

    /** One entry per Bedrock id, so the woods Java gives a boat each are the one `minecraft:boat`. */
    @HostAccess.Export
    public String entityTypes() {
        Map<String, JsonObject> types = new LinkedHashMap<>();
        BuiltInRegistries.ENTITY_TYPE.forEach(type -> types.putIfAbsent(AddonEntities.identifierOf(type),
            entityTypeOf(type)));
        JsonArray all = new JsonArray();
        types.values().forEach(all::add);
        return all.toString();
    }

    @HostAccess.Export
    public String entityType(String id) {
        EntityType<?> type = id == null ? null : AddonEntities.typeOf(id);
        return type == null ? null : entityTypeOf(type).toString();
    }

    private static JsonObject entityTypeOf(EntityType<?> type) {
        JsonObject fields = new JsonObject();
        fields.addProperty("id", AddonEntities.identifierOf(type));
        fields.addProperty("localizationKey", type.getDescriptionId());
        return fields;
    }

    @HostAccess.Export
    public String[] effectTypes() {
        return BuiltInRegistries.MOB_EFFECT.keySet().stream()
            .map(Identifier::toString)
            .toArray(String[]::new);
    }

    @HostAccess.Export
    public boolean hasEffectType(String id) {
        Identifier parsed = qualified(id);
        return parsed != null && BuiltInRegistries.MOB_EFFECT.containsKey(parsed);
    }

    @HostAccess.Export
    public String enchantments() {
        Registry<Enchantment> registry = dynamic(net.minecraft.core.registries.Registries.ENCHANTMENT);
        JsonArray all = new JsonArray();
        registry.keySet().forEach(id -> all.add(enchantmentOf(registry, id)));
        return all.toString();
    }

    @HostAccess.Export
    public String enchantment(String id) {
        Registry<Enchantment> registry = dynamic(net.minecraft.core.registries.Registries.ENCHANTMENT);
        Identifier parsed = qualified(id);
        return parsed == null || !registry.containsKey(parsed)
            ? null : enchantmentOf(registry, parsed).toString();
    }

    private static JsonObject enchantmentOf(Registry<Enchantment> registry, Identifier id) {
        JsonObject enchantment = new JsonObject();
        enchantment.addProperty("id", id.toString());
        enchantment.addProperty("maxLevel", registry.getOptional(id).map(Enchantment::getMaxLevel).orElse(1));
        return enchantment;
    }

    /**
     * The potions a pack can name, which are Bedrock's potion effect types.
     *
     * Bedrock splits a potion into the effect it carries and the shape it is thrown in, where Java
     * keeps the effect in its own registry and the shape in the item.
     */
    @HostAccess.Export
    public String potionEffectTypes() {
        JsonArray all = new JsonArray();
        BuiltInRegistries.POTION.keySet().forEach(id -> all.add(potionEffectOf(id)));
        return all.toString();
    }

    @HostAccess.Export
    public String potionEffectType(String id) {
        Identifier parsed = qualified(id);
        return parsed == null || !BuiltInRegistries.POTION.containsKey(parsed)
            ? null : potionEffectOf(parsed).toString();
    }

    /** An instant potion carries no duration, which is the field Bedrock leaves out rather than zeroes. */
    private static JsonObject potionEffectOf(Identifier id) {
        JsonObject potion = new JsonObject();
        potion.addProperty("id", id.toString());
        List<MobEffectInstance> effects = BuiltInRegistries.POTION.getOptional(id)
            .map(Potion::getEffects)
            .orElse(List.of());
        if (!effects.isEmpty()) potion.addProperty("durationTicks", effects.get(0).getDuration());
        return potion;
    }

    /**
     * Every vanilla id a pack may name, grouped by the registry that holds it.
     *
     * This is what `@minecraft/vanilla-data` publishes as its tables, read from the registries so
     * that each one is what this version of the game ships rather than a list kept in step by hand.
     * Only `minecraft:` ids are taken: an add-on's own blocks and items belong to that add-on.
     */
    @HostAccess.Export
    public String vanillaTypes() {
        JsonObject tables = new JsonObject();
        tables.add("blocks", bothSpellings(BuiltInRegistries.BLOCK.stream()
            .flatMap(block -> block.getStateDefinition().getPossibleStates().stream())
            .map(state -> List.of(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                AddonBlocks.identifierOf(state)))));
        tables.add("items", bothSpellings(BuiltInRegistries.ITEM.stream()
            .map(item -> List.of(BuiltInRegistries.ITEM.getKey(item).toString(), AddonItems.identifierOf(item)))));
        tables.add("entities", vanillaEntityIds());
        tables.add("effects", vanillaIds(BuiltInRegistries.MOB_EFFECT));
        tables.add("potionEffects", vanillaIds(BuiltInRegistries.POTION));
        tables.add("enchantments",
            vanillaIds(dynamic(net.minecraft.core.registries.Registries.ENCHANTMENT)));
        tables.add("biomes", vanillaIds(dynamic(net.minecraft.core.registries.Registries.BIOME)));

        // Bedrock's feature types name the structures a world generates, which Java keeps in a
        // registry of its own rather than among the features a biome places.
        tables.add("features", vanillaIds(dynamic(net.minecraft.core.registries.Registries.STRUCTURE)));

        JsonArray dimensions = new JsonArray();
        server.getAllLevels().forEach(level -> dimensions.add(dimensionOf(level)));
        tables.add("dimensions", dimensions);

        return tables.toString();
    }

    private static JsonArray vanillaIds(Registry<?> registry) {
        JsonArray ids = new JsonArray();
        registry.keySet().stream()
            .filter(id -> id.getNamespace().equals("minecraft"))
            .forEach(id -> ids.add(id.toString()));
        return ids;
    }

    /**
     * Each vanilla block or item under both editions' ids, Java's beside Bedrock's: `PoweredRail`
     * beside `GoldenRail`, and `Furnace` beside the `LitFurnace` Bedrock counts as a type of its own.
     */
    private static JsonArray bothSpellings(java.util.stream.Stream<List<String>> pairs) {
        Set<String> ids = new LinkedHashSet<>();
        pairs.flatMap(List::stream).filter(id -> id.startsWith("minecraft:")).forEach(ids::add);
        JsonArray all = new JsonArray();
        ids.forEach(all::add);
        return all;
    }

    /**
     * Each vanilla entity under both editions' ids, the way Bedrock's own table keeps `Villager`
     * beside `VillagerV2`. The surface accepts either and reports the Bedrock one.
     */
    private static JsonArray vanillaEntityIds() {
        Set<String> ids = new LinkedHashSet<>();
        BuiltInRegistries.ENTITY_TYPE.keySet().stream()
            .filter(id -> id.getNamespace().equals("minecraft"))
            .forEach(id -> {
                ids.add(id.toString());
                ids.add(BedrockEntityNames.bedrock(id.toString()));
            });
        JsonArray all = new JsonArray();
        ids.forEach(all::add);
        return all;
    }

    @HostAccess.Export
    public String[] biomeTypes() {
        return dynamic(net.minecraft.core.registries.Registries.BIOME).keySet().stream()
            .map(Identifier::toString)
            .toArray(String[]::new);
    }

    @HostAccess.Export
    public boolean hasBiomeType(String id) {
        Identifier parsed = qualified(id);
        return parsed != null && dynamic(net.minecraft.core.registries.Registries.BIOME).containsKey(parsed);
    }

    /**
     * The tags a biome belongs to.
     *
     * Reached by wrapping the biome itself rather than by asking the registry for a holder, because
     * the lookup from an id to a holder is spelled three different ways over the supported versions
     * and this one is spelled the same in all of them.
     */
    @HostAccess.Export
    public String[] biomeTags(String id) {
        Registry<Biome> registry = dynamic(net.minecraft.core.registries.Registries.BIOME);
        Identifier parsed = qualified(id);
        return parsed == null ? new String[0] : registry.getOptional(parsed)
            .map(registry::wrapAsHolder)
            .map(biome -> biome.tags().map(tag -> tag.location().toString()).toArray(String[]::new))
            .orElse(new String[0]);
    }

    /**
     * A registry the world carries rather than the game.
     *
     * Enchantments and biomes are data-driven, so they are reached through the server's registry
     * access instead of through {@link BuiltInRegistries}. Its accessor was renamed in 26.
     */
    private <T> Registry<T> dynamic(ResourceKey<Registry<T>> key) {
        //? if >=26 {
        return server.registryAccess().lookupOrThrow(key);
        //?} else {
        /*return server.registryAccess().registryOrThrow(key);
        *///?}
    }

    private static Identifier qualified(String id) {
        return id == null ? null : Identifier.tryParse(namespaced(id));
    }
}
