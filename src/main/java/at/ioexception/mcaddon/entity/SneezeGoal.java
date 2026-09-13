package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * Builds up to a sneeze, startles whatever is standing nearby and may leave something behind.
 *
 * `minecraft:behavior.sneeze` is a baby panda's, and what a player sees is the pause while it winds
 * up, the noise, and the mobs around it jumping. The dropped item comes out of the loot table the
 * pack named, rolled the way any other table is.
 */
public final class SneezeGoal extends Goal {
    private final AddonEntity mob;
    private final double probability;
    private final int prepareTime;
    private final int cooldown;
    private final double dropChance;
    private final double withinRadius;
    private final String lootTable;
    private final SoundEvent prepareSound;
    private final SoundEvent sneezeSound;
    private int prepared;
    private int untilNext;

    public SneezeGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.probability = Goals.number(component, "probability", 0.02);
        this.prepareTime = Goals.ticks(component, "prepare_time", 1);
        this.cooldown = Goals.ticks(component, "cooldown_time", 0);
        this.dropChance = Goals.number(component, "drop_item_chance", 1);
        this.withinRadius = Goals.number(component, "within_radius", 0);
        this.lootTable = Goals.text(component, "loot_table");
        this.prepareSound = sound(Goals.text(component, "prepare_sound"),
            SoundEvents.PANDA_PRE_SNEEZE);
        this.sneezeSound = sound(Goals.text(component, "sound"), SoundEvents.PANDA_SNEEZE);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return untilNext-- <= 0 && mob.getRandom().nextDouble() < probability;
    }

    @Override
    public boolean canContinueToUse() {
        return prepared < prepareTime;
    }

    @Override
    public void start() {
        prepared = 0;
        mob.setValue("is_sneezing", true);
        mob.getNavigation().stop();
        mob.playSound(prepareSound, mob.getSoundVolume(), mob.getVoicePitch());
    }

    @Override
    public void tick() {
        prepared++;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        mob.setValue("is_sneezing", false);
        untilNext = cooldown;
        mob.playSound(sneezeSound, mob.getSoundVolume(), mob.getVoicePitch());
        if (!(mob.level() instanceof ServerLevel level)) return;

        level.sendParticles(ParticleTypes.SNEEZE, mob.getX(), mob.getEyeY(), mob.getZ(),
            8, 0.1, 0.1, 0.1, 0.02);
        startle(level);
        drop(level);
    }

    /** Everything the pack's radius reaches jumps, which is the part of a sneeze a player sees. */
    private void startle(ServerLevel level) {
        if (withinRadius <= 0) return;

        for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class,
            mob.getBoundingBox().inflate(withinRadius))) {
            if (nearby == mob || !nearby.onGround()) continue;

            nearby.setDeltaMovement(nearby.getDeltaMovement().add(0, 0.42, 0));
        }
    }

    private void drop(ServerLevel level) {
        if (lootTable == null || mob.getRandom().nextDouble() > dropChance) return;

        Identifier table = AddonEntities.lootTable(mob.getType(), lootTable);
        if (table == null) return;

        for (ItemStack stack : Platform.rollLoot(level, table, mob)) {
            if (stack.isEmpty()) continue;

            level.addFreshEntity(new ItemEntity(level, mob.getX(), mob.getY(), mob.getZ(), stack));
        }
    }

    /** The sound a pack named, or the one the game already plays for this when it named none. */
    private static SoundEvent sound(String named, SoundEvent fallback) {
        Identifier id = named == null ? null
            : Identifier.tryParse(named.contains(":") ? named : "minecraft:" + named);
        return id == null ? fallback
            : net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.getOptional(id)
                .orElse(fallback);
    }
}
