package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.player.Player;

/**
 * Looks at somebody nearby for as long as the pack's `look_time` says, inside its field of view.
 *
 * Java's goal looks for two to four seconds whatever it is told, which is Bedrock's default too; a
 * pack's own range is held here instead, so Mojang's one to two seconds stays one to two seconds.
 * The three Bedrock behaviours that look differ in who is looked at: any player, the player being
 * traded with, or whichever entity the pack's filters name.
 */
final class LookGoal extends LookAtPlayerGoal {
    private final AddonEntity looker;
    private final int shortest;
    private final int longest;
    private final float horizontalView;
    private final float verticalView;
    private final boolean tradingOnly;
    private final Filter filters;
    private int remaining;

    private LookGoal(AddonEntity looker, Class<? extends LivingEntity> kind, JsonObject goal, boolean tradingOnly) {
        super(looker, kind, (float) Goals.number(goal, "look_distance", 8),
            (float) Goals.number(goal, "probability", 0.02));
        this.looker = looker;
        this.shortest = Goals.ticksLow(goal, "look_time", 2);
        this.longest = Goals.ticksHigh(goal, "look_time", 4);
        this.horizontalView = (float) Goals.number(goal, "angle_of_view_horizontal", 360);
        this.verticalView = (float) Goals.number(goal, "angle_of_view_vertical", 360);
        this.tradingOnly = tradingOnly;
        this.filters = Goals.filter(goal, "filters");
    }

    /** `minecraft:behavior.look_at_player`: the nearest player. */
    static LookGoal atPlayers(AddonEntity looker, JsonObject goal) {
        return new LookGoal(looker, Player.class, goal, false);
    }

    /** `minecraft:behavior.look_at_trading_player`: the player on the other side of a trade. */
    static LookGoal atTradingPlayer(AddonEntity looker, JsonObject goal) {
        return new LookGoal(looker, Player.class, goal, true);
    }

    /** `minecraft:behavior.look_at_entity`: the nearest living thing its filters allow. */
    static LookGoal atEntities(AddonEntity looker, JsonObject goal) {
        return new LookGoal(looker, LivingEntity.class, goal, false);
    }

    @Override
    public boolean canUse() {
        if (tradingOnly) {
            lookAt = looker.getTradingPlayer();
            return lookAt != null;
        }
        if (!super.canUse()) return false;
        if (inView() && filters.matches(new Filter.Context(looker, lookAt, lookAt, ServerHooks.state()))) {
            return true;
        }

        lookAt = null;
        return false;
    }

    /** `angle_of_view_horizontal` and `angle_of_view_vertical`, centred on where the entity faces. */
    private boolean inView() {
        double dx = lookAt.getX() - looker.getX();
        double dz = lookAt.getZ() - looker.getZ();
        double dy = lookAt.getEyeY() - looker.getEyeY();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return Math.abs(Mth.wrapDegrees(yaw - looker.getYHeadRot())) <= horizontalView / 2
            && Math.abs(Mth.wrapDegrees(pitch - looker.getXRot())) <= verticalView / 2;
    }

    @Override
    public boolean canContinueToUse() {
        return lookAt != null && lookAt.isAlive() && remaining > 0
            && looker.distanceToSqr(lookAt) <= (double) lookDistance * lookDistance;
    }

    @Override
    public void start() {
        super.start();
        remaining = Goals.between(looker.getRandom(), shortest, longest);
    }

    @Override
    public void tick() {
        super.tick();
        remaining--;
    }

    @Override
    public String toString() {
        return "LookGoal with " + remaining + " ticks left";
    }
}
