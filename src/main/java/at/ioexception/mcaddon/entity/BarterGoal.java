package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Holding something up to look at it, and paying for it afterwards.
 *
 * Bedrock splits a piglin's trade over two behaviours and two components. Admiring is what stops the
 * entity while it turns over whatever it picked up, and `minecraft:admire_item` says for how long.
 * Bartering is what happens when the admiring ends: `minecraft:barter` names a loot table, and what
 * that rolls is thrown to whoever is standing nearest.
 *
 * The two halves meet on the entity, because one goal cannot see another. The admiring goal writes
 * down the tick its admiring ends and the bartering goal waits for it. Each component's
 * `cooldown_after_being_attacked` keeps its half from starting while the entity was hurt recently,
 * and both components are read as the goal starts, because a pack puts them in the group that makes
 * an entity willing to trade.
 */
public final class BarterGoal extends Goal {
    /** How far the entity looks for somebody to pay. */
    private static final double PAY_RANGE = 8;

    /** How hard a payment is thrown, and how much of that goes upward. */
    private static final double THROW_FORCE = 0.2;
    private static final double THROW_RISE = 1.5;

    private final AddonEntity mob;
    private final boolean admires;
    private final String onStart;
    private final String onStop;
    private final String sound;
    private final int soundLow;
    private final int soundHigh;
    private int untilSound;

    private BarterGoal(AddonEntity mob, JsonObject component, boolean admires) {
        this.mob = mob;
        this.admires = admires;
        this.onStart = Goals.event(component, "on_admire_item_start");
        this.onStop = Goals.event(component, "on_admire_item_stop");
        this.sound = Goals.text(component, "admire_item_sound");
        this.soundLow = Goals.ticksLow(component, "sound_interval", 0);
        this.soundHigh = Goals.ticksHigh(component, "sound_interval", 0);
        setFlags(admires ? EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK) : EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.admire_item`, which stands still turning over what it just picked up. */
    public static BarterGoal admiring(AddonEntity mob, JsonObject component) {
        return new BarterGoal(mob, component, true);
    }

    /** `minecraft:behavior.barter`, which pays for what it admired once it has finished admiring. */
    public static BarterGoal bartering(AddonEntity mob, JsonObject component) {
        return new BarterGoal(mob, component, false);
    }

    @Override
    public boolean canUse() {
        if (mob.getMainHandItem().isEmpty()) return false;

        return admires
            ? mob.admireUntilTick() < 0 && mob.calmSinceHurt("minecraft:admire_item")
            : admiringHasEnded() && mob.calmSinceHurt("minecraft:barter") && payee() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return admires && !mob.getMainHandItem().isEmpty() && !admiringHasEnded();
    }

    @Override
    public void start() {
        if (!admires) return;

        mob.setAdmireUntilTick(mob.tickCount + Goals.ticks(mob.liveComponent("minecraft:admire_item"), "duration", 10));
        untilSound = 0;
        ServerHooks.state().setProperty(mob.getUUID(), "is_admiring", true);
        mob.getNavigation().stop();
        fire(onStart);
    }

    @Override
    public void stop() {
        if (!admires) return;

        ServerHooks.state().setProperty(mob.getUUID(), "is_admiring", false);
        fire(onStop);
    }

    @Override
    public void tick() {
        if (admires) {
            mob.getNavigation().stop();
            if (sound != null && untilSound-- <= 0) {
                mob.makeNoise(sound);
                untilSound = Goals.between(mob.getRandom(), soundLow, soundHigh);
            }
            return;
        }

        Player paying = payee();
        if (paying == null) return;

        mob.getLookControl().setLookAt(paying, 30f, 30f);
        pay(paying);
        mob.setAdmireUntilTick(-1);
    }

    /** Takes what the entity is holding and throws back whatever the pack's table rolled for it. */
    private void pay(Player paying) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        String table = Goals.text(mob.liveComponent("minecraft:barter"), "barter_table");
        Identifier named = table == null ? null : AddonEntities.lootTable(mob.getType(), table);
        if (named == null) return;

        Vec3 towards = paying.position().subtract(mob.position()).normalize();
        for (ItemStack rolled : Platform.rollLoot(level, named, mob)) {
            if (rolled.isEmpty()) continue;

            ItemEntity thrown = new ItemEntity(level, mob.getX(), mob.getEyeY(), mob.getZ(), rolled);
            thrown.setDeltaMovement(towards.x * THROW_FORCE,
                towards.y * THROW_FORCE + THROW_FORCE * THROW_RISE, towards.z * THROW_FORCE);
            level.addFreshEntity(thrown);
        }
    }

    /** Whether the entity has admired for as long as the pack asked. */
    private boolean admiringHasEnded() {
        return mob.admireUntilTick() >= 0 && mob.tickCount >= mob.admireUntilTick();
    }

    /** The nearest player close enough to be paid, or null when nobody is. */
    private Player payee() {
        List<Player> nearby = mob.level().getEntitiesOfClass(Player.class,
            mob.getBoundingBox().inflate(PAY_RANGE));
        return nearby.stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    private void fire(String event) {
        if (event != null) EntityEvents.trigger(mob, event, null, ServerHooks.state());
    }
}
