package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/**
 * Celebrating the end of a raid, for as long as the component group carrying the goal is on.
 *
 * Bedrock's village raises `minecraft:start_celebrating` on the entities around a raid as it ends, and
 * Mojang's own entities add the group carrying the goal from that event. So the goal starts whenever
 * it is live, lasts its `duration` and then raises `on_celebration_end_event`, which is how the pack
 * takes the group away again.
 *
 * `minecraft:behavior.celebrate` is a raider's victory dance: it jumps and makes its
 * `celebration_sound` at intervals. `minecraft:behavior.celebrate_survive` is a villager's: it sends
 * up a firework every `fireworks_interval`.
 */
public final class CelebrateGoal extends Goal {
    private final AddonEntity mob;
    private final boolean survived;
    private final int duration;
    private final int jumpLow;
    private final int jumpHigh;
    private final String sound;
    private final int soundLow;
    private final int soundHigh;
    private final int fireworksLow;
    private final int fireworksHigh;
    private final String endEvent;
    private int celebrated;
    private int untilJump;
    private int untilSound;
    private int untilFirework;

    private CelebrateGoal(AddonEntity mob, JsonObject component, boolean survived) {
        this.mob = mob;
        this.survived = survived;
        this.duration = Goals.ticks(component, "duration", 30);
        this.jumpLow = Goals.ticksLow(component, "jump_interval", 1);
        this.jumpHigh = Goals.ticksHigh(component, "jump_interval", 3.5);
        String named = Goals.text(component, "celebration_sound");
        this.sound = named == null ? "celebrate" : named;
        this.soundLow = Goals.ticksLow(component, "sound_interval", 2);
        this.soundHigh = Goals.ticksHigh(component, "sound_interval", 7);
        this.fireworksLow = Goals.ticksLow(component, "fireworks_interval", 10);
        this.fireworksHigh = Goals.ticksHigh(component, "fireworks_interval", 20);
        this.endEvent = Goals.event(component, "on_celebration_end_event");
        setFlags(survived ? EnumSet.noneOf(Goal.Flag.class) : EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    /** `minecraft:behavior.celebrate`, a raider's. */
    public static CelebrateGoal winning(AddonEntity mob, JsonObject component) {
        return new CelebrateGoal(mob, component, false);
    }

    /** `minecraft:behavior.celebrate_survive`, a villager's. */
    public static CelebrateGoal surviving(AddonEntity mob, JsonObject component) {
        return new CelebrateGoal(mob, component, true);
    }

    @Override
    public boolean canUse() {
        return true;
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
        untilFirework = Goals.between(mob.getRandom(), fireworksLow, fireworksHigh);
        if (!survived) mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        celebrated++;
        if (survived) {
            if (untilFirework-- > 0) return;

            untilFirework = Goals.between(mob.getRandom(), fireworksLow, fireworksHigh);
            mob.level().addFreshEntity(new FireworkRocketEntity(mob.level(), mob.getX(), mob.getEyeY(), mob.getZ(),
                new ItemStack(Items.FIREWORK_ROCKET)));
            return;
        }

        if (untilJump-- <= 0) {
            untilJump = Goals.between(mob.getRandom(), jumpLow, jumpHigh);
            if (mob.onGround()) mob.setDeltaMovement(mob.getDeltaMovement().add(0, 0.42, 0));
        }
        if (untilSound-- > 0) return;

        untilSound = Goals.between(mob.getRandom(), soundLow, soundHigh);
        mob.makeNoise(sound);
    }

    @Override
    public void stop() {
        if (celebrated >= duration && endEvent != null) {
            EntityEvents.trigger(mob, endEvent, null, ServerHooks.state());
        }
    }
}
