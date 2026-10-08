package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.TargetSelection;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.phys.AABB;

/**
 * Picks the nearest entity `minecraft:behavior.nearest_attackable_target` names as the one to attack.
 *
 * Java's goal chooses by class and searches as far as the mob's follow range; Bedrock chooses by the
 * filters of each `entity_types` entry, as far as that entry's `max_dist`. So the goal is given
 * every living entity and the entries decide, and its reach is the furthest any entry allows.
 */
final class HuntGoal extends NearestAttackableTargetGoal<LivingEntity> {
    private final AddonEntity hunter;
    private final TargetSelection selection;
    private final double searchHeight;
    private final double acceptance;
    private final boolean reselects;
    private final int persistTicks;
    private int rescanIn;
    private int keepUntil;

    HuntGoal(AddonEntity hunter, JsonObject goal, TargetSelection selection) {
        this(hunter, goal, selection, !Goals.flag(goal, "attack_owner", false));
    }

    private HuntGoal(AddonEntity hunter, JsonObject goal, TargetSelection selection, boolean sparesOwner) {
        //? if >=26 {
        super(hunter, LivingEntity.class, interval(goal), Goals.flag(goal, "must_see", false),
            Goals.flag(goal, "must_reach", false),
            (target, level) -> selection.accepts(hunter, target) && !(sparesOwner && hunter.sides(target)));
        //?} else {
        /*super(hunter, LivingEntity.class, interval(goal), Goals.flag(goal, "must_see", false),
            Goals.flag(goal, "must_reach", false),
            target -> selection.accepts(hunter, target) && !(sparesOwner && hunter.sides(target)));
        *///?}
        this.hunter = hunter;
        this.selection = selection;
        this.searchHeight = Goals.number(goal, "target_search_height", -1);
        this.acceptance = Goals.number(goal, "target_acquisition_probability", 1);
        this.reselects = Goals.flag(goal, "reselect_targets", false);
        this.persistTicks = Goals.ticks(goal, "persist_time", 0);
        setUnseenMemoryTicks(Goals.ticks(goal, "must_see_forget_duration", 3));
        // The range was fixed while the reach above was still unread.
        targetConditions.range(getFollowDistance());
    }

    /**
     * How many ticks the goal waits between searches on average.
     *
     * `attack_interval` is seconds and wins when the pack states one; `scan_interval` is already
     * ticks, which is how Java counts its own random interval.
     */
    private static int interval(JsonObject goal) {
        int seconds = Goals.ticksHigh(goal, "attack_interval", 0);
        return seconds > 0 ? seconds : (int) Goals.number(goal, "scan_interval", 10);
    }

    @Override
    protected double getFollowDistance() {
        double own = super.getFollowDistance();
        return selection == null ? own : Math.max(own, selection.maxDistance());
    }

    /** `target_search_height` is how far up and down to look; Bedrock's default is the reach itself. */
    @Override
    protected AABB getTargetSearchArea(double distance) {
        double height = searchHeight > 0 ? searchHeight : distance;
        return hunter.getBoundingBox().inflate(distance, height, distance);
    }

    /** A target found is accepted at `target_acquisition_probability`, asked each time one is found. */
    @Override
    public boolean canUse() {
        if (!super.canUse()) return false;
        if (acceptance >= 1 || hunter.getRandom().nextDouble() < acceptance) return true;

        target = null;
        return false;
    }

    /** `persist_time` keeps the attack up for that many seconds after the target stopped being valid. */
    @Override
    public boolean canContinueToUse() {
        if (super.canContinueToUse()) {
            keepUntil = hunter.tickCount + persistTicks;
            return true;
        }
        LivingEntity kept = hunter.getTarget();
        return kept != null && kept.isAlive() && hunter.tickCount < keepUntil;
    }

    /**
     * `reselect_targets` turns to whatever is nearest now, rather than keeping the first target
     * until it stops being one.
     */
    @Override
    public void tick() {
        if (!reselects || --rescanIn > 0) return;

        rescanIn = Math.max(1, randomInterval);
        findTarget();
        if (target != null && target != hunter.getTarget()) hunter.setTarget(target);
    }
}
