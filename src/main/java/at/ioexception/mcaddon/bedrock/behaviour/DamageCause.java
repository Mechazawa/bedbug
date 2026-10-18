package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonElement;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bedrock's damage causes, against the Java damage types that are the same harm.
 *
 * An entity file spells a cause `fire_tick` and a script spells it `fireTick`. Java names the same
 * harm by a damage type id, `minecraft:on_fire`, and several types are often one Bedrock cause: a
 * cactus and a berry bush are both `contact`. A cause listing no type this version declares is
 * dealt as its stand-in and answers for whatever its stand-in answers for, because Java cannot tell
 * the two apart: a snow golem too warm for its biome burns with `on_fire` in Java.
 */
public enum DamageCause {
    NONE("generic", "outside_border"),
    OVERRIDE(List.of(), false, NONE),
    CONTACT("cactus", "sweet_berry_bush"),
    ENTITY_ATTACK(List.of("mob_attack", "player_attack", "mob_attack_no_aggro", "sting", "spear"), true, null),
    PROJECTILE(List.of("mob_projectile", "arrow", "trident", "spit", "wind_charge", "fireball",
        "unattributed_fireball", "wither_skull", "thrown"), true, null),
    SUFFOCATION("in_wall", "cramming", "dry_out"),
    FALL("fall", "ender_pearl"),
    FIRE("in_fire"),
    FIRE_TICK("on_fire"),
    LAVA("lava"),
    DROWNING("drown"),
    /** Java writes `explosion` for both explosion causes and fills in what exploded only for one. */
    BLOCK_EXPLOSION("explosion", "bad_respawn_point"),
    ENTITY_EXPLOSION(List.of("explosion", "player_explosion"), true, null),
    VOID("out_of_world"),
    SELF_DESTRUCT(List.of("generic_kill"), false, NONE),
    MAGIC("magic", "indirect_magic", "dragon_breath"),
    WITHER("wither"),
    STARVE("starve"),
    ANVIL(List.of("falling_anvil"), true, null),
    THORNS(List.of("thorns"), true, null),
    FALLING_BLOCK(List.of("falling_block"), true, null),
    PISTON(List.of(), false, NONE),
    FLY_INTO_WALL("fly_into_wall"),
    MAGMA("hot_floor", "sulfur_cube_hot"),
    FIREWORKS(List.of("fireworks"), true, null),
    LIGHTNING("lightning_bolt"),
    CHARGING(List.of(), true, ENTITY_ATTACK),
    TEMPERATURE(List.of(), false, FIRE_TICK),
    FREEZING("freeze"),
    STALACTITE(List.of("falling_stalactite"), true, null),
    STALAGMITE("stalagmite"),
    RAM_ATTACK(List.of(), true, ENTITY_ATTACK),
    SONIC_BOOM(List.of("sonic_boom"), true, null),
    CAMPFIRE(List.of("campfire"), false, FIRE),
    SOUL_CAMPFIRE(List.of(), false, CAMPFIRE),
    MACE_SMASH(List.of("mace_smash"), true, ENTITY_ATTACK),
    /** A damage sensor's word for every cause at once. */
    ALL(List.of(), false, NONE);

    /** The damage types this version of Java declares, by path. */
    private static final Set<String> DECLARED = Arrays.stream(DamageTypes.class.getFields())
        .filter(field -> Modifier.isStatic(field.getModifiers()))
        .map(DamageCause::constant)
        .filter(ResourceKey.class::isInstance)
        .map(key -> Platform.idOf((ResourceKey<?>) key).getPath())
        .collect(Collectors.toUnmodifiableSet());

    private static final Map<String, DamageCause> BY_TYPE = new HashMap<>();

    static {
        for (DamageCause cause : values()) {
            cause.types.forEach(type -> BY_TYPE.putIfAbsent(type, cause));
        }
    }

    private final boolean attributed;
    private final DamageCause standIn;
    private final List<String> types;

    DamageCause(String... types) {
        this(List.of(types), false, null);
    }

    DamageCause(List<String> types, boolean attributed, DamageCause standIn) {
        this.types = types;
        this.attributed = attributed;
        this.standIn = standIn;
    }

    /**
     * The cause a pack names, in the entity file's spelling or the script's. Null, and reported,
     * for a name that is not one of Bedrock's.
     */
    public static DamageCause named(String name) {
        String folded = name.replace("_", "");
        DamageCause named = Arrays.stream(values())
            .filter(cause -> cause.name().replace("_", "").equalsIgnoreCase(folded))
            .findFirst().orElse(null);
        if (named == null) AddonEntities.unsupported("damage cause " + name);
        return named;
    }

    /** The causes a list in a pack names, leaving out the names that are not Bedrock's. */
    public static Set<DamageCause> allNamed(List<JsonElement> names) {
        return names.stream()
            .filter(JsonElement::isJsonPrimitive)
            .map(name -> named(name.getAsString()))
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(DamageCause.class)));
    }

    /** The cause Bedrock would report for a Java damage source. */
    public static DamageCause of(DamageSource source) {
        String type = source.typeHolder().unwrapKey()
            .map(Platform::idOf)
            .filter(id -> id.getNamespace().equals("minecraft"))
            .map(id -> id.getPath()).orElse("");
        if (type.equals("explosion")) {
            return source.getDirectEntity() != null ? ENTITY_EXPLOSION : BLOCK_EXPLOSION;
        }
        return BY_TYPE.getOrDefault(type, NONE);
    }

    /** `fire_tick` as an entity file writes it. */
    public String entityName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** `fireTick` as `EntityDamageCause` spells it. */
    public String scriptName() {
        String[] words = entityName().split("_");
        return words[0] + Arrays.stream(words).skip(1)
            .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
            .collect(Collectors.joining());
    }

    /** Whether a pack naming this cause hears this damage. */
    public boolean matches(DamageSource source) {
        if (this == ALL) return true;
        return of(source) == this || (javaType() == null && standIn.matches(source));
    }

    /** The damage type id this cause is dealt as on this version, its stand-in's when it has none. */
    public String damageType() {
        String own = javaType();
        return own != null ? "minecraft:" + own : standIn.damageType();
    }

    /**
     * Damage of this cause, carrying `by` wherever the Java type has room for whoever dealt it, so a
     * kill is still credited.
     */
    public DamageSource source(HolderLookup.Provider registries, Entity by) {
        if (javaType() == null) return standIn.source(registries, by);

        String type = this == ENTITY_ATTACK && by instanceof Player ? "player_attack" : javaType();
        var holder = registries.lookupOrThrow(Registries.DAMAGE_TYPE)
            .getOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, Platform.id("minecraft", type)));
        return attributed && by != null ? new DamageSource(holder, by, by) : new DamageSource(holder);
    }

    private String javaType() {
        return types.stream().filter(DECLARED::contains).findFirst().orElse(null);
    }

    private static Object constant(java.lang.reflect.Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException unreadable) {
            return null;
        }
    }
}
