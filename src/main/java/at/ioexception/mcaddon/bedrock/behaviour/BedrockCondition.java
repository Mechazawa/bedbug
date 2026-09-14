package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.state.AddonState;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.util.Map;

/**
 * A Bedrock loot condition, asked as the loot is rolled rather than rewritten as a Java predicate.
 *
 * Java's own predicates have no field for a pack's entity properties, an entity variant, the world
 * difficulty or a Bedrock biome tag, so those conditions reach a loot table of this mod's own type
 * carrying the Bedrock object that states them. The type goes in the loot condition registry, which
 * is one the game builds itself rather than one a data pack writes into.
 */
public final class BedrockCondition implements LootItemCondition {

    /** The condition names a converted table writes as this one, each answered by `test`. */
    public static final String NAME = McAddon.MOD_ID + ":bedrock";

    private final JsonObject bedrock;

    private BedrockCondition(String stated) {
        JsonObject parsed = null;
        try {
            parsed = Json.parse(stated).getAsJsonObject();
        } catch (RuntimeException error) {
            McAddon.LOGGER.warn("Could not read a Bedrock loot condition: {}", error.toString());
        }
        this.bedrock = parsed == null ? new JsonObject() : parsed;
    }

    private static MapCodec<BedrockCondition> mapCodec() {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("bedrock").forGetter(one -> one.bedrock.toString()))
            .apply(instance, BedrockCondition::new));
    }

    //? if >=26 {
    private static final MapCodec<BedrockCondition> TYPE = mapCodec();

    @Override
    public MapCodec<? extends LootItemCondition> codec() {
        return TYPE;
    }
    //?} else if >=1.21 {
    /*private static final net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
        TYPE = new net.minecraft.world.level.storage.loot.predicates.LootItemConditionType(mapCodec());

    @Override
    public net.minecraft.world.level.storage.loot.predicates.LootItemConditionType getType() {
        return TYPE;
    }
    *///?} else {
    /*private static final net.minecraft.world.level.storage.loot.predicates.LootItemConditionType
        TYPE = new net.minecraft.world.level.storage.loot.predicates.LootItemConditionType(
            new net.minecraft.world.level.storage.loot.Serializer<BedrockCondition>() {
                @Override
                public void serialize(JsonObject json, BedrockCondition condition,
                    com.google.gson.JsonSerializationContext context) {
                    json.addProperty("bedrock", condition.bedrock.toString());
                }

                @Override
                public BedrockCondition deserialize(JsonObject json,
                    com.google.gson.JsonDeserializationContext context) {
                    return new BedrockCondition(Json.text(json, "bedrock", "{}"));
                }
            });

    @Override
    public net.minecraft.world.level.storage.loot.predicates.LootItemConditionType getType() {
        return TYPE;
    }
    *///?}

    public static void register() {
        Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, McAddon.id("bedrock"), TYPE);
    }

    /**
     * The chance each difficulty rolls against, by the name Bedrock states it under.
     *
     * A difficulty the condition says nothing about takes `default_chance`, which is what the two
     * Bedrock reads share.
     */
    private static final Map<Difficulty, String> DIFFICULTIES = Map.of(
        Difficulty.PEACEFUL, "peaceful", Difficulty.EASY, "easy",
        Difficulty.NORMAL, "normal", Difficulty.HARD, "hard");

    @Override
    public boolean test(LootContext context) {
        Entity subject = Platform.lootSubject(context);
        AddonState state = ServerHooks.state();

        return switch (Json.text(bedrock, "condition", "").replaceFirst("^minecraft:", "")) {
            case "entity_properties" -> entityProperties(subject);
            case "has_variant" -> number(state, subject, "variant");
            case "has_mark_variant" -> number(state, subject, "mark_variant");
            case "bool_property" -> property(state, subject);
            case "random_difficulty_chance" -> context.getRandom().nextDouble() < difficultyChance(context);
            case "random_regional_difficulty_chance" -> context.getRandom().nextDouble()
                < Json.number(bedrock, "max_chance", 1) * Platform.regionalDifficulty(context);
            case "killed_by_player_or_pets" -> killedByPlayerOrPets(Platform.lootKiller(context));
            case "biome_has_tag" -> biomeHasTag(context);
            case "entity_killed" -> killed(subject);
            default -> false;
        };
    }

    /**
     * Whether what the entity just killed is the kind the condition names.
     *
     * Bedrock asks this of the table `minecraft:behavior.eat_mob` names, which is rolled as the meal
     * goes down, so the entity the loot belongs to is the one that did the killing. A name with no
     * namespace means `minecraft:`, which is how every published table writes one.
     */
    private boolean killed(Entity subject) {
        String wanted = Json.text(bedrock, "entity_type", "");
        if (!(subject instanceof AddonEntity hunter) || wanted.isEmpty()) return false;

        String victim = hunter.killed();
        return victim != null
            && victim.equals(wanted.contains(":") ? wanted : "minecraft:" + wanted);
    }

    /** `on_fire` and `on_ground` are the two the engine answers; the subject is the dying entity. */
    private boolean entityProperties(Entity subject) {
        JsonObject properties = bedrock.getAsJsonObject("properties");
        if (subject == null || properties == null) return false;

        for (String name : properties.keySet()) {
            boolean wanted = Json.bool(properties, name, true);
            Boolean actual = switch (name) {
                case "on_fire" -> subject.isOnFire();
                case "on_ground" -> subject.onGround();
                default -> null;
            };
            if (actual == null) {
                AddonEntities.unsupported("loot condition entity_properties " + name);
                return false;
            }
            if (actual != wanted) return false;
        }
        return true;
    }

    /** A variant nobody has set reads as zero, which is the one every Bedrock entity starts at. */
    private boolean number(AddonState state, Entity subject, String name) {
        double stored = subject == null || state == null ? 0
            : asNumber(state.property(subject.getUUID(), name));
        return stored == Json.number(bedrock, "value", 0);
    }

    private boolean property(AddonState state, Entity subject) {
        if (subject == null || state == null) return false;

        Object stored = state.property(subject.getUUID(), Json.text(bedrock, "domain", ""));
        return Boolean.TRUE.equals(stored) == Json.bool(bedrock, "value", true);
    }

    private static double asNumber(Object stored) {
        return stored instanceof Number held ? held.doubleValue() : 0;
    }

    private double difficultyChance(LootContext context) {
        String named = DIFFICULTIES.get(context.getLevel().getDifficulty());
        double fallback = Json.number(bedrock, "default_chance", 0);
        return named == null ? fallback : Json.number(bedrock, named, fallback);
    }

    /** A pet is anything a player tamed, which is what Bedrock counts alongside the player. */
    private static boolean killedByPlayerOrPets(Entity killer) {
        if (killer instanceof Player) return true;
        return killer instanceof OwnableEntity owned && owned.getOwner() instanceof Player;
    }

    private boolean biomeHasTag(LootContext context) {
        String tag = Json.text(bedrock, "tag", "");
        if (!BiomeMatch.WORLDS.contains(tag) && !BiomeMatch.NOT_BIOMES.containsKey(tag)
            && !BiomeMatch.JAVA_BIOMES.containsKey(tag)) {
            AddonEntities.unsupported("loot condition biome_has_tag " + tag);
            return false;
        }
        return new BiomeMatch.Tag(tag, false).matches(Platform.lootBiome(context));
    }
}
