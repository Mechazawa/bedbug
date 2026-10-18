package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.DamageCause;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.PanicGoal;

import java.util.EnumSet;
import java.util.Set;

/**
 * Runs about after being hurt by one of the causes `minecraft:behavior.panic` lists.
 *
 * Java's goal decides for itself what is worth panicking at and heads for water only while burning.
 * Bedrock lists the causes, can leave out every blow another mob dealt, can head for water whatever
 * the hurt was, and with `force` panics with no hurt at all and does not stop until it has nowhere
 * left to run, which is how a bee that has stung spends the rest of its life.
 */
public final class PanickingGoal extends PanicGoal {
    /** Bedrock's own list, which a pack naming no causes panics at. */
    private static final Set<DamageCause> DEFAULT_CAUSES = EnumSet.of(DamageCause.CAMPFIRE,
        DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.FREEZING, DamageCause.LAVA,
        DamageCause.LIGHTNING, DamageCause.MAGMA, DamageCause.SOUL_CAMPFIRE, DamageCause.TEMPERATURE,
        DamageCause.ENTITY_ATTACK, DamageCause.ENTITY_EXPLOSION, DamageCause.FIREWORKS, DamageCause.MAGIC,
        DamageCause.PROJECTILE, DamageCause.RAM_ATTACK, DamageCause.SONIC_BOOM, DamageCause.WITHER,
        DamageCause.MACE_SMASH);

    /** How far round the entity Java's own goal looks for water, in blocks. */
    private static final int WATER_SEARCH = 5;

    private final AddonEntity panicking;
    private final Set<DamageCause> causes;
    private final boolean ignoresMobs;
    private final boolean forced;
    private final boolean prefersWater;
    private final String sound;
    private final int soundLow;
    private final int soundHigh;
    private int nextSoundAt;

    public PanickingGoal(AddonEntity panicking, JsonObject component) {
        super(panicking, Goals.number(component, "speed_multiplier", 1));
        this.panicking = panicking;
        this.causes = component.has("damage_sources")
            ? DamageCause.allNamed(Json.list(component, "damage_sources"))
            : DEFAULT_CAUSES;
        this.ignoresMobs = Goals.flag(component, "ignore_mob_damage", false);
        this.forced = Goals.flag(component, "force", false);
        this.prefersWater = Goals.flag(component, "prefer_water", false);
        this.sound = Goals.text(component, "panic_sound");
        JsonObject interval = Goals.object(component, "sound_interval");
        this.soundLow = Goals.ticks(interval, "range_min", 0);
        this.soundHigh = Math.max(soundLow, Goals.ticks(interval, "range_max", 0));
    }

    @Override
    protected boolean shouldPanic() {
        if (forced) return true;

        DamageSource hurt = panicking.getLastDamageSource();
        if (hurt == null) return false;
        if (ignoresMobs && hurt.getEntity() instanceof Mob) return false;
        return causes.stream().anyMatch(cause -> cause.matches(hurt));
    }

    @Override
    public boolean canUse() {
        if (!shouldPanic()) return false;
        if ((prefersWater || panicking.isOnFire()) && headForWater()) return true;
        return findRandomPosition();
    }

    /** A forced panic runs on to somewhere new each time it arrives, until there is nowhere to go. */
    @Override
    public boolean canContinueToUse() {
        if (!panicking.getNavigation().isDone()) return true;
        if (!forced || !findRandomPosition()) return false;

        panicking.getNavigation().moveTo(posX, posY, posZ, speedModifier);
        return true;
    }

    @Override
    public void start() {
        super.start();
        nextSoundAt = panicking.tickCount;
    }

    /**
     * Makes `panic_sound` as the panic starts and again after each wait `sound_interval` spans, in
     * seconds. A pack stating no interval hears the sound once per panic.
     */
    @Override
    public void tick() {
        if (sound == null || nextSoundAt < 0 || panicking.tickCount < nextSoundAt) return;

        panicking.makeNoise(sound);
        nextSoundAt = soundHigh <= 0 ? -1
            : panicking.tickCount + Goals.between(panicking.getRandom(), Math.max(1, soundLow), soundHigh);
    }

    private boolean headForWater() {
        BlockPos water = lookForWater(panicking.level(), panicking, WATER_SEARCH);
        if (water == null) return false;

        posX = water.getX();
        posY = water.getY();
        posZ = water.getZ();
        return true;
    }
}
