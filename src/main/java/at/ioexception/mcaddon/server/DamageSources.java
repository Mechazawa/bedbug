package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.script.ScriptHost;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What hurt something, told the way a pack's script asks about it.
 *
 * Bedrock hands a script an object naming the cause, whatever dealt the damage and whatever was
 * thrown. Java says the same things through a damage source, under its own names for the causes.
 */
public final class DamageSources {
    /** Java's name for a cause, against the name Bedrock's `EntityDamageCause` uses for the same one. */
    private static final Map<String, String> CAUSES = Map.ofEntries(
        Map.entry("inFire", "fire"),
        Map.entry("onFire", "fireTick"),
        Map.entry("lava", "lava"),
        Map.entry("hotFloor", "magma"),
        Map.entry("inWall", "suffocation"),
        Map.entry("cramming", "suffocation"),
        Map.entry("drown", "drowning"),
        Map.entry("starve", "starve"),
        Map.entry("cactus", "contact"),
        Map.entry("fall", "fall"),
        Map.entry("flyIntoWall", "flyIntoWall"),
        Map.entry("outOfWorld", "void"),
        Map.entry("generic", "none"),
        Map.entry("magic", "magic"),
        Map.entry("wither", "wither"),
        Map.entry("anvil", "fallingBlock"),
        Map.entry("fallingBlock", "fallingBlock"),
        Map.entry("dragonBreath", "magic"),
        Map.entry("dryout", "dryout"),
        Map.entry("freeze", "freezing"),
        Map.entry("sting", "entityAttack"),
        Map.entry("mob", "entityAttack"),
        Map.entry("player", "entityAttack"),
        Map.entry("arrow", "projectile"),
        Map.entry("fireball", "projectile"),
        Map.entry("thrown", "projectile"),
        Map.entry("trident", "projectile"),
        Map.entry("indirectMagic", "magic"),
        Map.entry("thorns", "thorns"),
        Map.entry("explosion", "blockExplosion"),
        Map.entry("explosion.player", "entityExplosion"),
        Map.entry("sonic_boom", "sonicBoom"),
        Map.entry("lightningBolt", "lightning"),
        Map.entry("stalagmite", "stalagmite"),
        Map.entry("fallingStalactite", "stalactite"));

    private DamageSources() {
    }

    public static Map<String, Object> describe(DamageSource source) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("cause", CAUSES.getOrDefault(source.getMsgId(), "none"));
        if (source.getEntity() != null) {
            described.put("damagingEntity", new ScriptHost.EntityHandle(source.getEntity().getStringUUID()));
        }
        if (source.getDirectEntity() != null && source.getDirectEntity() != source.getEntity()) {
            described.put("damagingProjectile",
                new ScriptHost.EntityHandle(source.getDirectEntity().getStringUUID()));
        }
        return described;
    }

    /**
     * The Java damage source a Bedrock cause names, carrying `source` wherever Java's own source has
     * room for whatever dealt the damage.
     *
     * A cause Java has no form of becomes an attack by `source`, so the kill is still attributed to
     * it, and a nameless one when there is nobody to attribute it to.
     */
    public static DamageSource forCause(ServerLevel level, String cause, Entity source) {
        var sources = level.damageSources();
        return switch (cause == null ? "none" : cause) {
            case "fire", "campfire", "soulCampfire" -> sources.inFire();
            case "fireTick" -> sources.onFire();
            case "lava" -> sources.lava();
            case "magma" -> sources.hotFloor();
            case "suffocation" -> sources.inWall();
            case "drowning" -> sources.drown();
            case "starve" -> sources.starve();
            case "contact" -> sources.cactus();
            case "fall" -> sources.fall();
            case "flyIntoWall" -> sources.flyIntoWall();
            case "void" -> sources.fellOutOfWorld();
            case "magic" -> sources.magic();
            case "wither" -> sources.wither();
            case "freezing", "temperature" -> sources.freeze();
            case "lightning" -> sources.lightningBolt();
            case "stalagmite" -> sources.stalagmite();
            case "stalactite" -> sources.fallingStalactite(source);
            case "anvil" -> sources.anvil(source);
            case "fallingBlock" -> sources.fallingBlock(source);
            case "thorns" -> sources.thorns(source);
            case "sonicBoom" -> sources.sonicBoom(source);
            case "blockExplosion" -> sources.explosion(null, null);
            case "entityExplosion", "fireworks", "selfDestruct" -> sources.explosion(source, source);
            case "projectile", "ramAttack", "charging" -> sources.thrown(source, source);
            default -> attack(level, source);
        };
    }

    private static DamageSource attack(ServerLevel level, Entity source) {
        if (source instanceof Player player) return level.damageSources().playerAttack(player);
        if (source instanceof LivingEntity living) return level.damageSources().mobAttack(living);
        return level.damageSources().generic();
    }
}
