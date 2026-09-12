package at.ioexception.mcaddon.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.UUID;

/**
 * Every call whose spelling differs between the supported Minecraft versions.
 *
 * Keeping them here is what lets the rest of the mod read as ordinary code. Whole-class renames are
 * handled by the string replacements in the buildscript instead.
 */
public final class Platform {
    private Platform() {
    }

    /** The factory replaced the public constructor in 1.21. */
    public static Identifier id(String namespace, String path) {
        //? if >=1.21 {
        return Identifier.fromNamespaceAndPath(namespace, path);
        //?} else {
        /*return new Identifier(namespace, path);
        *///?}
    }

    public static Identifier idOf(ResourceKey<?> key) {
        //? if >=26 {
        return key.identifier();
        //?} else {
        /*return key.location();
        *///?}
    }

    public static Entity entityByUuid(ServerLevel level, UUID uuid) {
        //? if >=26 {
        return level.getEntityInAnyDimension(uuid);
        //?} else {
        /*return level.getEntity(uuid);
        *///?}
    }

    public static Set<String> tagsOf(Entity entity) {
        //? if >=26 {
        return entity.entityTags();
        //?} else {
        /*return entity.getTags();
        *///?}
    }

    /** Ticks since dawn on day zero. The name changed when the clock moved to the overworld. */
    public static long dayTime(Level level) {
        //? if >=26 {
        return level.getOverworldClockTime();
        //?} else {
        /*return level.getDayTime();
        *///?}
    }

    public static void kill(Entity entity, ServerLevel level) {
        //? if >=1.21.2 {
        entity.kill(level);
        //?} else {
        /*entity.kill();
        *///?}
    }

    public static void hurt(Entity entity, ServerLevel level, DamageSource source, float amount) {
        //? if >=1.21.2 {
        entity.hurtServer(level, source, amount);
        //?} else {
        /*entity.hurt(source, amount);
        *///?}
    }

    /** Sets position and rotation without interpolating from where the entity was. */
    public static void placeAt(Entity entity, double x, double y, double z, float yaw, float pitch) {
        //? if >=26 {
        entity.snapTo(x, y, z, yaw, pitch);
        //?} else {
        /*entity.moveTo(x, y, z, yaw, pitch);
        *///?}
    }

    public static CommandSourceStack commandSourceOf(Entity entity, ServerLevel level) {
        //? if >=26 {
        return entity.createCommandSourceStackForNameResolution(level);
        //?} else {
        /*return entity.createCommandSourceStack();
        *///?}
    }

    public static GameType gameMode(ServerPlayer player) {
        //? if >=26 {
        return player.gameMode();
        //?} else {
        /*return player.gameMode.getGameModeForPlayer();
        *///?}
    }

    public static Entity createMob(EntityType<?> type, ServerLevel level) {
        //? if >=1.21.2 {
        return type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        //?} else {
        /*return type.create(level);
        *///?}
    }

    /**
     * False when no effect is registered under that id.
     *
     * Three forms rather than two: the effect instance started taking a holder in 1.20.5, and the
     * registry lookup that returns one was `getHolder` until 26 renamed it to `get`.
     */
    public static boolean addEffect(LivingEntity entity, Identifier effect, int duration, int amplifier,
        boolean particles) {
        //? if >=26 {
        return BuiltInRegistries.MOB_EFFECT.get(effect)
            .map(holder -> entity.addEffect(new MobEffectInstance(holder, duration, amplifier, false, particles)))
            .orElse(false);
        //?} else if >=1.21 {
        /*return BuiltInRegistries.MOB_EFFECT.getHolder(effect)
            .map(holder -> entity.addEffect(new MobEffectInstance(holder, duration, amplifier, false, particles)))
            .orElse(false);
        *///?} else {
        /*net.minecraft.world.effect.MobEffect found = BuiltInRegistries.MOB_EFFECT.get(effect);
        return found != null
            && entity.addEffect(new MobEffectInstance(found, duration, amplifier, false, particles));
        *///?}
    }

    public static boolean removeEffect(LivingEntity entity, Identifier effect) {
        //? if >=26 {
        return BuiltInRegistries.MOB_EFFECT.get(effect).map(entity::removeEffect).orElse(false);
        //?} else if >=1.21 {
        /*return BuiltInRegistries.MOB_EFFECT.getHolder(effect).map(entity::removeEffect).orElse(false);
        *///?} else {
        /*net.minecraft.world.effect.MobEffect found = BuiltInRegistries.MOB_EFFECT.get(effect);
        return found != null && entity.removeEffect(found);
        *///?}
    }

    /** The slot the player is holding. It was a public field before 26 gave it an accessor. */
    public static int selectedSlot(ServerPlayer player) {
        //? if >=26 {
        return player.getInventory().getSelectedSlot();
        //?} else {
        /*return player.getInventory().selected;
        *///?}
    }

    /** The buildable height range, lowest block and one past the highest. */
    public static int[] heightRange(ServerLevel level) {
        //? if >=26 {
        return new int[] {level.getMinY(), level.getMaxY() + 1};
        //?} else {
        /*return new int[] {level.getMinBuildHeight(), level.getMaxBuildHeight()};
        *///?}
    }
}
