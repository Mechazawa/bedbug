package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.TradeTable;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * The two halves of a Bedrock trader's attention: the shop being open, and wanting it to be.
 *
 * `minecraft:trade_table` says what is for sale and the merchant screen shows it. These two goals are
 * what the trader does around that: it stands still and faces whoever has the shop open, and before
 * anybody has opened it, it notices a player holding something it buys and holds up what it sells
 * for that item, turning to the next such ware every `carried_item_switch_time` and putting it away
 * once the player has held nothing it buys for `remove_item_time`.
 */
public final class TradeGoal extends Goal {
    private final AddonEntity mob;
    private final boolean whileTrading;
    private final double maxDistance;
    private final double withinRadius;
    private final int interestTime;
    private final int cooldown;
    private final int switchTime;
    private final int removeTime;
    private final Filter conditions;
    private int interested;
    private int readyAt;
    private int switchAt;
    private int lostAt;
    private int showing;
    private Player customer;
    private ItemStack shown = ItemStack.EMPTY;

    private TradeGoal(AddonEntity mob, JsonObject component, boolean whileTrading) {
        this.mob = mob;
        this.whileTrading = whileTrading;
        this.maxDistance = Goals.number(component, "max_distance_from_player", 8);
        this.withinRadius = Math.max(1, Goals.number(component, "within_radius", 8));
        this.interestTime = Goals.ticks(component, "interest_time", 45);
        this.cooldown = Goals.ticks(component, "cooldown", 2);
        this.switchTime = Math.max(1, Goals.ticks(component, "carried_item_switch_time", 2));
        this.removeTime = Goals.ticks(component, "remove_item_time", 1);
        this.conditions = Goals.filter(component, "filters");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.trade_with_player`, which holds the trader still while the shop is open. */
    public static TradeGoal whileTrading(AddonEntity mob, JsonObject component) {
        return new TradeGoal(mob, component, true);
    }

    /** `minecraft:behavior.trade_interest`, which shows a likely customer what is for sale. */
    public static TradeGoal showingWares(AddonEntity mob, JsonObject component) {
        return new TradeGoal(mob, component, false);
    }

    @Override
    public boolean canUse() {
        if (whileTrading) {
            customer = mob.getTradingPlayer();
            return customer != null && mob.distanceTo(customer) <= maxDistance
                && conditions.matches(
                    new Filter.Context(mob, customer, customer, ServerHooks.state()));
        }

        if (mob.tickCount < readyAt || mob.getTradingPlayer() != null) return false;

        customer = mob.level().getEntitiesOfClass(Player.class,
                mob.getBoundingBox().inflate(withinRadius)).stream()
            .filter(this::carriesSomethingWanted)
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
        return customer != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (whileTrading) {
            return mob.getTradingPlayer() != null
                && mob.distanceTo(mob.getTradingPlayer()) <= maxDistance;
        }
        return customer != null && interested < interestTime && mob.getTradingPlayer() == null
            && (lostAt < 0 || mob.tickCount - lostAt < removeTime);
    }

    @Override
    public void start() {
        interested = 0;
        if (whileTrading) return;

        shown = mob.getMainHandItem().copy();
        lostAt = -1;
        showing = -1;
        showNext();
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (customer == null) return;

        interested++;
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(customer, 30f, 30f);
        if (whileTrading) return;

        if (!carriesSomethingWanted(customer)) {
            if (lostAt < 0) lostAt = mob.tickCount;
        } else {
            lostAt = -1;
            if (mob.tickCount >= switchAt) showNext();
        }
    }

    @Override
    public void stop() {
        if (whileTrading) return;

        readyAt = mob.tickCount + cooldown;
        mob.setItemSlot(EquipmentSlot.MAINHAND, shown);
        shown = ItemStack.EMPTY;
    }

    /** Whether the player is holding something one of the trader's own offers asks for. */
    private boolean carriesSomethingWanted(Player player) {
        return !player.getMainHandItem().isEmpty() && !forSale(player.getMainHandItem().getItem()).isEmpty();
    }

    /**
     * Holds up the next of the things sold for what the customer is holding, which is what
     * `carried_item_switch_time` turns through while the customer keeps holding it.
     */
    private void showNext() {
        java.util.List<ItemStack> wares = forSale(customer.getMainHandItem().getItem());
        switchAt = mob.tickCount + switchTime;
        if (wares.isEmpty()) return;

        showing = (showing + 1) % wares.size();
        mob.setItemSlot(EquipmentSlot.MAINHAND, wares.get(showing));
    }

    /** What the trader sells for the item, in the order its table lists the trades. */
    private java.util.List<ItemStack> forSale(net.minecraft.world.item.Item wanted) {
        TradeTable trades = mob.trades();
        if (trades == null) return java.util.List.of();

        return trades.offers().stream()
            .filter(offer -> AddonItems.named(offer.cost().item()) == wanted
                || (offer.secondCost() != null && AddonItems.named(offer.secondCost().item()) == wanted))
            .map(offer -> stack(offer.result().item()))
            .filter(stack -> !stack.isEmpty())
            .toList();
    }

    private static ItemStack stack(String item) {
        net.minecraft.world.item.Item found = item == null ? null : AddonItems.named(item);
        return found == null ? ItemStack.EMPTY : new ItemStack(found);
    }
}
