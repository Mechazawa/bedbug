package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Jumps and shouts once the raid underfoot has been beaten.
 *
 * `minecraft:behavior.celebrate` gives a raider its victory dance, and names how long it lasts, how
 * often it jumps and how often it cheers. The event at the end is the pack's own, which is how a
 * pack sends a celebrating entity away again.
 */
public final class CelebrateGoal extends Goal {
    private final AddonEntity mob;
    private final int duration;
    private final int jumpLow;
    private final int jumpHigh;
    private final int soundLow;
    private final int soundHigh;
    private final String endEvent;
    private int celebrated;
    private int untilJump;
    private int untilSound;

    public CelebrateGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.duration = Goals.ticks(component, "duration", 30);
        this.jumpLow = Goals.ticksLow(component, "jump_interval", 1);
        this.jumpHigh = Goals.ticksHigh(component, "jump_interval", 3.5);
        this.soundLow = Goals.ticksLow(component, "sound_interval", 2);
        this.soundHigh = Goals.ticksHigh(component, "sound_interval", 7);
        this.endEvent = Goals.event(component, "on_celebration_end_event");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return mob.level() instanceof ServerLevel level && Platform.raidWon(level, mob.blockPosition());
    }

    @Override
    public boolean canContinueToUse() {
        return celebrated < duration;
    }

    @Override
    public void start() {
        celebrated = 0;
        untilJump = 0;
        untilSound = 0;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        celebrated++;
        if (untilJump-- <= 0) {
            untilJump = Goals.between(mob.getRandom(), jumpLow, jumpHigh);
            if (mob.onGround()) mob.setDeltaMovement(mob.getDeltaMovement().add(0, 0.42, 0));
        }
        if (untilSound-- > 0) return;

        untilSound = Goals.between(mob.getRandom(), soundLow, soundHigh);
        mob.playSound(SoundEvents.VILLAGER_CELEBRATE, mob.getSoundVolume(), mob.getVoicePitch());
    }

    @Override
    public void stop() {
        if (endEvent != null) EntityEvents.trigger(mob, endEvent, null, ServerHooks.state());
    }
}
