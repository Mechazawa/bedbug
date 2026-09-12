package at.ioexception.mcaddon.script.surface;

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
import org.graalvm.polyglot.HostAccess;

import java.util.List;

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

    @HostAccess.Export
    public String entityTypes() {
        JsonArray types = new JsonArray();
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> types.add(entityTypeOf(id)));
        return types.toString();
    }

    @HostAccess.Export
    public String entityType(String id) {
        Identifier parsed = qualified(id);
        return parsed == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(parsed)
            ? null : entityTypeOf(parsed).toString();
    }

    private static JsonObject entityTypeOf(Identifier id) {
        JsonObject type = new JsonObject();
        type.addProperty("id", id.toString());
        type.addProperty("localizationKey", BuiltInRegistries.ENTITY_TYPE.getOptional(id)
            .map(EntityType::getDescriptionId)
            .orElse(""));
        return type;
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
        return id == null ? null : Identifier.tryParse(id.indexOf(':') < 0 ? "minecraft:" + id : id);
    }
}
