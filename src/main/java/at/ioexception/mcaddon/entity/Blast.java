package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.convert.BedrockSoundNames;
import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Optional;

/**
 * The blast `minecraft:explode` describes, and the rules Java's explosion asks it about.
 *
 * It breaks blocks whatever the mob griefing rule says unless `destroy_affected_by_griefing` asks
 * for the rule, which is Java's mob blast, lights fires the same way under
 * `fire_affected_by_griefing`, and caps what any block withstands at `max_resistance`. Under
 * `allow_underwater` the water around it holds nothing back. `damage_scaling` and
 * `knockback_scaling` scale what it does to entities, `toggles_blocks` works the switches of a blast
 * that breaks nothing, and `negates_fall_damage` spares a player it throws upward the fall. A wind
 * burst is drawn with Java's gusts. Before 1.21 Java's explosion asks nothing about entities and
 * takes no particles, sound or switching, so those fields are reported there instead.
 */
final class Blast extends ExplosionDamageCalculator {
    private final AddonEntity source;
    private final float power;
    private final float cap;
    private final boolean underwater;
    private final float damageScale;
    private final float knockbackScale;
    private final boolean fire;
    private final Level.ExplosionInteraction breaking;
    private final boolean negatesFall;
    private final boolean gust;
    private final Holder<SoundEvent> sound;

    Blast(AddonEntity source, JsonObject explode, ServerLevel level) {
        this.source = source;
        this.power = (float) Goals.number(explode, "power", 3);
        this.cap = (float) Goals.number(explode, "max_resistance", Float.MAX_VALUE);
        this.underwater = Goals.flag(explode, "allow_underwater", false);
        this.damageScale = (float) Goals.number(explode, "damage_scaling", 1);
        this.knockbackScale = (float) Goals.number(explode, "knockback_scaling", 1);
        this.fire = Goals.number(explode, "causes_fire", 0) != 0
            && (!Goals.flag(explode, "fire_affected_by_griefing", false) || Platform.mobGriefing(level));
        boolean breaks = Goals.number(explode, "breaks_blocks", 1) != 0;
        this.breaking = !breaks
            ? Goals.flag(explode, "toggles_blocks", false) ? Platform.toggling() : Level.ExplosionInteraction.NONE
            : Goals.flag(explode, "destroy_affected_by_griefing", false)
                ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.BLOCK;
        this.negatesFall = Goals.flag(explode, "negates_fall_damage", false);
        String particles = Goals.text(explode, "particle_effect");
        this.gust = "wind_burst".equals(particles) || "breeze_wind_burst".equals(particles);
        String named = Goals.text(explode, "sound_effect");
        String java = BedrockSoundNames.java(named == null ? "explode" : named);
        //? if <1.21 {
        /*at.ioexception.mcaddon.registry.AddonEntities.unsupportedFields("minecraft:explode", explode,
            OLDER_JAVA_LACKS);
        *///?}
        this.sound = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(BuiltInRegistries.SOUND_EVENT.getOptional(
                Identifier.tryParse(java == null ? "minecraft:entity.generic.explode" : java))
            .orElseThrow());
    }

    /** What Java's explosion asks no blast about before 1.21. */
    private static final java.util.List<String> OLDER_JAVA_LACKS = java.util.List.of("damage_scaling",
        "knockback_scaling", "toggles_blocks", "negates_fall_damage", "particle_effect", "sound_effect");

    void goOff(ServerLevel level) {
        Platform.explode(level, source, this, source.position(), power, fire, breaking, gust, sound);
        if (!negatesFall) return;

        level.getEntitiesOfClass(Player.class, source.getBoundingBox().inflate(power * 2),
                player -> player.getY() >= source.getY())
            .forEach(Platform::spareTheFall);
    }

    @Override
    public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter level, BlockPos position,
        BlockState block, FluidState fluid) {
        Optional<Float> resistance = underwater && !fluid.isEmpty()
            ? Optional.of(block.getBlock() instanceof LiquidBlock
                ? 0f : block.getBlock().getExplosionResistance())
            : super.getBlockExplosionResistance(explosion, level, position, block, fluid);
        return resistance.map(withstood -> Math.min(withstood, cap));
    }

    /** A capped resistance lets the blast through water, which stays where it is. */
    @Override
    public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos position, BlockState block,
        float strength) {
        return !(block.getBlock() instanceof LiquidBlock)
            && super.shouldBlockExplode(explosion, level, position, block, strength);
    }

    //? if >=1.21 {
    @Override
    public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
        return damageScale > 0 && super.shouldDamageEntity(explosion, entity);
    }

    @Override
    public float getKnockbackMultiplier(Entity entity) {
        return super.getKnockbackMultiplier(entity) * knockbackScale;
    }
    //?}

    //? if >=26 {
    @Override
    public float getEntityDamageAmount(Explosion explosion, Entity entity, float exposure) {
        return super.getEntityDamageAmount(explosion, entity, exposure) * damageScale;
    }
    //?} else if >=1.21 {
    /*@Override
    public float getEntityDamageAmount(Explosion explosion, Entity entity) {
        return super.getEntityDamageAmount(explosion, entity) * damageScale;
    }
    *///?}
}
