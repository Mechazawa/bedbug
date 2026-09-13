package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Cowers while the sky is breaking, which is the whole of what a Bedrock panda does in a storm.
 *
 * `minecraft:behavior.scared` names only how often the entity whimpers; the weather is the trigger
 * and the entity stopping where it stands is the effect. A pack's own render controller draws the
 * pose off `q.is_scared`, so the state is carried as the property that query reads.
 */
public final class ScaredGoal extends Goal {
    private final AddonEntity mob;
    private final int soundDelay;

    public ScaredGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.soundDelay = Math.max(1, (int) Goals.number(component, "sound_delay", 20));
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return mob.level().isThundering();
    }

    @Override
    public void start() {
        mob.setValue("is_scared", true);
    }

    @Override
    public void tick() {
        mob.getNavigation().stop();
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
        if (mob.getRandom().nextInt(soundDelay) == 0) {
            mob.playSound(SoundEvents.PANDA_WORRIED_AMBIENT, mob.getSoundVolume(), mob.getVoicePitch());
        }
    }

    @Override
    public void stop() {
        mob.setValue("is_scared", false);
    }
}
